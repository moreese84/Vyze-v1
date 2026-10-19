package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.VyzeApplication
import com.vyze.app.util.CrashLogFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Reproduces the LiteRT-LM #3165 condition the MiniCPM-V-4.7-1B model card
 * documents: conversations served on ONE shared engine inherit the previous
 * conversation's recurrent (GatedDeltaNet) state — "the second and third
 * conversations on one Engine gave different, broken answers to the same
 * eight questions, a fresh Engine the same answers every time". Our app
 * keeps one [VlmEngineManager] engine for the whole session, so this probe
 * fires the SAME question N times, N separate Conversations, one engine.
 *
 * Golden check: runs 2/3 must equal run 1 (same prompt, temperature 0.1,
 * top_k 1 — effectively deterministic). Any drift/broken answer confirms
 * #3165 on our serving path; a per-engine-cache fix decision follows.
 *
 * adb trigger (debug build):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.RepeatQueryProbeReceiver \
 *         --ei times 3
 *
 * Optional extras: --es text "..." (default: a text-only geography question
 * the card's corpus answers), --ei delayMs 2500 (gap between runs).
 *
 * IMAGE MODE (--ez image true): runs the same N-loop through the IMAGE
 * path ([VlmEngineManager.analyzeImage]) on a SYNTHESIZED 640×640 bitmap
 * (solid shapes + text — content irrelevant, timing real: the full vision
 * encode + prompt prefill + decode run on the engine's actual backends).
 * Used with the vision-GPU corridor receiver this turns the
 * "GPU vision vs CPU vision" prefill A/B into a scripted, repeatable
 * measurement (one [VlmEngineManager] engine call per run, same probe
 * discipline as the text runs).
 */
class RepeatQueryProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val question = intent.getStringExtra("text")
            ?: "What is the capital of France? Answer in one short sentence."
        val times = intent.getIntExtra("times", 3).coerceIn(1, 8)
        val delayMs = intent.getIntExtra("delayMs", 2500).toLong()
        val imageMode = intent.getBooleanExtra("image", false)

        val controller = (context.applicationContext as VyzeApplication).coreController
        if (controller == null) {
            Log.w(TAG, "Probe: coreController not ready — launch the app first")
            return
        }
        val engine = controller.vlmEngine
        if (engine.getActiveBackend() == "NONE") {
            Log.w(TAG, "Probe: engine not initialized — launch the app first")
            return
        }

        val probeBitmap = if (imageMode) synthesizedProbeBitmap() else null

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            CrashLogFile.log(
                TAG,
                "=== #3165 REPEAT-QUERY PROBE: $times runs, " +
                    (if (imageMode) "IMAGE" else "text") + ", same question, one engine ==="
            )
            for (i in 1..times) {
                delay(delayMs)
                val start = System.currentTimeMillis()
                val answer = try {
                    if (imageMode && probeBitmap != null) {
                        engine.analyzeImage(
                            probeBitmap,
                            question,
                            sessionId = "probe3165-img-$i",
                            maxTokens = 96
                        )
                    } else {
                        engine.analyzeText(question, sessionId = "probe3165-$i", maxTokens = 96)
                    }
                } catch (e: Throwable) {
                    "<THROWN: ${e.javaClass.simpleName}: ${e.message}>"
                }
                val elapsed = System.currentTimeMillis() - start
                CrashLogFile.log(
                    TAG,
                    "PROBE run=$i/$times backend=${engine.getActiveBackend()} " +
                        "time=${elapsed}ms answer=${answer?.take(140) ?: "<null>"}"
                )
            }
        }
    }

    /**
     * Synthetic 640×640 probe image: flat foreground shapes over a
     * contrasting background plus a text label. Timing-relevant only —
     * the vision encoder's cost is driven by input pixels, not content —
     * so the probe stays deterministic and needs no camera surface.
     */
    private fun synthesizedProbeBitmap(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
            .apply {
                val canvas = android.graphics.Canvas(this)
                canvas.drawColor(android.graphics.Color.rgb(190, 214, 230))
                canvas.drawRect(
                    80f, 380f, 300f, 560f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(30, 90, 170); isAntiAlias = true
                    }
                )
                canvas.drawOval(
                    360f, 120f, 560f, 320f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(200, 50, 50); isAntiAlias = true
                    }
                )
                android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK; textSize = 64f; isAntiAlias = true
                }.let { canvas.drawText("PROBE 112", 150f, 120f, it) }
            }

    private companion object {
        const val TAG = "RepeatQueryProbe"
    }
}

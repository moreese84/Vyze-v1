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
 */
class RepeatQueryProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val question = intent.getStringExtra("text")
            ?: "What is the capital of France? Answer in one short sentence."
        val times = intent.getIntExtra("times", 3).coerceIn(1, 8)
        val delayMs = intent.getIntExtra("delayMs", 2500).toLong()

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

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            CrashLogFile.log(TAG, "=== #3165 REPEAT-QUERY PROBE: $times runs, same question, one engine ===")
            for (i in 1..times) {
                delay(delayMs)
                val start = System.currentTimeMillis()
                val answer = try {
                    engine.analyzeText(question, sessionId = "probe3165-$i", maxTokens = 96)
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

    private companion object {
        const val TAG = "RepeatQueryProbe"
    }
}

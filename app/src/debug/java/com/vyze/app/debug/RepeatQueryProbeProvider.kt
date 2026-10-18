package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.vyze.app.VyzeApplication
import com.vyze.app.util.CrashLogFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY ContentProvider stub (src/debug source set — release builds
 * have no such component): manifest broadcasts to a cached app never
 * dispatched on our Android 16 target (observed 2026-10-18: "Enqueued
 * broadcast" with no receiver execution), while ContentProviders DO run at
 * process start. This provider starts — in-process, deterministically — the
 * LiteRT-LM #3165 repeat-query probe ([RepeatQueryProbeReceiver] documents
 * what it does and why).
 *
 * Probe parameters live in vyze_debug_flags.xml (debug-only prefs file):
 *   - repeat_probe_times   (int, default 3, max 8)
 *   - repeat_probe_delay_ms (int, default 3000)
 *   - repeat_probe_question (string, optional)
 * The provider launches a detached coroutine that polls engine readiness
 * (getActiveBackend() != NONE, up to 5 min) and only then runs the probe —
 * Created then Navigated flow means coreController appears later.
 *
 * adb trigger (debug build):
 *
 *     adb shell run-as com.vyze.app sh -c \
 *         "sed -i 's|<map>|<map><int name=\"repeat_probe_times\" value=\"3\" />|' \
 *          shared_prefs/vyze_debug_flags.xml"
 *     adb shell am force-stop com.vyze.app && adb shell am start \
 *         -n com.vyze.app/.MainActivity
 */
class RepeatQueryProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val prefs = ctx.getSharedPreferences("vyze_debug_flags", Context.MODE_PRIVATE)
        val times = prefs.getInt("repeat_probe_times", 0)
        if (times !in 1..8) return false  // not armed — no-op provider
        val delayMs = prefs.getInt("repeat_probe_delay_ms", 3000).coerceAtLeast(300).toLong()
        val question = prefs.getString(
            "repeat_probe_question",
            "What is the capital of France? Answer in one short sentence."
        ) ?: "What is the capital of France? Answer in one short sentence."

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Wait for the engine (LoadingFragment init pipeline), max 5 min.
            var ready = false
            for (attempt in 1..60) {
                delay(5_000)
                val controller = (ctx as VyzeApplication).coreController ?: continue
                if (controller.vlmEngine.getActiveBackend() != "NONE") {
                    ready = true
                    break
                }
            }
            if (!ready) {
                CrashLogFile.log(TAG, "Probe: engine never became ready — aborting")
                return@launch
            }
            CrashLogFile.log(
                TAG,
                "=== #3165 REPEAT-QUERY PROBE: $times runs, one question, one shared engine ==="
            )
            val engine = (ctx as VyzeApplication).coreController?.vlmEngine ?: return@launch
            for (i in 1..times) {
                if (i > 1) delay(delayMs)
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
        return true
    }

    // ── Stub provider: nothing queryable, no authorities in use ──
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object {
        const val TAG = "RepeatQueryProbe"
    }
}

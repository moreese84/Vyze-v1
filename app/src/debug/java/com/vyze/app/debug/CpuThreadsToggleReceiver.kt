package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.core.VlmEngineManager

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Overrides the CPU thread count used for [Backend.CPU] engine attempts
 * (Tier 2 CPU fallback / Tier 3) so a device session can measure XNNPack
 * thread scaling directly: same scene, same query, one cold restart per
 * value — no APK rebuild.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.CpuThreadsToggleReceiver --ei threads 2
 *     adb shell am broadcast -n com.vyze.app/.debug.CpuThreadsToggleReceiver --ei threads 4
 *     adb shell am broadcast -n com.vyze.app/.debug.CpuThreadsToggleReceiver --ei threads 6
 *     adb shell am broadcast -n com.vyze.app/.debug.CpuThreadsToggleReceiver --ei threads 8
 *
 *     # back to the tier default (min(4, max(2, cores/2))):
 *     adb shell am broadcast -n com.vyze.app/.debug.CpuThreadsToggleReceiver
 *
 * MEASUREMENT PROTOCOL (per value, capture from logcat):
 *  1. Broadcast the value above, cold-restart the app (the engine init
 *     reads the count when the backend is constructed).
 *  2. Verify the value took: logcat "CPU backend: N threads" (init) +
 *     "CPU backend thread override: requested=… clamped=…" lines.
 *  3. Run the SAME scripted scene (fridge front), SAME text query.
 *  4. Record the "[PIPELINE-BREAKDOWN] threads=N " line per run — it now
 *     carries the thread count inline so runs are self-labeling.
 *  5. Compare wall + PREFILL across values. Decode is ms-scale and is NOT
 *     expected to move the total; the question is whether PREFILL scales
 *     at all with threads on the little-core Helio SoCs.
 *
 * After the session: if a value beats the default by a meaningful margin,
 * promote it as a deliberate code change (CPU_FALLBACK_THREADS or a
 * tier-aware formula). If none does, the default stands and the experiment
 * is closed — the override stays debug-only.
 *
 * Same discipline as [PreGateToggleReceiver]: release builds have no such
 * components; [CpuThreadsStickyProvider] persists the value in debug builds
 * so the OEM memory manager killing the app between test cycles doesn't
 * re-darken it. Clamping (to available cores) happens in the engine, so an
 * out-of-range value can never crash an init.
 */
class CpuThreadsToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requested = if (intent.hasExtra("threads")) {
            intent.getIntExtra("threads", Int.MIN_VALUE)
        } else {
            Int.MIN_VALUE
        }
        if (requested == Int.MIN_VALUE) {
            // No extra → restore the tier default formula.
            VlmEngineManager.cpuThreadCountOverride = null
            CpuThreadsStickyProvider.clearSticky(context.applicationContext)
            Log.i(TAG, "CPU thread override CLEARED (tier default formula)")
        } else {
            VlmEngineManager.cpuThreadCountOverride = requested
            CpuThreadsStickyProvider.setSticky(context.applicationContext, requested)
            Log.i(TAG, "CPU thread override set to $requested (sticky: survives restarts; debug only)")
        }
    }

    private companion object {
        const val TAG = "CpuThreadsToggle"
    }
}

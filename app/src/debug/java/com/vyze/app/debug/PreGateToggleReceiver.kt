package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.agent.VyzeAgentRuntime

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Toggles [VyzeAgentRuntime.preGateEnabled] so the VLM pre-gate (latency
 * lever #1) can be exercised on a real device: with the flag on,
 * high-confidence IGNORE families (app-cue echo / greeting garble /
 * filler-only) skip capture + Gemma entirely and log
 * "VLM pre-gate: ... (VLM skipped)". The flag ships dark; the promotion
 * flow that flips it permanently is a deliberate manual act — this
 * receiver only affects the CURRENT process, so the default-dark contract
 * is restored on every restart. The runtime never enables itself; neither
 * does this receiver persist anything.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.PreGateToggleReceiver
 *     adb shell am broadcast -n com.vyze.app/.debug.PreGateToggleReceiver --ez enabled false
 *
 * (Omitting --ez toggles: on → off → on. The state is STICKY within
 * debug builds — [PreGateStickyProvider] persists it and restores it at
 * process start, so the OEM memory manager killing the app between test
 * cycles no longer re-darkens the flag mid-testing. Release builds have
 * no such components and always start dark.)
 *
 * Exported=true is intentional and safe: the component exists ONLY in
 * debug builds and its sole effect is flipping an in-memory boolean that
 * skips a few model inferences in this same app.
 */
class PreGateToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requested = if (intent.hasExtra("enabled")) {
            intent.getBooleanExtra("enabled", false)
        } else {
            !VyzeAgentRuntime.preGateEnabled
        }
        VyzeAgentRuntime.preGateEnabled = requested
        // STICKY (debug only): persist so PreGateStickyProvider restores
        // the state when the OEM memory manager kills and restarts the
        // process — otherwise every kill silently re-darkens the flag.
        PreGateStickyProvider.setSticky(context.applicationContext, requested)
        Log.i(TAG, "VLM pre-gate ${if (requested) "ENABLED" else "DISABLED"} (sticky: survives restarts; debug only)")
    }

    private companion object {
        const val TAG = "PreGateToggle"
    }
}

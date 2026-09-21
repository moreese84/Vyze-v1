package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.agent.VyzeAgentRuntime

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Toggles [VyzeAgentRuntime.shadowEnabled] so the shadow router (and the
 * distilled StudentRouter inside it) can be exercised on a real device via
 * the ROUTE log lines. The flag ships dark; the promotion flow that flips
 * it permanently is a deliberate manual act — this receiver only affects
 * the CURRENT process, so the default-dark contract is restored on every
 * restart. The runtime never enables itself; neither does this receiver
 * persist anything.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.ShadowRouteToggleReceiver
 *     adb shell am broadcast -n com.vyze.app/.debug.ShadowRouteToggleReceiver --ez enabled false
 *
 * (Omitting --ez toggles: on → off → on. The flag is process state — an
 * app restart resets it to false.)
 *
 * Exported=true is intentional and safe: the component exists ONLY in
 * debug builds and its sole effect is flipping an in-memory boolean that
 * gates LOG-ONLY routing decisions in this same app.
 */
class ShadowRouteToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requested = if (intent.hasExtra("enabled")) {
            intent.getBooleanExtra("enabled", false)
        } else {
            !VyzeAgentRuntime.shadowEnabled
        }
        VyzeAgentRuntime.shadowEnabled = requested
        Log.i(TAG, "Shadow router ${if (requested) "ENABLED" else "DISABLED"} (process-local)")
    }

    private companion object {
        const val TAG = "ShadowRouteToggle"
    }
}

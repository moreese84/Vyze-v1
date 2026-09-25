package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.agent.VyzeAgentRuntime

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Toggles [VyzeAgentRuntime.gemmaAlwaysEnabled] so Design A ("Gemma
 * always" — voice transcribes LOCALLY on every tap, online included) can
 * be exercised on a real device. With the flag on, the system recognizer
 * is not consulted at all; the transcript flows from Gemma's audio
 * encoder through the exact original pipeline (detectLocaleFromText →
 * onSpeechResult → student router → answer).
 *
 * Same discipline as [PreGateToggleReceiver]: the flag ships dark; this
 * receiver only affects the CURRENT process; [GemmaAlwaysStickyProvider]
 * persists the toggled state in debug builds so the OEM memory manager
 * killing the app between test cycles doesn't re-darken it. Release
 * builds have no such components and always start dark.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.GemmaAlwaysToggleReceiver
 *     adb shell am broadcast -n com.vyze.app/.debug.GemmaAlwaysToggleReceiver --ez enabled false
 *
 * (Omitting --ez toggles: on → off → on.)
 */
class GemmaAlwaysToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requested = if (intent.hasExtra("enabled")) {
            intent.getBooleanExtra("enabled", false)
        } else {
            !VyzeAgentRuntime.gemmaAlwaysEnabled
        }
        VyzeAgentRuntime.gemmaAlwaysEnabled = requested
        // STICKY (debug only): survive OEM process kills during testing.
        GemmaAlwaysStickyProvider.setSticky(context.applicationContext, requested)
        Log.i(TAG, "Gemma-always ${if (requested) "ENABLED" else "DISABLED"} " +
            "(voice transcribes locally, online included; sticky: survives restarts; debug only)")
    }

    private companion object {
        const val TAG = "GemmaAlwaysToggle"
    }
}

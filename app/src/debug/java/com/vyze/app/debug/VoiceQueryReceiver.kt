package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vyze.app.ui.fragments.CameraFragment
import com.vyze.app.util.CrashLogFile

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * WHY IT EXISTS: the voice lanes (detector bypasses, barcode speech,
 * repeat replay, confirmation, text-only routing) can only be driven by
 * spoken input — a scripted device session has no mouth. This receiver
 * supplies transcripts directly to the fragment's speech-result path:
 *
 * ```
 * adb shell am broadcast -n com.vyze.app/.debug.VoiceQueryReceiver \
 *     --es query "what color is this" --es locale "en-US"
 * ```
 *
 * Requires the camera screen to be resumed ([CameraFragment.instance]
 * is non-null only then). Confidence is pinned at 1.0 (no confirmation
 * detours) — the recognizer itself is out of scope by design.
 */
class VoiceQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val fragment = CameraFragment.instance
        if (fragment == null) {
            Log.w(TAG, "Inject: CameraFragment not resumed — open the camera screen first")
            return
        }
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (query.isBlank()) {
            Log.w(TAG, "Inject: missing --es query extra")
            return
        }
        val locale = intent.getStringExtra(EXTRA_LOCALE).takeUnless { it.isNullOrBlank() } ?: "en-US"
        Handler(Looper.getMainLooper()).post {
            fragment.debugInjectVoiceResult(query, locale)
            CrashLogFile.log(TAG, "Voice injection dispatched: \"$query\" lang=$locale")
        }
    }

    private companion object {
        const val TAG = "VoiceQueryProbe"
        const val EXTRA_QUERY = "query"
        const val EXTRA_LOCALE = "locale"
    }
}

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
 * WHY IT EXISTS: `CameraFragment.toggleContinuousMode()` — the continuous
 * auto-snapshot lane the plan §E0 FRAME-BUDGET session must measure — has
 * NO in-app caller (no button, no gesture, no XML onClick, no voice word;
 * git history never had one either). This receiver supplies that missing
 * trigger so a scripted device session can drive the lane:
 *
 * ```
 * adb shell am broadcast -n com.vyze.app/.debug.ContinuousToggleReceiver
 * ```
 *
 * Toggles once per broadcast; the fragment logs `Continuous mode ON/OFF`
 * itself. Requires the camera screen to be resumed ([CameraFragment.instance]
 * is non-null only then) and a cool-enough device (the fragment's thermal
 * guard auto-refuses with a spoken notice under SEVERE+). Process-local —
 * every app restart restores OFF.
 */
class ContinuousToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val fragment = CameraFragment.instance
        if (fragment == null) {
            Log.w(TAG, "Toggle: CameraFragment not resumed — open the camera screen first")
            return
        }
        Handler(Looper.getMainLooper()).post {
            fragment.toggleContinuousMode()
            CrashLogFile.log(TAG, "Continuous-mode toggle dispatched to fragment")
        }
    }

    private companion object {
        const val TAG = "ContinuousToggle"
    }
}

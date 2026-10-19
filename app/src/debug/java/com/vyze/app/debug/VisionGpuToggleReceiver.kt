package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.core.VlmEngineManager

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Forces the MiniCPM GPU-vision corridor on so the vision backend can be
 * A/B-measured on a real device ([VlmEngineManager.debugGpuVisionOverride]):
 *
 *  - `--ez enabled true`  → every subsequent engine build attempts GPU vision
 *    (decoder+vision all-GPU config — the 2026-10-10 experiment line).
 *  - `--ez enabled false` → back to the policy decision
 *    ([VlmEngineManager.companionMiniCpmVisionBackendName]).
 *  - No extra → flips (auto ↔ forced GPU).
 *
 * State is STICKY within debug builds ([VisionGpuStickyProvider] persists and
 * restores it at process start, so the OEM memory manager killing the app
 * between test cycles no longer re-darkens the override mid-testing).
 *
 * The override is read at ENGINE BUILD time — flip first, then force-stop
 * and relaunch the process so the engines rebuild with the forced setting.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.VisionGpuToggleReceiver --ez enabled true
 *     adb shell am broadcast -n com.vyze.app/.debug.VisionGpuToggleReceiver --ez enabled false
 *
 * Release builds have no such components: [VlmEngineManager.debugGpuVisionOverride]
 * would need a setter that does not exist there — no release code path writes it.
 * Exported=true is intentional and safe: the receiver exists only in debug
 * builds and its sole effect is flipping an in-memory backend-choice seam in
 * this same app.
 */
class VisionGpuToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requested = if (intent.hasExtra("enabled")) {
            intent.getBooleanExtra("enabled", false)
        } else {
            VlmEngineManager.debugGpuVisionOverride != true
        }
        VlmEngineManager.debugGpuVisionOverride = if (requested) true else null
        val humanState = if (requested) "FORCED GPU vision" else "AUTO (policy decides)"
        // STICKY (debug only): persist so [VisionGpuStickyProvider] restores the
        // state when the OEM memory manager kills and restarts the process.
        VisionGpuStickyProvider.setSticky(context.applicationContext, requested)
        Log.i(TAG, "Vision-GPU corridor: $humanState (sticky, debug only; rebuild engines to apply)")
    }

    private companion object {
        const val TAG = "VisionGpuToggle"
    }
}

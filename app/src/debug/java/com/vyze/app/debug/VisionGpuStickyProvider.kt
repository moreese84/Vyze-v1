package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.vyze.app.core.VlmEngineManager

/**
 * DEBUG-ONLY sticky restore for the GPU-vision corridor override
 * (src/debug source set — never compiled into release).
 *
 * WHY: the test device's OEM memory manager kills the app between test
 * cycles, and every new process restarts with
 * [VlmEngineManager.debugGpuVisionOverride] = null (policy). This provider
 * restores the last toggled state at process start, so a mid-testing kill
 * does not silently re-darken the corridor while the A/B is running.
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework
 * BEFORE Application.onCreate — the only component that runs at cold start
 * with no UI and no dev-machine trigger (same pattern as
 * [PreGateStickyProvider]).
 *
 * SAFETY / CONTRACT:
 *  - DEBUG ONLY: this class does not exist in release builds; the policy
 *    ([VlmEngineManager.companionMiniCpmVisionBackendName]) is the only
 *    vision-backend decider in production.
 *  - Storage is a two-line SharedPreferences file local to the app;
 *    nothing leaves the device.
 *  - onCreate is a synchronous SharedPreferences read + boolean assign
 *    (sub-millisecond); failures are logged, never thrown.
 */
class VisionGpuStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GPU_VISION_FORCE, false)
            if (saved) {
                VlmEngineManager.debugGpuVisionOverride = true
                android.util.Log.i(TAG, "Sticky GPU-vision corridor RESTORED at process start (debug)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Sticky restore failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return true
    }

    // ── Stub provider: no tables, no queries — exists only for onCreate. ──

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        private const val TAG = "VisionGpuSticky"
        private const val PREFS = "vyze_debug_flags"
        private const val KEY_GPU_VISION_FORCE = "gpu_vision_force"

        /** Persist the toggle so [onCreate] can restore it (debug only). */
        fun setSticky(appContext: Context, enabled: Boolean) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GPU_VISION_FORCE, enabled).apply()
        }
    }
}

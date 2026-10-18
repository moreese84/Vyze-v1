package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * DEBUG-ONLY sticky brain-override restore (src/debug source set — never
 * compiled into release).
 *
 * WHY: the test device's OEM memory manager (TRANSSION reclaim) kills the app
 * between test cycles, and every new process starts with the brain override
 * NULL (production auto-resolution). This provider restores the last forced
 * brain at process start so the corridor A/B result (MiniCPM vs Gemma) isn't
 * silently lost when the OEM kills the app mid-session.
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework BEFORE
 * Application.onCreate — the only component that runs at cold start with no
 * UI and no dev-machine trigger (same pattern as PreGateStickyProvider).
 *
 * SAFETY / CONTRACT:
 *  - DEBUG ONLY: this class does not exist in release builds, so the release
 *    app always uses the production default (MiniCPM, the only production
 *    brain) — the promotion flow (a deliberate manual act) is untouched.
 *  - Storage is a two-line SharedPreferences file local to the app; nothing
 *    leaves the device.
 *  - onCreate is a synchronous SharedPreferences read + assign
 *    (sub-millisecond); failures are logged, never thrown.
 */
class BrainStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedSlug = prefs.getString(KEY_BRAIN, null) ?: return true
            val brain = com.vyze.app.core.BrainModel.fromSlug(savedSlug)
            if (brain != null) {
                com.vyze.app.core.VlmEngineManager.brainOverride = brain
                android.util.Log.i(TAG, "Sticky brain override RESTORED: ${brain.displayName} (debug)")
            } else {
                android.util.Log.w(TAG, "Sticky brain slug '$savedSlug' unknown — ignored")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Sticky restore failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return true
    }

    // ── Stub provider: no tables, no queries — exists only for onCreate. ──

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        private const val TAG = "BrainSticky"
        private const val PREFS = "vyze_debug_flags"
        private const val KEY_BRAIN = "brain_override_slug"

        /** Store the forced brain (null = clear the override back to auto). */
        fun setSticky(context: Context, brain: com.vyze.app.core.BrainModel?) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_BRAIN, brain?.slug)
                    .apply()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Sticky save failed: ${t.message}")
            }
        }
    }
}

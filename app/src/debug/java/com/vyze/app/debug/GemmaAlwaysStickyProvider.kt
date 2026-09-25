package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * DEBUG-ONLY sticky-flag restore (src/debug source set — never compiled
 * into release) for the Design A "Gemma always" voice-engine flag.
 *
 * Same discipline as [PreGateStickyProvider]: the test device's OEM memory
 * manager (TRANSSION reclaim) kills the app between test cycles, and every
 * new process starts with the in-memory flag DARK (the ships-dark
 * contract). This provider restores the last toggled state at process
 * start so the flag survives kills/restarts DURING DEBUG TESTING.
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework
 * BEFORE Application.onCreate — the only component that runs at cold
 * start with no UI and no dev-machine trigger.
 *
 * SAFETY / CONTRACT:
 *  - DEBUG ONLY: this class does not exist in release builds, so the
 *    release app always starts with the flag dark — the promotion flow
 *    (a deliberate manual code change) is untouched.
 *  - Storage is a two-line SharedPreferences file local to the app;
 *    nothing leaves the device.
 *  - onCreate is a synchronous SharedPreferences read + boolean assign
 *    (sub-millisecond); failures are logged, never thrown.
 */
class GemmaAlwaysStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GEMMA_ALWAYS, false)
            com.vyze.app.agent.VyzeAgentRuntime.gemmaAlwaysEnabled = saved
            if (saved) {
                android.util.Log.i(TAG, "Sticky gemma-always flag RESTORED at process start (debug)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Sticky restore failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "GemmaAlwaysSticky"
        private const val PREFS = "vyze_debug_flags"
        private const val KEY_GEMMA_ALWAYS = "gemma_always_enabled"

        /** Persist the toggled state (called by the toggle receiver). */
        fun setSticky(context: Context, enabled: Boolean) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_GEMMA_ALWAYS, enabled).apply()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Sticky persist failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}

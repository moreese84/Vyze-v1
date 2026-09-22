package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * DEBUG-ONLY sticky-flag restore (src/debug source set — never compiled
 * into release).
 *
 * WHY: the test device's OEM memory manager (TRANSSION reclaim) kills the
 * app between test cycles, and every new process starts with the in-memory
 * pre-gate flag DARK (the ships-dark contract). That forced re-toggling
 * via adb before every single test — three test cycles were blocked by it
 * on 2026-09-22. This provider restores the last toggled state at process
 * start, so the flag survives kills/restarts DURING DEBUG TESTING.
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework
 * BEFORE Application.onCreate — the only component that runs at cold
 * start with no UI and no dev-machine trigger (same pattern as
 * TranscriptAutoExportProvider).
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
class PreGateStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PRE_GATE, false)
            com.vyze.app.agent.VyzeAgentRuntime.preGateEnabled = saved
            if (saved) {
                android.util.Log.i(TAG, "Sticky pre-gate flag RESTORED at process start (debug)")
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
        private const val TAG = "PreGateSticky"
        private const val PREFS = "vyze_debug_flags"
        private const val KEY_PRE_GATE = "pre_gate_enabled"

        /** Persist the toggle so [onCreate] can restore it (debug only). */
        fun setSticky(appContext: Context, enabled: Boolean) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PRE_GATE, enabled).apply()
        }
    }
}

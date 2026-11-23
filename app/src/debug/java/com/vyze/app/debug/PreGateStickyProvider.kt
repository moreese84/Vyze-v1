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
 * pre-gate flag at its code default. Before promotion that forced
 * re-toggling via adb before every single test — three test cycles were
 * blocked by it on 2026-09-22. This provider restores the last toggled
 * state at process start, so the flag survives kills/restarts DURING
 * DEBUG TESTING, including deliberate kill-switch toggles.
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework
 * BEFORE Application.onCreate — the only component that runs at cold
 * start with no UI and no dev-machine trigger (same pattern as
 * TranscriptAutoExportProvider).
 *
 * SAFETY / CONTRACT:
 *  - DEBUG ONLY: this class does not exist in release builds, so the
 *    release app always starts at the code default (lit since the
 *    2026-11-14 promotion; the kill switch is a one-line default flip).
 *  - Storage is a two-line SharedPreferences file local to the app;
 *    nothing leaves the device.
 *  - onCreate is a synchronous SharedPreferences read + boolean assign
 *    (sub-millisecond); failures are logged, never thrown.
 */
class PreGateStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getBoolean(KEY_PRE_GATE, true)
            com.vyze.app.agent.VyzeAgentRuntime.preGateEnabled = saved
            // Log RESTORED only for a genuine persisted value — a missing
            // key means the lit code default applied, not a restore
            // (live-caught 2026-11-23: the line fired on a prefs-wiped
            // restart and misled the verification read).
            if (saved && prefs.contains(KEY_PRE_GATE)) {
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

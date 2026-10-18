package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * DEBUG-ONLY sticky restore (src/debug source set — never compiled into
 * release) for the CPU thread-count override used by the thread-scaling
 * experiment (see [CpuThreadsToggleReceiver] for the adb protocol).
 *
 * Same discipline as [PreGateStickyProvider]: the test device's OEM memory
 * manager (TRANSSION reclaim) kills the app between test cycles, and every
 * new process starts with the in-memory override NULL. This provider
 * restores the last broadcast value at process start so the experiment
 * survives kills/restarts DURING DEBUG TESTING. Storage is the shared
 * `vyze_debug_flags` SharedPreferences file; nothing leaves the device.
 *
 * Contract mirrors the toggle receiver: the sentinel [NO_OVERRIDE] (-1)
 * means "tier default formula" (override null). Clamping to available
 * cores happens in the engine read path — a stale out-of-range value can
 * never crash an init.
 */
class CpuThreadsStickyProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        try {
            val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CPU_THREADS, NO_OVERRIDE)
            if (saved != NO_OVERRIDE) {
                com.vyze.app.core.VlmEngineManager.cpuThreadCountOverride = saved
                android.util.Log.i(TAG, "Sticky CPU thread override RESTORED at process start: $saved (debug)")
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
    ): Cursor = MatrixCursor(arrayOf<String>())

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        private const val TAG = "CpuThreadsSticky"
        private const val PREFS = "vyze_debug_flags"
        private const val KEY_CPU_THREADS = "cpu_threads_override"
        /** Sentinel for "no override" (Int cannot store null). */
        private const val NO_OVERRIDE = -1

        /** Persist the override so it survives OEM kills (debug builds only). */
        fun setSticky(appContext: Context, threads: Int) {
            try {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_CPU_THREADS, threads).apply()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "setSticky failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        /** Clear the persisted override (restore tier default on next start). */
        fun clearSticky(appContext: Context) {
            try {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_CPU_THREADS).apply()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "clearSticky failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}

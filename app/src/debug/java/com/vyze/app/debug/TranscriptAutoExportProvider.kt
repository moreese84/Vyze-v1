package com.vyze.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY auto-export hook (src/debug source set — never compiled into
 * release).
 *
 * WHY A PROVIDER: ContentProviders are instantiated by the framework BEFORE
 * Application.onCreate with zero UI and zero user action — the only
 * component that can run code at cold start without a trigger from a
 * development machine. The adb broadcast receiver
 * (InteractionLogExportReceiver) requires a connected dev machine; this
 * user's workflow transfers files by USB (no adb), so the export must run
 * ITSELF on the phone.
 *
 * WHAT IT DOES: on every cold app start, exports all recorded transcripts
 * to the PUBLIC Downloads folder (TranscriptDownloadsExport) where plain
 * USB file transfer can grab them. Consequence of the timing: the export
 * reflects everything recorded BEFORE this launch — the workflow is
 * "test with the app → close it → reopen it (auto-export fires) → copy
 * the file from Downloads on the next USB connection".
 *
 * SAFETY: onCreate launches the work on Dispatchers.IO and returns
 * immediately (provider init stays sub-millisecond, no ANR risk). The
 * export itself is a local DB read + local file write — no network, no
 * user-visible effect, and a no-op when storage access was not granted or
 * the stores are empty. Failure paths are logged, never thrown.
 */
class TranscriptAutoExportProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = TranscriptDownloadsExport.exportToDownloads(appContext)
                if (file != null) {
                    android.util.Log.i(TAG, "Auto-export complete: ${file.absolutePath}")
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Auto-export crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
            // Also dump the app's own recent logcat — the failing session's
            // flow logs (TTS/ASR/VLM) survive in the ring buffer and land in
            // Downloads for diagnosis without adb.
            try {
                DiagnosticsLogExporter.exportToDownloads(appContext)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Diag export crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
            // And the app's own continuous crash/flow log (vyze_crash.log) —
            // every cold start refreshes the Downloads copy so the session
            // verdict lines (GEMMA-PRIMARY CAPTURE/SKIP, Speech error, etc.)
            // are always pullable without adb.
            try {
                com.vyze.app.util.CrashLogFile.exportToDownloads(appContext)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Crash-log export crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
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

    private companion object {
        const val TAG = "TranscriptAutoExport"
    }
}

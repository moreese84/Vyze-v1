package com.vyze.app.debug

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vyze.app.data.VyzeDatabase
import java.io.File

/**
 * DEBUG-ONLY: copy the latest Jev transcript export to the phone's PUBLIC
 * Downloads folder so it can be moved to the development laptop with plain
 * USB file transfer — no adb required (the adb broadcast trigger assumes a
 * developer workflow this debug build's user does not use).
 *
 * Storage path: MANAGE_EXTERNAL_STORAGE is already declared for the debug
 * build's Downloads crash-log copy (CrashLogFile.exportToDownloads), and
 * that grant is checked before touching Downloads — without it this is a
 * silent no-op, never a crash.
 *
 * The file lands in Downloads with the SAME name the adb pull flow expects
 * (interactions_<epoch>.jsonl) so the laptop-side audit command is
 * identical either way.
 */
object TranscriptDownloadsExport {

    private const val TAG = "TranscriptDlExport"

    /** Export file name prefix, mirroring InteractionLogExporter's output. */
    private const val FILE_PREFIX = "interactions_"

    /**
     * Export all transcripts to Downloads (public storage).
     *
     * @return the written Downloads file, or null when there was nothing to
     *   export or storage access is unavailable.
     */
    suspend fun exportToDownloads(context: Context): File? {
        // 1. Storage grant check — same discipline as CrashLogFile.
        val hasAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }
        if (!hasAccess) {
            Log.w(TAG, "No external-storage access — Downloads export skipped")
            return null
        }

        // 2. Build the rows through the SAME pipeline as the app-scoped export.
        val db = VyzeDatabase.getInstance(context)
        val rows = InteractionLogExporter.collectRows(db)
        if (rows.isEmpty()) {
            Log.i(TAG, "Export: no speech interactions recorded yet — nothing to write")
            return null
        }

        // 3. Write to public Downloads (visible to USB file transfer / any
        //    file manager — app-scoped Android/data is blocked on 13+).
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ) ?: return null
        val outFile = File(downloadsDir, "${FILE_PREFIX}${System.currentTimeMillis()}.jsonl")
        return try {
            outFile.bufferedWriter(Charsets.UTF_8).use { w ->
                for (row in rows) {
                    w.write(row.toJson())
                    w.write("\n")
                }
            }
            Log.i(TAG, "Export: wrote ${rows.size} rows → ${outFile.absolutePath}")
            outFile
        } catch (t: Throwable) {
            Log.w(TAG, "Export: Downloads write failed: ${t.message}")
            null
        }
    }
}

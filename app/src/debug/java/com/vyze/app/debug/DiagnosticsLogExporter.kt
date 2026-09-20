package com.vyze.app.debug

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * DEBUG-ONLY: dump the app's OWN recent logcat to the public Downloads folder,
 * next to the transcript export — so a failing session (e.g. silent speech
 * queries) can be diagnosed without adb.
 *
 * How it works: `logcat -d` dumps the kernel log buffer and exits. Without the
 * READ_LOGS permission an app can only see its OWN log entries — which is
 * exactly the Vyze flow ([TTSManager], recognizer, VLM engine, locale
 * detection). The buffer survives process death, so the logs from the PREVIOUS
 * session (the failing one) are still there when this runs at the next cold
 * start.
 *
 * Output: Download/vyze_diag_<epoch>.log — filtered to Vyze-relevant lines
 * plus every error/warning, capped to keep the file sendable over USB.
 *
 * Safety: runs on Dispatchers.IO via TranscriptAutoExportProvider; failures
 * are logged, never thrown; no-op without storage access.
 */
object DiagnosticsLogExporter {

    private const val TAG = "DiagExporter"

    /** Ring-buffer lines to request from logcat. */
    private const val MAX_LINES = 4000

    /** Kept lines after filtering (keeps the file small enough to email). */
    private const val MAX_KEPT_LINES = 2500

    /** Line filters: Vyze flow tags (case-insensitive) or any E/W level. */
    private val INTERESTING = Regex(
        pattern = "(?i)vyze|ttsmanager|detectlocale|setuserlocale|vlmengine|recognizer|onspeech|onresults|onpartial|gemma|microphone|audiotrack|speech",
    )
    private val ANY_ERROR = Regex("^[EW]/")

    /**
     * Dump filtered logcat to Downloads.
     * @return the written file, or null when dumping/filtering failed.
     */
    fun exportToDownloads(context: Context): File? {
        val hasAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }
        if (!hasAccess) return null

        val raw = try {
            val pid = android.os.Process.myPid()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "$MAX_LINES", "--pid", "$pid"))
            proc.inputStream.bufferedReader(Charsets.UTF_8).readText().also {
                proc.waitFor()
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "logcat dump failed: ${t.message}")
            return null
        }
        if (raw.isBlank()) return null

        val allLines = raw.lines()
        val kept = allLines.asSequence()
            .filter { line ->
                val body = line.drop(18) // skip "MM-DD HH:MM:SS.mmm PID PID " prefix for matching
                INTERESTING.containsMatchIn(body) || ANY_ERROR.containsMatchIn(line)
            }
            .take(MAX_KEPT_LINES)
            .toList()

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ) ?: return null
        val outFile = File(downloadsDir, "vyze_diag_${System.currentTimeMillis()}.log")
        return try {
            outFile.bufferedWriter(Charsets.UTF_8).use { w ->
                w.writeLine("# Vyze diagnostic log — device dump of own-PID logcat")
                w.writeLine("# app: ${context.packageName}  pid: ${android.os.Process.myPid()}  time: ${System.currentTimeMillis()}")
                w.writeLine("# kept ${kept.size} of ${allLines.size} lines")
                for (line in kept) w.writeLine(line)
            }
            android.util.Log.i(TAG, "Diag export: ${kept.size} lines → ${outFile.absolutePath}")
            outFile
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Diag export write failed: ${t.message}")
            null
        }
    }

    private fun java.io.BufferedWriter.writeLine(s: String) = run {
        write(s); write("\n"); Unit
    }
}

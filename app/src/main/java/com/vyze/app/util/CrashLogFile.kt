package com.vyze.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Crash log file that writes timestamped entries to internal storage.
 *
 * Unlike Room-based error logging, this works even for native crashes (SIGSEGV/SIGABRT)
 * because the file is flushed synchronously after each write.
 *
 * PRIVACY (2026-11-09, whole-app audit P1 #1): user content (OCR takes,
 * transcripts, prompts, answers) must never reach a persistent log on a
 * release build. Content-bearing lines go through [logContent], which is a
 * no-op unless the app is debuggable; [log] carries metadata only
 * (lengths, counts, lane names, timings). [exportToDownloads] is likewise
 * debug-gated — release builds never copy the log anywhere public.
 */
object CrashLogFile {

    private const val FILE_NAME = "vyze_crash.log"
    private const val EXPORT_FILE_NAME = "vyze_crash.log"
    private const val MAX_ENTRIES = 200

    /**
     * Crash-log file cap (whole-app audit P3 #61): MAX_ENTRIES bounded
     * the in-memory ring only — the FILE grew unbounded until the next
     * init truncated it. Past this size [appendToFile] rotates to its
     * tail instead of growing forever. Internal so tests pin the real
     * value instead of a copy.
     */
    internal const val MAX_LOG_FILE_BYTES = 256 * 1024L

    private var logFile: File? = null
    private val entries = mutableListOf<String>()
    private val lock = Any()

    /**
     * Single-thread file writer (P3 #93): log()/logContent() enqueue
     * here so no caller thread ever touches disk; the single thread
     * preserves line order, and logcat (still synchronous per call)
     * stays the real-time source of truth. logError() and the
     * crash-path flush() stay synchronous — see below.
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vyze-crashlog").apply { isDaemon = true }
    }

    /**
     * True when the app is debuggable (set from [init]). Content logging
     * and the Downloads export are allowed only then — the default false
     * is release-safe (before init, nothing content-bearing is written).
     */
    @Volatile
    private var debuggableApp: Boolean = false

    /**
     * Test seam: when non-null, [isDebugBuild] returns this instead of
     * the app flag. Production never sets it.
     */
    @Volatile
    internal var debugBuildOverride: Boolean? = null

    /**
     * Whole-app audit P2 #20 (2026-11-09): timestamps used to be
     * formatted through one shared SimpleDateFormat OUTSIDE the lock —
     * it carries a mutable Calendar, so concurrent callers read each
     * other's values. DateTimeFormatter is immutable and thread-safe
     * (minSdk 26, no desugaring needed); the HH:mm:ss.SSS shape is
     * unchanged.
     */
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun formatNow(): String = timeFormatter.format(Instant.now())

    /**
     * Test-only: format [date] through the SAME shared formatter the log
     * lines use, so a concurrency test can pin per-thread values.
     */
    internal fun debugFormatTimestampForTests(date: Date): String =
        timeFormatter.format(date.toInstant())

    fun init(context: Context) {
        debuggableApp =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        logFile = File(context.filesDir, FILE_NAME)
        synchronized(lock) {
            entries.clear()
            logFile?.writeText("--- Crash log started at ${formatNow()} ---\n")
        }
        Log.d("CrashLogFile", "Initialized: ${logFile?.absolutePath}")
    }

    /**
     * Debug-build check behind [logContent] and [exportToDownloads].
     * Internal so JVM tests can pin the override/default behavior.
     */
    internal fun isDebugBuild(): Boolean = debugBuildOverride ?: debuggableApp

    /**
     * Log a line that carries USER CONTENT (OCR text, transcripts,
     * prompts, answers, scan takes). Written only on debuggable builds;
     * silently dropped on release. Debug behavior is identical to [log]
     * (file + logcat). Call sites that mix useful metadata with content
     * keep a content-free [log] line and move the content here.
     */
    fun logContent(tag: String, message: String) {
        if (!isDebugBuild()) return
        log(tag, message)
    }

    fun log(tag: String, message: String) {
        val timestamp = formatNow()
        val entry = "$timestamp | $tag | $message"
        val file: File?
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            // Captured under the lock: the writer task must hit the file
            // that was current at enqueue time, never a later swap.
            file = logFile
        }
        if (file != null) {
            ioExecutor.execute {
                synchronized(lock) {
                    appendToFile(file, entry)
                }
            }
        }
        Log.d(tag, message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = formatNow()
        val trace = throwable?.let {
            "\n  ${it.javaClass.simpleName}: ${it.message}\n  ${it.stackTrace.take(8).joinToString("\n  at ") { s -> s.toString() }}"
        } ?: ""
        val entry = "$timestamp | $tag | ERROR: $message$trace"
        // Synchronous by design (#93): crash evidence must hit disk on
        // the caller — the error path is cold, so the I/O cost is fine.
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            appendToFile(logFile, entry)
        }
        Log.e(tag, message, throwable)
    }

    /**
     * Drain barrier (#93): every line queued before this call is on
     * disk when it returns (bounded wait — best-effort, never throws).
     * The crash paths call it after logError; never call it from the
     * writer thread itself.
     */
    fun flush() {
        val file = synchronized(lock) { logFile }
        try {
            ioExecutor.submit {
                synchronized(lock) {
                    // Routed through appendToFile so the FLUSH line obeys
                    // the same rotation cap (P3 #61) — it bypassed it before.
                    appendToFile(file, "--- FLUSH at ${formatNow()} ---")
                }
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // Best-effort: a stuck writer must never break the caller.
        }
    }

    /**
     * Export the crash log to the public Downloads directory so it can be pulled
     * via `adb pull /sdcard/Download/vyze_crash.log`.
     *
     * DEBUG BUILDS ONLY (2026-11-09, audit P1 #1): the log is world-readable
     * in Downloads, so release builds return without copying. Debug behavior
     * is unchanged. Call this after each successful inference so the log is
     * always fresh. If storage permission is not granted, this silently
     * does nothing.
     */
    fun exportToDownloads(context: Context) {
        if (!isDebugBuild()) return
        // Freshness (#93): drain queued lines before copying, so the
        // export holds the tail instead of missing it.
        flush()
        try {
            val source = File(context.filesDir, FILE_NAME)
            if (!source.exists()) return

            // Check storage access
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
            }
            if (!hasAccess) return

            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ) ?: return

            val target = File(downloadsDir, EXPORT_FILE_NAME)
            source.copyTo(target, overwrite = true)
            Log.d("CrashLogFile", "Exported to ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Throwable) {
            Log.w("CrashLogFile", "Export failed: ${e.message}")
        }
    }

    fun read(context: Context): String {
        return try {
            File(context.filesDir, FILE_NAME).readText()
        } catch (_: Exception) {
            "No crash log found"
        }
    }

    /** Test-only: in-memory ring size (the file is never touched on JVM). */
    internal fun debugEntryCountForTests(): Int = synchronized(lock) { entries.size }

    /** Test-only: clear the in-memory ring. */
    internal fun debugClearEntriesForTests() = synchronized(lock) { entries.clear() }

    /** Test-only: snapshot the in-memory ring. */
    internal fun debugEntriesSnapshotForTests(): List<String> =
        synchronized(lock) { entries.toList() }

    /** Test-only: point the file writer at a temp file (null restores). */
    internal fun debugSetLogFileForTests(file: File?) {
        synchronized(lock) { logFile = file }
    }

    private fun appendToFile(file: File?, entry: String) {
        file?.let {
            try {
                // P3 #61: rotate past the cap — rewrite the file keeping
                // its tail, so the freshest crash evidence always survives
                // while the file stays bounded. The pre-rotation read is
                // bounded too (cap + one entry), never an #43-style whole
                // unbounded read. Callers hold [lock].
                if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                    val tail = file.readLines().takeLast(MAX_ENTRIES)
                    file.writeText(
                        (tail + "--- ROTATED at ${formatNow()} " +
                            "(kept last $MAX_ENTRIES lines) ---")
                            .joinToString("\n") + "\n"
                    )
                }
                FileWriter(file, true).use { writer ->
                    writer.write("$entry\n")
                    writer.flush()
                }
            } catch (_: Exception) {
            }
        }
    }
}

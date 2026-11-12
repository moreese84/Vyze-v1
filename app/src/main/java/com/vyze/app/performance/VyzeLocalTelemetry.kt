package com.vyze.app.performance

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.LocaleListCompat
import com.vyze.app.util.CrashLogFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/**
 * Local, privacy-safe telemetry for Vyze performance behavior.
 *
 * Captures only what is needed to tune latency, backend selection, failure
 * modes, and task type — on-device, never network-uploaded, and never image
 * content, audio content, or user text beyond a short localized task-type tag.
 */
object VyzeLocalTelemetry {

    private const val TAG = "VyzeLocalTelemetry"

    private const val PREFS_NAME = "vyze_telemetry"
    private const val KEY_LAST_INGESTION_MS = "last_ingestion_ms"
    private const val KEY_INGESTION_COUNT = "ingestion_count"

    /** Maximum telemetry rows kept in memory before oldest are dropped. */
    const val MAX_IN_MEMORY_ROWS = 200

    /** Max telemetry rows flushed per batch (remainder waits for the next flush). */
    const val ROWS_PER_FILE = 300

    /** Serializes the header check-then-act + append (audit P3 #63). */
    private val fileLock = Any()

    /** Telemetry files older than this are swept on write (audit P3 #63). */
    private const val TELEMETRY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * App-private telemetry directory (audit P1 #6 scope-down,
     * 2026-11-09): CSVs used to land in public
     * Downloads/Vyze_Telemetry. Pure java.io — JVM-tested in
     * VyzeLocalTelemetryTest.
     */
    fun telemetryDir(filesDir: File): File {
        val dir = File(filesDir, "telemetry")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Directory for local telemetry files (app-private files/telemetry/). */
    fun telemetryDirectory(context: Context): File = telemetryDir(context.filesDir)

    /** In-memory event buffer. */
    private val eventBuffer = ConcurrentLinkedQueue<TelemetryEvent>()

    /** Hand off any in-memory events to disk batch. Called at safe points. */
    fun flushIfNeeded(context: Context) {
        if (eventBuffer.isEmpty()) return
        try {
            writeBufferedEventsToFile(context)
        } catch (e: Throwable) {
            CrashLogFile.log(TAG, "flushIfNeeded failed: ${e.message}")
        }
    }

    /** Record one telemetry event. This is the primary public API. */
    fun record(
        context: Context,
        taskType: String,
        backend: String,
        latencyMs: Long,
        success: Boolean,
        failureKind: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val event = TelemetryEvent(
            tsMs = now,
            taskType = sanitizeTag(taskType),
            backend = sanitizeTag(backend),
            latencyMs = clampLatency(latencyMs),
            success = success,
            failureKind = sanitizeTag(failureKind),
            deviceModel = deviceModel(),
            socModel = socModel(),
            osApi = android.os.Build.VERSION.SDK_INT,
            totalRamMB = totalRamMB(context),
            lowRamFlag = isLowRamDevice(context),
            localeLanguage = preferredLocaleLanguage(),
        )
        eventBuffer.offer(event)
        if (eventBuffer.size > MAX_IN_MEMORY_ROWS) {
            val discarded = eventBuffer.poll()
            if (discarded != null) {
                CrashLogFile.log(TAG, "telemetry ring dropped oldest event: ${discarded.taskType}")
            }
        }
    }

    /** Persist current buffer to a dated file. */
    fun writeBufferedEventsToFile(context: Context) {
        if (eventBuffer.isEmpty()) return
        val dir = telemetryDirectory(context)
        val file = newestTelemetryFile(dir)
        val rows = min(eventBuffer.size, ROWS_PER_FILE)
        val batch = mutableListOf<TelemetryEvent>()
        repeat(rows) {
            val e = eventBuffer.poll() ?: return@repeat
            batch.add(e)
        }
        if (batch.isEmpty()) return
        try {
            companionSweepOldFiles(dir, System.currentTimeMillis())
            companionAppendCsvLines(file, batch.map { it.toCsvLine() })
            bumpIngestionCount(context)
        } catch (e: Throwable) {
            CrashLogFile.log(TAG, "writeBufferedEventsToFile failed: ${e.message}")
        }
    }

    // NOTE (audit P2 #43, 2026-11-09): the diagnostics reader
    // (readRecentRows) was DELETED, not fixed — it had zero callers in
    // every source set and loaded whole daily files via readLines()
    // (OOM risk on low-RAM Tier-3 phones). If a diagnostics reader is
    // ever needed again, it must tail-read (bounded bytes from the end),
    // never readLines(). Persisted rows stay write-only until then.

    /** Clear persisted telemetry for this app. */
    fun clear(context: Context) {
        val dir = telemetryDirectory(context)
        dir.listFiles()?.forEach { it.delete() }
        eventBuffer.clear()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_INGESTION_MS, 0L)
            .putInt(KEY_INGESTION_COUNT, 0)
            .apply()
    }

    // ── internal helpers ───────────────────────────────────────────────────

    private fun sanitizeTag(value: String?): String {
        if (value == null) return ""
        return value.trim()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            .take(64)
    }

    /**
     * CSV cell sanitizer (audit P3 #63): deviceModel/socModel went into
     * rows RAW — a comma/newline in a Build string shifts columns (or
     * rows). Unlike [sanitizeTag] this KEEPS spaces (readable corridor
     * analysis: "samsung SM-S918B" survives) and only neutralizes what
     * breaks the format: commas become semicolons, quotes and newlines
     * go. Pure java.io-free — JVM-tested in VyzeLocalTelemetryTest.
     */
    internal fun companionCsvCell(value: String?): String {
        if (value == null) return ""
        return value.trim().take(64)
            .replace(',', ';')
            .filter { it != '"' && it != '\r' && it != '\n' }
    }

    /**
     * True when [file] needs the HEADER row first (audit P3 #63): the
     * HEADER const existed but was never written, so no CSV ever had a
     * header. Only missing/empty files take one — pre-existing headerless
     * files keep appending data (no mid-file header, no migration).
     */
    internal fun companionNeedsHeader(file: File): Boolean =
        !file.exists() || file.length() == 0L

    /**
     * Delete telemetry_*.csv files older than the retention window
     * (audit P3 #63 — daily files used to accumulate forever). Total:
     * never throws, ignores foreign files and per-file failures.
     * Returns the swept count. Pure java.io — JVM-tested.
     */
    internal fun companionSweepOldFiles(dir: File, nowMs: Long): Int {
        val files = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return 0
        var swept = 0
        for (f in files) {
            try {
                if (f.isFile && f.name.startsWith("telemetry_") && f.name.endsWith(".csv") &&
                    nowMs - f.lastModified() > TELEMETRY_RETENTION_MS
                ) {
                    if (f.delete()) swept++
                }
            } catch (_: Throwable) {
            }
        }
        return swept
    }

    /**
     * Append [lines] to [file], writing the HEADER row first when the
     * file is new (audit P3 #63). The check-then-act + append run under
     * [fileLock], so concurrent flushes can neither duplicate the header
     * nor tear lines. Pure java.io — JVM-tested (hammer).
     */
    internal fun companionAppendCsvLines(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(fileLock) {
            if (companionNeedsHeader(file)) {
                file.writeText(TelemetryEvent.HEADER + "\n")
            }
            file.appendText(lines.joinToString("\n") + "\n")
        }
    }

    private fun clampLatency(ms: Long): Long {
        if (ms < 0L) return 0L
        return min(ms, 999_999L)
    }

    private fun deviceModel(): String {
        return try {
            companionCsvCell("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        } catch (_: Throwable) {
            "unknown"
        }
    }

    /**
     * Chipset identifier for the one-build-per-vendor analysis (checklist
     * item 6): distinguishes MT6899 from MT6768, Adreno from Mali generations
     * that retail model names hide. Build.SOC_MODEL exists since API 31;
     * older devices fall back to Build.HARDWARE (e.g. "mt6899"), which is
     * the same discriminator set the tier logic already uses.
     */
    private fun socModel(): String {
        return try {
            companionCsvCell(android.os.Build.SOC_MODEL)
        } catch (_: Throwable) {
            try {
                companionCsvCell(android.os.Build.HARDWARE)
            } catch (_: Throwable) {
                "unknown"
            }
        }
    }

    private fun totalRamMB(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024L * 1024L)
        } catch (_: Throwable) {
            0L
        }
    }

    private fun isLowRamDevice(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun preferredLocaleLanguage(): String {
        val locale = LocaleListCompat.getDefault()
        if (locale != null && locale.isEmpty.not()) {
            val lang = locale.get(0)?.language
            if (!lang.isNullOrBlank()) return lang.take(8)
        }
        return Locale.getDefault().language.take(8).ifBlank { "en" }
    }

    private fun newestTelemetryFile(dir: File): File {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(dir, "telemetry_$date.csv")
    }

    private fun bumpIngestionCount(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_INGESTION_COUNT, 0)
        prefs.edit().putInt(KEY_INGESTION_COUNT, current + 1)
            .putLong(KEY_LAST_INGESTION_MS, System.currentTimeMillis())
            .apply()
    }

    // ── CSV event model ────────────────────────────────────────────────────

    /** One telemetry row. CSV-safe fields only. */
    data class TelemetryEvent(
        val tsMs: Long,
        val taskType: String,
        val backend: String,
        val latencyMs: Long,
        val success: Boolean,
        val failureKind: String,
        val deviceModel: String,
        val socModel: String,
        val osApi: Int,
        val totalRamMB: Long,
        val lowRamFlag: Boolean,
        val localeLanguage: String,
    ) {
        companion object {
            // Schema v2 (2026-10-17): adds socModel after deviceModel so
            // cross-vendor corridor rows are chipset-comparable. Older same-day
            // CSV files lack the column; rows are opaque diagnostics
            // strings, so no migration needed.
            val HEADER = "tsMs,taskType,backend,latencyMs,success,failureKind,deviceModel,socModel,osApi,totalRamMB,lowRamFlag,localeLanguage"
        }

        fun toCsvLine(): String {
            return listOf(
                tsMs,
                taskType,
                backend,
                latencyMs,
                if (success) 1 else 0,
                failureKind,
                deviceModel,
                socModel,
                osApi.toString(),
                totalRamMB.toString(),
                if (lowRamFlag) 1 else 0,
                localeLanguage,
            ).joinToString(",")
        }
    }
}

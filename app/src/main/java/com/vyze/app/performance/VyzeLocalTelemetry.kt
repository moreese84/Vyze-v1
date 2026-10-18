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

    /** Max telemetry rows written per file before rotating. */
    const val ROWS_PER_FILE = 300

    /** Directory for local telemetry files (Downloads/Vyze_Telemetry/). */
    fun telemetryDirectory(context: Context): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "Vyze_Telemetry"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

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
            val lines = batch.map { it.toCsvLine() }
            File(dir, file.name).appendText(lines.joinToString("\n") + "\n")
            bumpIngestionCount(context)
        } catch (e: Throwable) {
            CrashLogFile.log(TAG, "writeBufferedEventsToFile failed: ${e.message}")
        }
    }

    /** Read the last N persisted rows for diagnostics. */
    fun readRecentRows(context: Context, maxRows: Int): List<String> {
        val dir = telemetryDirectory(context)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val out = mutableListOf<String>()
        outer@ for (f in files) {
            if (out.size >= maxRows) break
            try {
                f.readLines().reversed().forEach { line ->
                    if (out.size >= maxRows) break@outer
                    out.add(line)
                }
            } catch (_: Throwable) {}
        }
        return out
    }

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

    private fun clampLatency(ms: Long): Long {
        if (ms < 0L) return 0L
        return min(ms, 999_999L)
    }

    private fun deviceModel(): String {
        return try {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(64)
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
            android.os.Build.SOC_MODEL.trim().take(64)
        } catch (_: Throwable) {
            try {
                android.os.Build.HARDWARE.trim().take(64)
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
            // CSV files lack the column; readRecentRows consumers treat rows
            // as opaque diagnostics strings, so no migration needed.
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

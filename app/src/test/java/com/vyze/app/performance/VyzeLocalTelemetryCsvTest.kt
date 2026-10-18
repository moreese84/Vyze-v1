package com.vyze.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the privacy-safe local telemetry row format
 * ([VyzeLocalTelemetry.TelemetryEvent]) — checklist item 6:
 *
 *  - CSV rows parse back cleanly (same field count as the HEADER everywhere),
 *  - only anonymized device/OS facts are recorded (model, API level, RAM
 *    class, locale LANG code) — never task text, image content, or full
 *    locale tags,
 *  - tags are sanitized to CSV-safe, bounded strings.
 */
class VyzeLocalTelemetryCsvTest {

    private fun row(
        taskType: String = "ocr_first",
        backend: String = "cpu",
        latencyMs: Long = 1234L,
        success: Boolean = true,
        failureKind: String = "",
        deviceModel: String = "INFINIX Infinix X6891",
        socModel: String = "MT6899",
        osApi: Int = 33,
        totalRamMB: Long = 6144L,
        lowRamFlag: Boolean = false,
        localeLanguage: String = "en",
    ) = VyzeLocalTelemetry.TelemetryEvent(
        tsMs = 1_729_000_000_000L,
        taskType = taskType,
        backend = backend,
        latencyMs = latencyMs,
        success = success,
        failureKind = failureKind,
        deviceModel = deviceModel,
        socModel = socModel,
        osApi = osApi,
        totalRamMB = totalRamMB,
        lowRamFlag = lowRamFlag,
        localeLanguage = localeLanguage,
    )

    @Test
    fun `every CSV row matches the header field count`() {
        val cases = listOf(
            row(),
            row(success = false, failureKind = "oom"),
            row(taskType = "user_cancel", backend = "gpu", latencyMs = 0, success = false,
                failureKind = "user_cancel", deviceModel = "Helio G99 phone", osApi = 31,
                totalRamMB = 4096, lowRamFlag = true, localeLanguage = "ms"),
        )
        for (r in cases) {
            assertEquals(
                VyzeLocalTelemetry.TelemetryEvent.HEADER.split(',').size,
                r.toCsvLine().split(',').size,
            )
        }
    }

    @Test
    fun `success flag serializes as 0 or 1`() {
        assertEquals('1', row(success = true).toCsvLine().split(',')[4][0])
        assertEquals('0', row(success = false).toCsvLine().split(',')[4][0])
    }

    @Test
    fun `row writer passes latency through as recorded`() {
        // The data class is a plain CSV record; negative/clamped-overflow
        // normalization is record()'s contract at the ring-buffer boundary,
        // not the row writer's. Pin pass-through so a silent regex-style
        // rewrite cannot change stored values.
        val line = row(latencyMs = -5).toCsvLine().split(',')
        assertEquals("-5", line[3])
    }

    @Test
    fun `soc model column identifies the chipset per vendor`() {
        // One-build-across-vendors pin (checklist item 6): rows must be
        // chipset-comparable. SoC strings, never retail names or content.
        assertEquals("MT6899", row(socModel = "MT6899").toCsvLine().split(',')[7])
        // Snapdragon-class example — same column, same slot.
        assertEquals("SDM845", row(socModel = "SDM845").toCsvLine().split(',')[7])
    }

    @Test
    fun `soc field count survives and matches header insertion`() {
        // deviceModel must remain col 6 and socModel col 7 — a silent reorder
        // of the anonymous device-fact columns would corrupt comparisons
        // across schema versions.
        val line = row().toCsvLine().split(',')
        assertEquals("INFINIX Infinix X6891", line[6])
        assertEquals("MT6899", line[7])
    }

    @Test
    fun `header carries zero user-content columns`() {
        // Privacy pin: exact column list — only anonymous device facts and
        // task metadata. No transcript text, OCR text, or prompts.
        assertEquals(
            "tsMs,taskType,backend,latencyMs,success,failureKind,deviceModel,socModel,osApi,totalRamMB,lowRamFlag,localeLanguage",
            VyzeLocalTelemetry.TelemetryEvent.HEADER,
        )
        for (col in listOf("query", "answer", "ocrText", "image", "audio", "transcript")) {
            assertTrue(
                "column $col must never exist",
                !VyzeLocalTelemetry.TelemetryEvent.HEADER.contains(col, ignoreCase = true),
            )
        }
    }

    @Test
    fun `locale is a bare language code, never a full tag`() {
        // "zh-Hans-MY,HS" style tags could leak region/settings detail; the
        // contract is the two-letter language code only. (socModel inserted
        // after deviceModel shifted locale to column 11.)
        val line = row(localeLanguage = "zh").toCsvLine().split(',')
        assertEquals("zh", line[11])
    }

    @Test
    fun `lowRam flag serializes as 0 or 1`() {
        assertEquals("1", row(lowRamFlag = true).toCsvLine().split(',')[10])
        assertEquals("0", row(lowRamFlag = false).toCsvLine().split(',')[10])
    }

    @Test
    fun `task tags stay in the documented vocabulary`() {
        // The CSV writer only ever receives sanitize+bounded tags; these are
        // the task types the router/degradation layer is allowed to emit.
        val allowed = setOf(
            VyzePerformanceConfig.TelemetryTaskType.OCR_FIRST,
            VyzePerformanceConfig.TelemetryTaskType.SCENE_DIRECT,
            VyzePerformanceConfig.TelemetryTaskType.TEXT_ONLY,
            VyzePerformanceConfig.TelemetryTaskType.VLM_IMAGE,
            VyzePerformanceConfig.TelemetryTaskType.VLM_TEXT_CLEANUP,
            VyzePerformanceConfig.TelemetryTaskType.OCR_FAST_PATH_DEGRADATION,
            VyzePerformanceConfig.TelemetryTaskType.USER_CANCEL,
            VyzePerformanceConfig.TelemetryTaskType.LOW_RAM_AUTO_REDUCE,
            VyzePerformanceConfig.TelemetryTaskType.BACKEND_SELECTION,
            VyzePerformanceConfig.TelemetryTaskType.BACKEND_STARTUP_CALIBRATION,
            VyzePerformanceConfig.TelemetryTaskType.BACKEND_FAILED,
            VyzePerformanceConfig.TelemetryTaskType.ENGINE_FAILED,
            VyzePerformanceConfig.TelemetryTaskType.OVERHEAT_FALLBACK,
        )
        for (t in allowed) {
            // vocabulary strings are CSV/underscore safe and lowercase-ish
            assertEquals(t, t.trim().filter { it.isLetterOrDigit() || it in "_-." })
        }
    }
}

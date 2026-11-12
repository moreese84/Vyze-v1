package com.vyze.app.core

import com.vyze.app.performance.VyzePerformanceConfig.TelemetryFailureKind
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the telemetry failure classifier (audit #64).
 *
 * Engine onError strings used to collapse to a single UNKNOWN bucket.
 * The classifier maps device-evidenced markers ("m0 was canceled",
 * "Inference timed out", OOM allocation failures, thermal holds) to the
 * production [TelemetryFailureKind] buckets. All expectations reference
 * the production constants — no hardcoded copies (audit #98).
 */
class TelemetryFailureClassifierTest {

    private fun classify(error: String?): String =
        VyzeCoreController.companionClassifyTelemetryFailure(error)

    // ── Fallback ─────────────────────────────────────────────────────

    @Test
    fun `null error maps to UNKNOWN`() {
        assertEquals(TelemetryFailureKind.UNKNOWN, classify(null))
    }

    @Test
    fun `blank error maps to UNKNOWN`() {
        assertEquals(TelemetryFailureKind.UNKNOWN, classify("   "))
    }

    @Test
    fun `unrecognized message maps to UNKNOWN`() {
        assertEquals(TelemetryFailureKind.UNKNOWN, classify("AI engine unavailable"))
    }

    @Test
    fun `null exception message wrapper maps to UNKNOWN`() {
        assertEquals(TelemetryFailureKind.UNKNOWN, classify("Inference error: null"))
    }

    // ── User cancel ──────────────────────────────────────────────────

    @Test
    fun `m0 was canceled maps to USER_CANCEL`() {
        assertEquals(TelemetryFailureKind.USER_CANCEL, classify("m0 was canceled"))
    }

    @Test
    fun `coroutine cancellation maps to USER_CANCEL`() {
        assertEquals(
            TelemetryFailureKind.USER_CANCEL,
            classify("StandaloneCoroutine was cancelled")
        )
    }

    @Test
    fun `external interrupt maps to USER_CANCEL`() {
        assertEquals(
            TelemetryFailureKind.USER_CANCEL,
            classify("Inference interrupted externally")
        )
    }

    @Test
    fun `aborted job maps to USER_CANCEL`() {
        assertEquals(
            TelemetryFailureKind.USER_CANCEL,
            classify("Inference error: Job aborted")
        )
    }

    // ── Timeout ──────────────────────────────────────────────────────

    @Test
    fun `inference timed out maps to TIMEOUT`() {
        assertEquals(TelemetryFailureKind.TIMEOUT, classify("Inference timed out"))
    }

    @Test
    fun `budget exceeded maps to TIMEOUT`() {
        assertEquals(
            TelemetryFailureKind.TIMEOUT,
            classify("GPU build exceeded 30s budget — treating as failure")
        )
    }

    @Test
    fun `timeout is case-insensitive`() {
        assertEquals(TelemetryFailureKind.TIMEOUT, classify("INFERENCE TIMED OUT"))
    }

    // ── Memory ───────────────────────────────────────────────────────

    @Test
    fun `allocation failure maps to OOM`() {
        assertEquals(
            TelemetryFailureKind.OOM,
            classify("OutOfMemoryError: Failed to allocate a 64 byte allocation")
        )
    }

    @Test
    fun `out of memory maps to OOM`() {
        assertEquals(
            TelemetryFailureKind.OOM,
            classify("Inference error: out of memory")
        )
    }

    @Test
    fun `low memory trim maps to LOW_RAM`() {
        assertEquals(
            TelemetryFailureKind.LOW_RAM,
            classify("low memory, trimming caches")
        )
    }

    // ── Thermal / backend init ───────────────────────────────────────

    @Test
    fun `too hot maps to THERMAL_BLOCK`() {
        assertEquals(TelemetryFailureKind.THERMAL_BLOCK, classify("Device is too hot"))
    }

    @Test
    fun `opencl failure maps to GPU_INIT_FAILED`() {
        assertEquals(
            TelemetryFailureKind.GPU_INIT_FAILED,
            classify("OpenCL kernel compile failed")
        )
    }

    @Test
    fun `npu failure maps to NPU_INIT_FAILED`() {
        assertEquals(
            TelemetryFailureKind.NPU_INIT_FAILED,
            classify("NPU init failed")
        )
    }

    @Test
    fun `warmup failure maps to WARMUP_FAILED`() {
        assertEquals(TelemetryFailureKind.WARMUP_FAILED, classify("warmup failed"))
    }

    // ── Priority ─────────────────────────────────────────────────────

    @Test
    fun `OOM beats timeout and gpu markers`() {
        assertEquals(
            TelemetryFailureKind.OOM,
            classify("gpu timeout: outofmemory during build")
        )
    }

    @Test
    fun `timeout beats cancel markers`() {
        assertEquals(
            TelemetryFailureKind.TIMEOUT,
            classify("cancelled by timeout watchdog")
        )
    }

    @Test
    fun `thermal beats cancel markers`() {
        assertEquals(
            TelemetryFailureKind.THERMAL_BLOCK,
            classify("cancelled: thermal throttling active")
        )
    }
}

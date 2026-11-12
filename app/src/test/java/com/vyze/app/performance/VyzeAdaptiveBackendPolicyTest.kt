package com.vyze.app.performance

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the adaptive backend selection policy
 * ([VyzeAdaptiveBackend]) — checklist item 3:
 *
 *  - CPU is ALWAYS present in the chain and is the terminal attempt,
 *  - GPU is optional (only when the capability check says it is worth it),
 *  - NPU is experimental-only (never the sole non-CPU path, requires opt-in),
 *  - a persisted hardware preference goes STALE after 24h (one APK must
 *    re-adapt, never latch a lucky GPU run forever),
 *  - calibration never picks an unhealthy result; no winner means CPU.
 */
class VyzeAdaptiveBackendPolicyTest {

    private val DAY_MS = 24 * 60 * 60 * 1000L

    /** Pure name-level chain policy (no litertlm types — they are Java-21
     *  class files and cannot load on the JVM test runtime). */
    private fun chain(pref: String, gpu: Boolean, npu: Boolean): List<String> {
        return VyzeAdaptiveBackend.backendChainNames(
            osPreferredBackend = pref, deviceCanGpu = gpu, npuExperimental = npu,
        )
    }

    // ── backendChainNames: ordering invariants (pure policy) ─────────

    @Test
    fun `default policy is CPU-only`() {
        assertEquals(listOf("CPU"), chain("CPU", gpu = false, npu = false))
    }

    @Test
    fun `unknown stored preference also lands on CPU`() {
        // No opt-in means no exotic probe for a meaningless preference.
        assertEquals(listOf("CPU"), chain("WASM", gpu = true, npu = false))
    }

    @Test
    fun `GPU preference with capable device tries GPU first CPU last`() {
        assertEquals(listOf("GPU", "CPU"), chain("GPU", gpu = true, npu = false))
    }

    @Test
    fun `GPU preference without GPU capability degrades to CPU directly`() {
        assertEquals(listOf("CPU"), chain("GPU", gpu = false, npu = false))
    }

    @Test
    fun `experimental NPU is appended only when opted in`() {
        assertFalse(chain("GPU", gpu = true, npu = false).contains("NPU"))
        assertEquals(listOf("GPU", "NPU", "CPU"), chain("GPU", gpu = true, npu = true))
    }

    @Test
    fun `NPU is never the sole non-CPU path`() {
        // NPU preference without GPU capability and without opt-in = CPU only.
        assertEquals(listOf("CPU"), chain("NPU", gpu = false, npu = false))
        // Even opted-in-but-incapable hardware never gets an NPU attempt.
        assertEquals(listOf("CPU"), chain("NPU", gpu = false, npu = true))
    }

    @Test
    fun `NPU never rides without GPU in the chain`() {
        // Whole-app audit P3 #70 (2026-11-09): with CPU preference +
        // GPU-capable hardware + NPU opt-in, NPU was the SOLE non-CPU
        // attempt ([NPU, CPU]) despite the doc ban. NPU only appends
        // when GPU is already chained.
        assertEquals(listOf("CPU"), chain("CPU", gpu = true, npu = true))
        assertEquals(listOf("CPU"), chain("NPU", gpu = true, npu = true))
    }

    // ── preference staleness: one build re-adapts across devices ─────

    @Test
    fun `persisted GPU preference is honored while fresh`() {
        val state = VyzeAdaptiveBackend.PersistentState(
            preferredBackend = "GPU", lastValidatedMs = 1000L,
        )
        assertTrue(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L + DAY_MS - 1))
    }

    @Test
    fun `persisted GPU preference expires after 24 hours`() {
        val state = VyzeAdaptiveBackend.PersistentState(
            preferredBackend = "GPU", lastValidatedMs = 1000L,
        )
        assertFalse(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L + DAY_MS))
    }

    @Test
    fun `sub-threshold overtemps do not veto the hardware preference`() {
        // Whole-app audit P3 #68 (2026-11-09): the veto fired at
        // overtempCount > 0 while the CPU fallback needs
        // OVERTEMP_FALLBACK_THRESHOLD — one transient overheat vetoed
        // hardware. Veto and fallback now share the threshold. (The veto
        // window still keys off validation age — no dedicated overheat
        // timestamp is persisted; the pipeline is unwired, so minting
        // one is unjustified. See the KDoc.)
        val state = VyzeAdaptiveBackend.PersistentState(
            preferredBackend = "GPU",
            lastValidatedMs = 1000L,
            overtempCount = VyzeAdaptiveBackend.OVERTEMP_FALLBACK_THRESHOLD - 1,
        )
        assertTrue(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L + 60_000L))
    }

    @Test
    fun `recent overtemp invalidates the hardware preference immediately`() {
        val state = VyzeAdaptiveBackend.PersistentState(
            preferredBackend = "GPU",
            lastValidatedMs = 1000L,
            overtempCount = VyzeAdaptiveBackend.OVERTEMP_FALLBACK_THRESHOLD,
        )
        assertFalse(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L + 60_000L))
    }

    @Test
    fun `last-validated zero means never validated — no hardware preference`() {
        val state = VyzeAdaptiveBackend.PersistentState(preferredBackend = "GPU")
        assertFalse(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L))
    }

    @Test
    fun `CPU preference never counts as hardware-preferred`() {
        val state = VyzeAdaptiveBackend.PersistentState(
            preferredBackend = "CPU", lastValidatedMs = 1000L,
        )
        assertFalse(VyzeAdaptiveBackend.prefersHardwareBackend(state, nowMs = 1000L + 60_000L))
    }

    // ── chooseBestBackend: no unhealthy winner, CPU is the floor ─────

    @Test
    fun `fastest healthy backend wins calibration`() {
        val winner = VyzeAdaptiveBackend.chooseBestBackend(
            listOf(
                VyzeAdaptiveBackend.CalibrationResult(backend = "GPU", succeeded = true, latencyMs = 4000, healthy = true),
                VyzeAdaptiveBackend.CalibrationResult(backend = "CPU", succeeded = true, latencyMs = 8000, healthy = true),
            )
        )
        assertEquals("GPU", winner)
    }

    @Test
    fun `unhealthy or failed results are never chosen`() {
        val winner = VyzeAdaptiveBackend.chooseBestBackend(
            listOf(
                VyzeAdaptiveBackend.CalibrationResult(backend = "GPU", succeeded = true, latencyMs = 1, healthy = false),
                VyzeAdaptiveBackend.CalibrationResult(backend = "NPU", succeeded = false, latencyMs = 1, healthy = true),
            )
        )
        assertEquals("CPU", winner)
    }

    @Test
    fun `no candidate at all means CPU`() {
        assertEquals("CPU", VyzeAdaptiveBackend.chooseBestBackend(emptyList()))
    }

    // ── warm-up health gate (startup calibration verdict input) ────────

    @Test
    fun `warm-up latency inside the bar is healthy`() {
        assertTrue(VyzeAdaptiveBackend.warmupHealthy(1L))
        assertTrue(VyzeAdaptiveBackend.warmupHealthy(8_999L))
        assertTrue(VyzeAdaptiveBackend.warmupHealthy(VyzeAdaptiveBackend.GPU_WARMUP_MAX_LATENCY_MS - 1))
    }

    @Test
    fun `zero failed or too-slow warm-up is NOT healthy`() {
        // 0 = warm-up failed / engine absent — never a persisted verdict.
        assertFalse(VyzeAdaptiveBackend.warmupHealthy(0L))
        assertFalse(VyzeAdaptiveBackend.warmupHealthy(-1L))
        assertFalse(
            VyzeAdaptiveBackend.warmupHealthy(VyzeAdaptiveBackend.GPU_WARMUP_MAX_LATENCY_MS),
        )
    }

    // ── calibration: per-backend result judgment ──────────────────────

    @Test
    fun `oversized RAM delta marks a backend unhealthy even at good latency`() {
        // 400 MB delta > MAX_CALIBRATION_RAM_DELTA_MB (350) — a backend that
        // eats memory on the SAME model is a real risk on 6 GB devices even
        // when it looks fast.
        val result = runCalibrationWith(backend = "GPU", latencyMs = 2000, ramDeltaMB = 400L)
        assertFalse(result.healthy)
        assertTrue(result.succeeded)
    }

    @Test
    fun `calibration failure still returns a structured result, never throws`() {
        val result = runBlocking {
            VyzeAdaptiveBackend.calibrate(
                context = unusedContext(),
                backendName = "GPU",
                buildEngine = { throw IllegalStateException("ic") },
                dispatchCalibrationImage = { 0L },
                measureRamDelta = { 0L },
            )
        }
        assertFalse(result.succeeded)
        assertFalse(result.healthy)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `healthy fast low-delta backend is marked healthy`() {
        val result = runCalibrationWith(backend = "GPU", latencyMs = 2000, ramDeltaMB = 100L)
        assertTrue(result.healthy)
    }

    // ── vendor heuristics (one-build rule sanity) ─────────────────────

    @Test
    fun `known-fragile Mali GPUs read as CPU-only`() {
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("mali-g57"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("Mali-G76 mp12"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("adreno 740"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("mali-g615"))
    }

    @Test
    fun `ultra-budget SoCs read as CPU-only`() {
        // Unisoc Tiger / ums / Spreadtrum SC — no usable LiteRT-LM GPU path.
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("ums512"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("UMS9230"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("tiger_t606"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("sc9863a"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("unisoc"))
        // Low Helio — MT67xx below the G90-class mt6785 floor.
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6768"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6765"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6762"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6739"))
    }

    @Test
    fun `capable SoCs keep the GPU attempt path`() {
        // G90-class and up keep attempt-with-fallback; per-device
        // calibration learns their verdict instead of a static veto.
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6785"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6833"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("mt6899"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("kona"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksCpuOnly("sm8550"))
    }

    @Test
    fun `real chip ids detect NPU hardware`() {
        // Whole-app audit P3 #71 (2026-11-09): detect required literal
        // "dimensity"/"snapdragon", but real Build.HARDWARE holds chip
        // ids — NPU was never detected on real hardware. Matching is
        // Locale.US (Turkish-safe) and case-insensitive.
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("mt6899"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("MT6989"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("sm8550"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("SM8450"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("tensor"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("Tensor G3"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("dimensity 9300"))
        assertTrue(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("snapdragon 8 gen 2"))
    }

    @Test
    fun `non-NPU hardware stays negative`() {
        // Pinned gaps (documented, not oversights): board codenames
        // ("kona"), Exynos, and 7-series Snapdragon have real accelerators
        // but no stable id pattern is claimed yet — extending coverage is
        // a follow-up, not this fix.
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("mt6768"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("ums512"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("kona"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("exynos2100"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu("sm7675"))
        assertFalse(VyzeAdaptiveBackend.deviceLooksLikeItHasNpu(""))
    }

    @Test
    fun `recognized backend names`() {
        assertTrue(VyzeAdaptiveBackend.isValidBackendName("cpu"))
        assertTrue(VyzeAdaptiveBackend.isValidBackendName("GPU"))
        assertTrue(VyzeAdaptiveBackend.isValidBackendName("Npu"))
        assertFalse(VyzeAdaptiveBackend.isValidBackendName("TPU"))
        assertEquals(null, com.vyze.app.performance.VyzePerformanceConfig.BackendName.fromString("TPU"))
        assertNotEquals(null, com.vyze.app.performance.VyzePerformanceConfig.BackendName.fromString("gpu"))
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Row-shape calibration run without an Android handle: the engine and
     *  dispatcher are test doubles; the RAM probe is stateful — calibrate
     *  measures delta as after-minus-before, so the first reading is the
     *  baseline and the second is baseline+delta. */
    private fun runCalibrationWith(backend: String, latencyMs: Long, ramDeltaMB: Long): VyzeAdaptiveBackend.CalibrationResult {
        var baselineRead = false
        return runBlocking {
            VyzeAdaptiveBackend.calibrate(
                context = unusedContext(),
                backendName = backend,
                buildEngine = { FakeEngineHandle() },
                dispatchCalibrationImage = { latencyMs },
                measureRamDelta = {
                    if (baselineRead) 256L + ramDeltaMB else 256L.also { baselineRead = true }
                },
            )
        }
    }

    /**
     * null for the persistence-only Context parameter: the JVM has no real
     * Android Context, and [VyzeAdaptiveBackend.calibrate] (which now accepts
     * a nullable Context) must never dereference it — persistence happens in
     * the CALLER after the verdict.
     */
    private fun unusedContext(): android.content.Context? = null

    private class FakeEngineHandle : EngineHandle {
        var closed = false
        override fun close() {
            closed = true
        }
    }
}

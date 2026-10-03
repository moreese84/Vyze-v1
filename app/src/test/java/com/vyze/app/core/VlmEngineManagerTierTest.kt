package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the tiered-init boundary policy —
 * [VlmEngineManager.classifyTier] and [VlmEngineManager.requiredFreeRamMB].
 *
 * Device evidence (2026-10-03, second-hand report, device not in hand): a
 * low-RAM phone showed the full failure cascade — crash/OOM at startup,
 * slow init, and unusably slow answers. Root cause in the old constants:
 * a NOMINAL 4 GB phone reports ~3.6-3.9 GB kernel-adjusted totalMem, which
 * slipped past the old 3400 MB bypass floor into Tier 3 CPU-direct VLM —
 * where the ~2.59 GB model mmap dies with an uncatchable native OOM or
 * runs unusably slowly. The contract pinned here:
 *
 *  - The OS low-RAM flag ALWAYS means Tier 0 (bypass) — trust the platform
 *    before any RAM arithmetic.
 *  - The nominal-4GB band (reported < 4000 MB) → Tier 0: the app's own
 *    spoken claim "requires at least 4 GB RAM" must be honored by the tier
 *    ladder, not contradicted by it.
 *  - Probe failure (totalRamMb <= 0) stays permissive (Tier 2) — the
 *    documented "never disable VLM on a guess" intent; the tier-aware
 *    pre-flight is its safety net.
 *  - Tier 3 (CPU-direct) pre-flight demands extra free-RAM headroom.
 */
class VlmEngineManagerTierTest {

    // ── The crash band: nominal 4 GB phones must bypass ──────────────

    @Test
    fun `reported 3399 MB bypasses the VLM`() {
        assertEquals(
            DeviceTier.TIER_DISABLED,
            VlmEngineManager.classifyTier(3399L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `reported 3999 MB still bypasses — nominal 4 GB phone territory`() {
        // Kernel-adjusted totalMem of a nominal 4 GB phone: ~3.6-3.9 GB.
        // Every value below the 4000 floor must be Tier 0 so this whole
        // device class gets the spoken fallback + OCR tools, never the
        // CPU-direct VLM attempt that crashes them.
        assertEquals(
            DeviceTier.TIER_DISABLED,
            VlmEngineManager.classifyTier(3999L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `reported 4000 MB enters Tier 3 CPU-direct`() {
        // Nominal 5 GB phones report ~4.5-4.8 GB — above the floor. The
        // 4000-4499 MB band is the ambiguity zone; it errs toward attempting
        // CPU (the tier-aware pre-flight rejects it gracefully if free RAM
        // is actually short).
        assertEquals(
            DeviceTier.TIER_3,
            VlmEngineManager.classifyTier(4000L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `reported 5499 MB stays Tier 3`() {
        assertEquals(
            DeviceTier.TIER_3,
            VlmEngineManager.classifyTier(5499L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `reported 5500 MB enters Tier 2`() {
        assertEquals(
            DeviceTier.TIER_2,
            VlmEngineManager.classifyTier(5500L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `flagship GPU lifts squeezed RAM into Tier 2`() {
        // High-end GPU + modest RAM: certified OpenCL/Vulkan drivers are
        // trusted over raw capacity (documented intent, unchanged).
        assertEquals(
            DeviceTier.TIER_2,
            VlmEngineManager.classifyTier(5000L, flagship = true, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `Tier 1 boundary needs flagship GPU and 7168 MB`() {
        assertEquals(
            DeviceTier.TIER_2,
            VlmEngineManager.classifyTier(7167L, flagship = true, midGpu = false, isLowRamDevice = false)
        )
        assertEquals(
            DeviceTier.TIER_1,
            VlmEngineManager.classifyTier(7168L, flagship = true, midGpu = false, isLowRamDevice = false)
        )
    }

    // ── The OS flag outranks every RAM number ─────────────────────────

    @Test
    fun `OS low-RAM flag bypasses even with abundant reported RAM`() {
        assertEquals(
            DeviceTier.TIER_DISABLED,
            VlmEngineManager.classifyTier(8192L, flagship = true, midGpu = false, isLowRamDevice = true)
        )
        assertEquals(
            DeviceTier.TIER_DISABLED,
            VlmEngineManager.classifyTier(3000L, flagship = false, midGpu = true, isLowRamDevice = true)
        )
    }

    // ── Documented permissive intent, unchanged ───────────────────────

    @Test
    fun `RAM probe failure stays Tier 2 — never disable VLM on a guess`() {
        // totalMem probe failed (0). The pre-flight RAM check is the safety
        // net that degrades a genuinely weak device gracefully instead.
        assertEquals(
            DeviceTier.TIER_2,
            VlmEngineManager.classifyTier(0L, flagship = false, midGpu = false, isLowRamDevice = false)
        )
    }

    @Test
    fun `mid-range GPU alone does not lift the tier`() {
        // The Mali/Helio mid-tier families are exactly where GPU init fails
        // silently — a mid-GPU marker must never rescue a low-RAM reading.
        assertEquals(
            DeviceTier.TIER_3,
            VlmEngineManager.classifyTier(4000L, flagship = false, midGpu = true, isLowRamDevice = false)
        )
    }

    // ── Tier-aware pre-flight bar ─────────────────────────────────────

    @Test
    fun `standard pre-flight bar on GPU tiers is 1200 MB`() {
        assertEquals(1200L, VlmEngineManager.requiredFreeRamMB(DeviceTier.TIER_1, isLowRamDevice = false))
        assertEquals(1200L, VlmEngineManager.requiredFreeRamMB(DeviceTier.TIER_2, isLowRamDevice = false))
    }

    @Test
    fun `CPU-direct tier demands extra headroom at pre-flight`() {
        // Weights + KV-cache + activations all in system RAM: the same free
        // reading is riskier on Tier 3 than on a GPU attempt.
        assertEquals(1600L, VlmEngineManager.requiredFreeRamMB(DeviceTier.TIER_3, isLowRamDevice = false))
    }

    @Test
    fun `low-RAM flag lowers the base bar`() {
        assertEquals(800L, VlmEngineManager.requiredFreeRamMB(DeviceTier.TIER_1, isLowRamDevice = true))
        assertEquals(1200L, VlmEngineManager.requiredFreeRamMB(DeviceTier.TIER_3, isLowRamDevice = true))
    }
}

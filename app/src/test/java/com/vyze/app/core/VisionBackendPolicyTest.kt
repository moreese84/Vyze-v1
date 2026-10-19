package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [VlmEngineManager.companionMiniCpmVisionBackendName] and
 * [VlmEngineManager.isAdrenoGpu] — the tier + GPU-family vision policy.
 *
 * The contract being guarded: the GT 50 Pro (Mali, Tier 1) keeps the
 * MEASURED CPU-vision path (A/B 2026-10-18: all-GPU 7.36-7.39 s vs mixed
 * 3.7-4.3 s), Adreno Tier-1 devices get the model card's fastest vision
 * path (GPU, 0.93 s first token on Galaxy S26), and the DEBUG corridor
 * override always wins over the policy.
 */
class VisionBackendPolicyTest {

    private fun decide(
        hardware: String,
        tier: DeviceTier,
        isMiniCpm: Boolean = true,
        stepIsGpu: Boolean = true,
        debugOverride: Boolean? = null,
    ): String = VlmEngineManager.companionMiniCpmVisionBackendName(
        isMiniCpm = isMiniCpm,
        stepIsGpu = stepIsGpu,
        tier = tier,
        hardware = hardware,
        debugOverride = debugOverride,
    ).first

    // ── 1. Mali Tier 1 (the GT 50 Pro) keeps the measured CPU path ──

    @Test
    fun `gt 50 pro mali tier 1 stays on CPU vision`() {
        // mt6899 = Dimensity 8400/8400-Ultimate (Mali-G720), flagship SoC.
        assertEquals("CPU", decide("mt6899", DeviceTier.TIER_1))
        assertEquals("CPU", decide("MT6899", DeviceTier.TIER_1))
    }

    @Test
    fun `other mali tier 1 socs stay on CPU vision`() {
        assertEquals("CPU", decide("mt6989", DeviceTier.TIER_1))   // D9300 Mali
        assertEquals("CPU", decide("mt6893", DeviceTier.TIER_1))   // D1200 Mali
    }

    // ── 2. Adreno Tier 1 attempts GPU vision (model-card fastest) ──

    @Test
    fun `adreno tier 1 attempts GPU vision`() {
        assertEquals("GPU", decide("sm8550", DeviceTier.TIER_1))   // SD 8 Gen 2
        assertEquals("GPU", decide("sm8750", DeviceTier.TIER_1))   // SD 8 Elite
        assertEquals("GPU", decide("qcom,sm8350", DeviceTier.TIER_1))
    }

    // ── 3. Defaults are safe: unknown HW and sub-Tier-1 never get GPU vision ──

    @Test
    fun `unknown hardware falls back to CPU vision`() {
        assertEquals("CPU", decide("", DeviceTier.TIER_1))
        assertEquals("CPU", decide("ums9230", DeviceTier.TIER_1))
    }

    @Test
    fun `sub tier 1 never gets GPU vision even on adreno`() {
        assertEquals("CPU", decide("sm8550", DeviceTier.TIER_2))
        assertEquals("CPU", decide("sm8550", DeviceTier.TIER_3))
    }

    @Test
    fun `plan without GPU never attempts GPU vision`() {
        // The adaptive gate dropped GPU from the plan → the vision encoder
        // must not attempt a backend the plan itself rejected.
        assertEquals("CPU", decide("sm8550", DeviceTier.TIER_1, stepIsGpu = false))
    }

    // ── 4. DEBUG corridor override always wins ──

    @Test
    fun `debug force GPU wins over the Mali measurement`() {
        assertEquals("GPU", decide("mt6899", DeviceTier.TIER_1, debugOverride = true))
    }

    @Test
    fun `debug deny CPU wins over the Adreno policy`() {
        assertEquals("CPU", decide("sm8550", DeviceTier.TIER_1, debugOverride = false))
    }

    // ── 5. Non-MiniCPM brains keep riding the engine plan ──

    @Test
    fun `non minicpm brain follows the engine plan backend`() {
        assertEquals("GPU", decide("sm8550", DeviceTier.TIER_1, isMiniCpm = false))
        assertEquals("GPU", decide("mt6899", DeviceTier.TIER_1, isMiniCpm = false))
        assertEquals("CPU", decide("sm8550", DeviceTier.TIER_1, isMiniCpm = false, stepIsGpu = false))
    }

    // ── 6. isAdrenoGpu boundaries ──

    @Test
    fun `adreno detection families`() {
        assertTrue(VlmEngineManager.isAdrenoGpu("sm8550"))
        assertTrue(VlmEngineManager.isAdrenoGpu("SM8650"))
        assertTrue(VlmEngineManager.isAdrenoGpu("sdm845"))
        assertTrue(VlmEngineManager.isAdrenoGpu("msm8998"))
        assertTrue(VlmEngineManager.isAdrenoGpu("qcm6490"))
        assertTrue(VlmEngineManager.isAdrenoGpu("qcs9075"))
        assertTrue(VlmEngineManager.isAdrenoGpu("kona"))
        assertTrue(VlmEngineManager.isAdrenoGpu("taro"))
    }

    @Test
    fun `mali and mediatek families are never adreno`() {
        assertFalse(VlmEngineManager.isAdrenoGpu("mt6899"))
        assertFalse(VlmEngineManager.isAdrenoGpu("mt6789"))
        assertFalse(VlmEngineManager.isAdrenoGpu("mt6833"))
        assertFalse(VlmEngineManager.isAdrenoGpu("helio g99"))
        assertFalse(VlmEngineManager.isAdrenoGpu(""))
    }

    @Test
    fun `near misses do not match the soc regex`() {
        // 't' blocks the \b boundary; plain numbers never carry the prefix
        assertFalse(VlmEngineManager.isAdrenoGpu("tsm4620"))
        assertFalse(VlmEngineManager.isAdrenoGpu("asmed-01"))
        // bare names without digits: not SoC-style ids
        assertFalse(VlmEngineManager.isAdrenoGpu("smx"))
    }
}

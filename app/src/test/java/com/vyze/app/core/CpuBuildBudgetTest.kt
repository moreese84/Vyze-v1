package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM pins for the tiered CPU engine-build budget
 * ([VlmEngineManager.companionCpuBuildBudgetMs]).
 *
 * Device evidence (2026-11-14, mt6768): a cold 1.6 GB mmap +
 * KV-tensor alloc exceeds 60 s, so the old single budget failed EVERY
 * cold low-end init — and with no retry path the engine wedged dead.
 * Tier 1 keeps 60 s; lower tiers get 120 s.
 */
class CpuBuildBudgetTest {

    @Test
    fun `tier 1 keeps the 60s CPU budget`() {
        assertEquals(60_000L, VlmEngineManager.companionCpuBuildBudgetMs(DeviceTier.TIER_1))
    }

    @Test
    fun `tier 2 and 3 get the 180s cold-build budget`() {
        assertEquals(180_000L, VlmEngineManager.companionCpuBuildBudgetMs(DeviceTier.TIER_2))
        assertEquals(180_000L, VlmEngineManager.companionCpuBuildBudgetMs(DeviceTier.TIER_3))
    }
}

package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vyze.app.performance.VyzeAdaptiveBackend

/**
 * JVM pins for the adaptive GPU gate order
 * ([VlmEngineManager.companionAdaptiveGpuGate]).
 *
 * Branch order is the contract: the 24 h failure backoff must suppress a
 * GPU retry even when no successful calibration ever persisted. Device
 * evidence (2026-11-12, X6891): the old order returned ALLOWED for a
 * never-calibrated device with an active backoff, so a restart 70 s after
 * a recorded 90 s GPU timeout re-attempted GPU — a second 90 s burn plus
 * ~243 MB of unrestorable program-cache garbage.
 */
class AdaptiveGpuGateTest {

    private fun gate(
        cpuOnlyHardware: Boolean = false,
        overtempCount: Int = 0,
        lastValidatedMs: Long = 0L,
        preferredBackend: String = "CPU",
        backoffActive: Boolean = false
    ) = VlmEngineManager.companionAdaptiveGpuGate(
        cpuOnlyHardware, overtempCount, lastValidatedMs, preferredBackend, backoffActive
    )

    @Test
    fun `backoff suppresses retry on a never-calibrated device`() {
        val (disallowed, why) = gate(backoffActive = true)
        assertTrue(disallowed)
        assertEquals("calibrated device memory (24h GPU-failure backoff)", why)
    }

    @Test
    fun `never-calibrated device without backoff still attempts GPU`() {
        val (disallowed, _) = gate()
        assertFalse(disallowed)
    }

    @Test
    fun `known-bad hardware vetoes above everything`() {
        val (disallowed, why) = gate(
            cpuOnlyHardware = true,
            lastValidatedMs = 123L,
            preferredBackend = "GPU"
        )
        assertTrue(disallowed)
        assertEquals("known-bad GPU driver (hardware heuristic)", why)
    }

    @Test
    fun `overtemp fallback vetoes below hardware but above backoff`() {
        val (disallowed, why) = gate(
            overtempCount = VyzeAdaptiveBackend.OVERTEMP_FALLBACK_THRESHOLD,
            lastValidatedMs = 123L,
            preferredBackend = "GPU",
            backoffActive = true
        )
        assertTrue(disallowed)
        assertEquals("overtemp CPU fallback active", why)
    }

    @Test
    fun `calibrated CPU verdict disallows after backoff expires`() {
        val (disallowed, why) = gate(
            lastValidatedMs = 123L,
            preferredBackend = "CPU",
            backoffActive = false
        )
        assertTrue(disallowed)
        assertEquals("calibrated device memory (CPU verdict)", why)
    }

    @Test
    fun `calibrated GPU verdict without backoff allows`() {
        val (disallowed, _) = gate(
            lastValidatedMs = 123L,
            preferredBackend = "GPU",
            backoffActive = false
        )
        assertFalse(disallowed)
    }

    @Test
    fun `calibrated GPU verdict with backoff disallows`() {
        val (disallowed, _) = gate(
            lastValidatedMs = 123L,
            preferredBackend = "GPU",
            backoffActive = true
        )
        assertTrue(disallowed)
    }

    @Test
    fun `sub-threshold overtemp does not veto`() {
        val (disallowed, _) = gate(
            overtempCount = VyzeAdaptiveBackend.OVERTEMP_FALLBACK_THRESHOLD - 1,
            lastValidatedMs = 123L,
            preferredBackend = "GPU"
        )
        assertFalse(disallowed)
    }
}

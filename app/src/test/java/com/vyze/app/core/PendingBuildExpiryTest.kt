package com.vyze.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for parked-build expiry
 * ([VlmEngineManager.companionPendingBuildExpired]).
 *
 * A timed-out native build parks its holder for adoption by the next
 * attempt — but a failed BACKGROUND build (rotation/GPU-swap) that no
 * later attempt drains would sit resident forever (mt6768, 2026-11-14:
 * app PSS 3.3 GB with 812 MB swapped). Past its TTL the periodic
 * sweeper closes the orphan; inside the window a drain still adopts it.
 */
class PendingBuildExpiryTest {

    @Test
    fun `freshly parked holder does not expire`() {
        assertFalse(VlmEngineManager.companionPendingBuildExpired(1_000L, 2_000L, 180_000L))
    }

    @Test
    fun `holder past its TTL expires`() {
        assertTrue(VlmEngineManager.companionPendingBuildExpired(1_000L, 181_000L, 180_000L))
    }

    @Test
    fun `expiry boundary is inclusive`() {
        assertTrue(VlmEngineManager.companionPendingBuildExpired(1_000L, 181_000L, 180_000L))
        assertFalse(VlmEngineManager.companionPendingBuildExpired(1_000L, 180_999L, 180_000L))
    }

    @Test
    fun `zero park stamp never expires`() {
        assertFalse(VlmEngineManager.companionPendingBuildExpired(0L, 9_999_999L, 180_000L))
    }
}

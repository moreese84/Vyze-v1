package com.vyze.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [VyzeCoreController.companionCpuEraLeanMode] — the CPU-era lean gate.
 *
 * The contract that caused this test (2026-10-19): a text-lane query built
 * at backend=CPU rendered 5,878 chars and ate a 12.7 s prefill era. After
 * the v2 gate, whatever the lane, an active CPU backend leans the prompt;
 * the one thing a text lane keeps (its OCR/question content) travels in
 * separate parameters the lean suppression never touches.
 */
class CpuEraLeanPolicyTest {

    // ── 1. CPU era: every lane leans including text lanes (v2 contract) ──

    @Test
    fun `cpu era text lane leans`() {
        assertTrue(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = false, activeBackend = "CPU",
            )
        )
    }

    @Test
    fun `cpu era image lane leans`() {
        assertTrue(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = false, tierIsCpuVision = false, activeBackend = "CPU",
            )
        )
    }

    // ── 2. GPU era: nobody leans (the rich prompt is the point of the GPU) ──

    @Test
    fun `gpu era never leans`() {
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = false, tierIsCpuVision = false, activeBackend = "GPU",
            )
        )
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = false, activeBackend = "GPU",
            )
        )
    }

    // ── 3. Tier 2/3 (perma-CPU-vision): image lanes lean, text lanes keep fidelity ──

    @Test
    fun `cpu vision tier image lane leans even at gpu backend`() {
        assertTrue(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = false, tierIsCpuVision = true, activeBackend = "GPU",
            )
        )
    }

    @Test
    fun `cpu vision tier text lane never leans`() {
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = true, activeBackend = "GPU",
            )
        )
    }

    // ── 4. Unknown backend must not trigger lean — the gate needs evidence ──

    @Test
    fun `unknown backend with capable tier does not lean`() {
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = false, activeBackend = null,
            )
        )
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = false, tierIsCpuVision = false, activeBackend = null,
            )
        )
    }

    // ── 5. Defensive comment hygiene pin (the v1 regression) ──

    @Test
    fun `tier 2 input alone does not lean a text lane at gpu backend`() {
        // Regression target for the v1→v2 edit: text lanes at Tier 2/3 only
        // lean if the ACTIVE backend is CPU; the GPU-era text lane renders
        // its rich form (mirrors case 3's second assertion at the other tier flag).
        assertFalse(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = true, activeBackend = "GPU",
            )
        )
    }
}

package com.vyze.app.core

import org.junit.Assert.assertEquals
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

    // ── 2. GPU era leans too (v3, 2026-10-21) ──────────────────────────
    // v2 kept GPU prompts rich ("the rich prompt is the point of the GPU");
    // v3 flipped it on MEASURED evidence: session 314f7542 on the Infinix
    // MT6899 — a 5,323-char prompt ate 9,769 ms of prefill on the GPU
    // backend. Prefill scales with prompt chars on every backend, so rich-
    // on-GPU preserved the cost on the one backend meant to be fast.

    @Test
    fun `gpu era leans too - v3 evidence pin`() {
        assertTrue(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = false, tierIsCpuVision = false, activeBackend = "GPU",
            )
        )
        assertTrue(
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
    fun `cpu vision tier cannot veto the gpu lean verdict on text lanes - v3`() {
        // v2 pinned FALSE here (tier text lanes kept the rich form at GPU);
        // v3 says the BACKEND decides — the tier flag may only ADD lean
        // (image lanes at Tier 2/3), never remove it.
        assertTrue(
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
    fun `tier flag is verdict-neutral for gpu text lanes - v3`() {
        // v1→v2 regression target, re-pinned under v3: the tier flag must
        // produce the SAME verdict with and without it — it only ever added
        // lean for image lanes at unknown/absent backend evidence, and must
        // never grow a veto over the backend verdict (that veto was the v2
        // form this test used to pin).
        assertEquals(
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = false, activeBackend = "GPU",
            ),
            VyzeCoreController.companionCpuEraLeanMode(
                isTextQuery = true, tierIsCpuVision = true, activeBackend = "GPU",
            ),
        )
    }
}

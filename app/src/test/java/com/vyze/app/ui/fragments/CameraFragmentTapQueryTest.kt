package com.vyze.app.ui.fragments

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the single-tap query ([CameraFragment.companionTapQuery]).
 *
 * The tap prompt rides every tap turn's prefill (~2.6-6 ms per token on
 * CPU), so it stays short — but two substrings are load-bearing and must
 * survive any future trim:
 * - "tapped at position": the tap classifier marker
 *   ([VyzeCoreController] keys tap routing, OCR pre-pass and verbatim
 *   exclusions on it).
 * - a read verb: taps classify as text-extraction queries, which arms the
 *   refusal swap, the CJK coverage net and the read-recital contract for
 *   taps that land on text.
 */
class CameraFragmentTapQueryTest {

    @Test
    fun `tap query keeps the tap-position marker`() {
        val q = CameraFragment.companionTapQuery(87, 412)
        assertTrue(q.contains("tapped at position"))
        assertTrue(q.contains("87"))
        assertTrue(q.contains("412"))
    }

    @Test
    fun `tap query keeps a read verb for read-active classification`() {
        val q = CameraFragment.companionTapQuery(0, 0).lowercase()
        assertTrue(q.contains("read"))
    }

    @Test
    fun `tap query stays within the prefill budget`() {
        // The 2026-11-12 device baseline was ~800 chars of rule prose the
        // DIRECT/NAV blocks already own; the builder must never regrow it.
        val q = CameraFragment.companionTapQuery(1080, 2340)
        assertTrue("tap query ${q.length} chars exceeds budget", q.length <= 200)
    }
}

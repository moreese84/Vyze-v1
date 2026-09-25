package com.vyze.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SpeechGatePolicy] — Layer 0 of the anti-hallucination
 * stack. Pins the evidence-bounded floor: every observed Gemma fabrication
 * sat on sub-300ms classified speech; every real query carried ≥920ms.
 */
class SpeechGatePolicyTest {

    @Test
    fun `floor is evidence-bounded between hallucinations and real queries`() {
        // Highest observed hallucination speechMs (the corpus max was ≤140).
        assertTrue(140L < SpeechGatePolicy.MIN_SPEECH_MS)
        // Lowest observed real-query speechMs (920ms, 2026-09-25 session).
        assertTrue(SpeechGatePolicy.MIN_SPEECH_MS < 920L)
    }

    @Test
    fun `silent clip is rejected`() {
        assertFalse(SpeechGatePolicy.shouldTranscribe(0L))
    }

    @Test
    fun `hallucination-zone clip is rejected`() {
        // The "I am a large language model" clips: speechMs ≤ 140.
        assertFalse(SpeechGatePolicy.shouldTranscribe(140L))
        assertFalse(SpeechGatePolicy.shouldTranscribe(80L))
    }

    @Test
    fun `real query zone is accepted`() {
        assertTrue(SpeechGatePolicy.shouldTranscribe(300L))   // exactly at floor
        assertTrue(SpeechGatePolicy.shouldTranscribe(920L))   // observed real query
        assertTrue(SpeechGatePolicy.shouldTranscribe(1320L))  // 2026-09-25 zh query
    }

    @Test
    fun `just below floor is rejected`() {
        assertFalse(SpeechGatePolicy.shouldTranscribe(299L))
    }
}

package com.vyze.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CaptureStopPolicy] — the L2c no-speech bail (2026-09-24).
 *
 * Device evidence driving this: the 08:00 session logged 5/5 captures at
 * the full 8s cap because a contaminated calibration threshold classified
 * real speech as silence (speechMs 20–560, never reaching the 600ms floor)
 * — so the normal early stop was never eligible. Gemma transcribed that
 * speech from the clip tail anyway, proving the extra waiting is waste.
 *
 * Contract pinned here:
 *  - Normal exit unchanged: min speech + trailing silence
 *  - Bail: after the bail window, IF speech never accumulated AND the tail
 *    is silent — but NEVER mid-utterance (silence tail resets on speech)
 *  - Hard cap always wins
 *  - Bail never fires while the user is actively speaking
 */
class CaptureStopPolicyTest {

    private fun policy(
        minSpeechMs: Long = 600,
        trailingSilenceMs: Long = 800,
        noSpeechBailMs: Long = 4000,
        maxDurationMs: Long = 8000,
    ) = CaptureStopPolicy(minSpeechMs, trailingSilenceMs, noSpeechBailMs, maxDurationMs)

    // ── Normal exit (behavior unchanged from L2) ────────────────────────

    @Test
    fun `normal exit needs min speech plus trailing silence`() {
        val p = policy()
        // Enough speech but tail not yet silent → keep recording
        assertFalse(p.shouldStop(speechMs = 600, silenceMs = 500, elapsedMs = 2000))
        // Speech + full trailing silence → stop
        assertTrue(p.shouldStop(speechMs = 600, silenceMs = 800, elapsedMs = 2400))
    }

    @Test
    fun `short speech with quiet tail below min speech does NOT exit normally`() {
        val p = policy()
        // This is exactly failure mode (2): 520ms of speech classified,
        // then quiet — old rule can't stop it
        assertFalse(p.shouldStop(speechMs = 520, silenceMs = 2000, elapsedMs = 3500))
    }

    // ── No-speech bail ───────────────────────────────────────────────────

    @Test
    fun `bail fires after the bail window with sub-minimum speech and silent tail`() {
        val p = policy()
        // 4.1s elapsed, only 520ms classified speech, quiet for 2s → stop
        assertTrue(p.shouldStop(speechMs = 520, silenceMs = 2000, elapsedMs = 4100))
    }

    @Test
    fun `bail does NOT fire before the bail window`() {
        val p = policy()
        assertFalse(p.shouldStop(speechMs = 520, silenceMs = 2000, elapsedMs = 3900))
    }

    @Test
    fun `bail does NOT fire while the tail is loud (mid-utterance)`() {
        val p = policy()
        // Contaminated threshold: user IS talking but gate calls it silence...
        // ...except the tail is loud here, meaning speech is in progress
        assertFalse(p.shouldStop(speechMs = 100, silenceMs = 0, elapsedMs = 5000))
    }

    @Test
    fun `bail does NOT fire once speech already reached the minimum`() {
        val p = policy()
        // Speech requirement met but tail not silent yet — must wait for the
        // normal exit, not bail (bail is only for never-accumulated speech)
        assertFalse(p.shouldStop(speechMs = 700, silenceMs = 500, elapsedMs = 5000))
    }

    // ── Hard cap ─────────────────────────────────────────────────────────

    @Test
    fun `hard cap always stops regardless of audio state`() {
        val p = policy()
        assertTrue(p.shouldStop(speechMs = 0, silenceMs = 0, elapsedMs = 8000))
        assertTrue(p.shouldStop(speechMs = 5000, silenceMs = 0, elapsedMs = 8200))
    }

    @Test
    fun `cap boundary respects elapsed exactly`() {
        val p = policy()
        assertFalse(p.shouldStop(speechMs = 0, silenceMs = 0, elapsedMs = 7999))
        assertTrue(p.shouldStop(speechMs = 0, silenceMs = 0, elapsedMs = 8000))
    }

    // ── The exact device scenario from the 08:00 session ────────────────

    @Test
    fun `device session capture with 520ms speech and contamination bails at 4s not 8s`() {
        val p = policy()
        // speechMs grows slowly to 520 by ~4s, tail quiet after 3.2s
        assertFalse(p.shouldStop(speechMs = 300, silenceMs = 800, elapsedMs = 2500))
        assertTrue(p.shouldStop(speechMs = 520, silenceMs = 800, elapsedMs = 4200))
    }
}

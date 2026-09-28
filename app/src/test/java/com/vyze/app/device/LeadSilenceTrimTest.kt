package com.vyze.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [LeadSilenceTrim] — the latency-recovery decision for the
 * retained calibration window (2026-09-28).
 *
 * Device evidence: after the calibration audio started being RETAINED
 * (failure mode 4 fix), the common case — a short pause between the
 * double-tap and the first word — serialized up to 800ms of leading
 * silence into the clip, and the ASR paid for all of it (~+800ms latency
 * vs the pre-calibration build). The trim keeps everything from one chunk
 * before the first spoken chunk onward.
 *
 * Contract (pure, deterministic):
 *  - leading silence present → trim to (leadSilentChunks - 1) chunks
 *  - no leading silence (spoke immediately) → keep everything (0)
 *  - ALL chunks silent → keep the final chunk (onset may sit in it)
 *  - malformed inputs (0 chunks / 0 samples) → keep everything
 */
class LeadSilenceTrimTest {

    private val CHUNK = SilenceGate.CAL_CHUNK_SAMPLES // 320 samples = 20ms

    @Test
    fun `user paused 800ms before speaking — trim all but one lead chunk`() {
        // 40 calibration chunks, ALL silent (spoke after the window):
        // keep the last silent chunk as lead-in → 780ms of dead air removed.
        assertEquals(39 * CHUNK, LeadSilenceTrim.startSample(40, 40, CHUNK))
    }

    @Test
    fun `user spoke inside the window — silence before speech is trimmed`() {
        // 40 chunks: 12 silent (240ms) then 28 of speech. Keep from chunk 11
        // → 220ms of natural lead-in, 10 chunks (200ms) of dead air removed.
        assertEquals(11 * CHUNK, LeadSilenceTrim.startSample(12, 40, CHUNK))
    }

    @Test
    fun `user spoke immediately — nothing is trimmed`() {
        assertEquals(0, LeadSilenceTrim.startSample(0, 40, CHUNK))
    }

    @Test
    fun `one silent chunk then speech — still trims to zero`() {
        // The single silent chunk IS the natural lead-in; keep everything.
        assertEquals(0, LeadSilenceTrim.startSample(1, 40, CHUNK))
    }

    @Test
    fun `all silent window keeps the final chunk as lead-in`() {
        // Degenerate: the whole window is silent. The streaming part
        // continues after it, so the onset may sit at the boundary —
        // keep exactly one chunk, never zero audio.
        assertEquals(39 * CHUNK, LeadSilenceTrim.startSample(40, 40, CHUNK))
    }

    @Test
    fun `partial trailing window (no full chunk) keeps everything`() {
        // fullChunks=40 but leadSilentChunks=40 → same as all-silent case;
        // a remainder < 320 samples (the un-chunked tail) rides along.
        assertEquals(39 * CHUNK, LeadSilenceTrim.startSample(40, 40, CHUNK))
    }

    @Test
    fun `malformed inputs are inert`() {
        assertEquals(0, LeadSilenceTrim.startSample(0, 0, CHUNK))
        assertEquals(0, LeadSilenceTrim.startSample(5, 0, CHUNK))
        assertEquals(0, LeadSilenceTrim.startSample(5, 40, 0))
        assertEquals(0, LeadSilenceTrim.startSample(-1, 40, CHUNK))
    }

    @Test
    fun `lead silence count above full chunks is clamped`() {
        // Defensive: a miscounted leadSilentChunks must not index past the
        // window — clamped to fullChunks (keep-last-chunk behavior).
        assertEquals(39 * CHUNK, LeadSilenceTrim.startSample(99, 40, CHUNK))
    }
}

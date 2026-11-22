package com.vyze.app.ui.fragments

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the follow-up mic timing contract (whole-app audit
 * P3 #85).
 *
 * The non-streaming answer path opens the follow-up mic on a fixed
 * delay after the last utterance completes — with no other drain
 * protection, that delay must clear the AudioTrack hardware grace or
 * the mic captures the answer's own tail as the next query. The
 * relationship (not the values) is pinned against the production
 * constants — no copies (audit #98).
 */
class CameraFragmentTimingTest {

    @Test
    fun `follow-up mic delay clears the audio drain grace`() {
        assertTrue(
            "FOLLOW_UP_OPEN_DELAY_MS (${CameraFragment.FOLLOW_UP_OPEN_DELAY_MS}) " +
                "must clear AUDIO_DRAIN_GRACE_MS (${CameraFragment.AUDIO_DRAIN_GRACE_MS})",
            CameraFragment.FOLLOW_UP_OPEN_DELAY_MS >= CameraFragment.AUDIO_DRAIN_GRACE_MS
        )
    }

    @Test
    fun `mic opens after the ready beep ends`() {
        // The mic must go live after the tone finishes (never into it —
        // early speech is physically lost) yet fast enough that the
        // tap-to-live window stays near ~400 ms with dispatch.
        assertTrue(
            "MIC_OPEN_AFTER_CUE_MS (${CameraFragment.MIC_OPEN_AFTER_CUE_MS}) " +
                "must exceed MIC_CUE_TONE_MS (${CameraFragment.MIC_CUE_TONE_MS})",
            CameraFragment.MIC_OPEN_AFTER_CUE_MS > CameraFragment.MIC_CUE_TONE_MS
        )
        assertTrue(
            "tap-to-live window must stay near-instant",
            CameraFragment.MIC_OPEN_AFTER_CUE_MS <= 500L
        )
    }
}

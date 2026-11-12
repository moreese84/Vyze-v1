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
}

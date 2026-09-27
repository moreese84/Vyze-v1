package com.vyze.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the IMPLICIT FAILURE MARKERS (plan C, 2026-09-24/27):
 * the app silently identifies its own failures and pre-labels interaction
 * rows so shared corpus rows arrive as failure evidence. Pure decisions —
 * the controller keeps the tiny per-conversation state.
 */
class SuspectMarkerTest {

    // ── Recovery cues ────────────────────────────────────────────

    @Test
    fun `recovery cues detected across languages`() {
        // en
        assertTrue(SuspectMarker.isRecoveryCue("please say that again"))
        assertTrue(SuspectMarker.isRecoveryCue("i didnt catch that"))
        assertTrue(SuspectMarker.isRecoveryCue("what did you say"))
        // ms
        assertTrue(SuspectMarker.isRecoveryCue("sila sebut sekali lagi"))
        assertTrue(SuspectMarker.isRecoveryCue("tak dengar"))
        // zh
        assertTrue(SuspectMarker.isRecoveryCue("请再说一遍"))
        assertTrue(SuspectMarker.isRecoveryCue("我没有听清"))
    }

    @Test
    fun `recovery cue prefix tolerance keeps cue-dominant tails`() {
        assertTrue(SuspectMarker.isRecoveryCue("please say that again please"))
        assertTrue(SuspectMarker.isRecoveryCue("say that again 2"))
    }

    @Test
    fun `real queries are never recovery cues`() {
        assertFalse(SuspectMarker.isRecoveryCue("read this again for me"))
        assertFalse(SuspectMarker.isRecoveryCue("what is in front of me"))
        assertFalse(SuspectMarker.isRecoveryCue("berapa duit ini"))
        assertFalse(SuspectMarker.isRecoveryCue("这是什么"))
        assertFalse(SuspectMarker.isRecoveryCue(""))
    }

    // ── Repeat-within-window ─────────────────────────────────────

    @Test
    fun `identical quick repeat is marked`() {
        assertTrue(
            SuspectMarker.isRepeatWithinWindow(
                query = "what is in front of me",
                lastQuery = "What is in front of me?",
                lastQueryMsAgo = 20_000L,
            )
        )
    }

    @Test
    fun `repeat outside the window is not marked`() {
        assertFalse(
            SuspectMarker.isRepeatWithinWindow(
                query = "what is in front of me",
                lastQuery = "what is in front of me",
                lastQueryMsAgo = SuspectMarker.REPEAT_WINDOW_MS + 1,
            )
        )
    }

    @Test
    fun `different query is not a repeat`() {
        assertFalse(
            SuspectMarker.isRepeatWithinWindow(
                query = "is there a cat",
                lastQuery = "what is in front of me",
                lastQueryMsAgo = 5_000L,
            )
        )
    }

    @Test
    fun `no prior query or blank inputs never mark`() {
        assertFalse(
            SuspectMarker.isRepeatWithinWindow("anything", null, null)
        )
        assertFalse(
            SuspectMarker.isRepeatWithinWindow("   ", "what is this", 1000L)
        )
        assertFalse(
            SuspectMarker.isRepeatWithinWindow("what is this", "   ", 1000L)
        )
    }

    // ── Language mismatch ────────────────────────────────────────

    @Test
    fun `malay query with english answer is a mismatch`() {
        assertTrue(
            SuspectMarker.isLangMismatch(
                query = "berapa duit ini",
                answer = "That is a red Maggi packet.",
            )
        )
    }

    @Test
    fun `mirrored answer is not a mismatch`() {
        assertFalse(
            SuspectMarker.isLangMismatch(
                query = "berapa duit ini",
                answer = "Itu wang kertas lima ringgit.",
            )
        )
    }

    @Test
    fun `unknown on either side never marks`() {
        // No letters at all (pure numbers/symbols) → inferred unknown →
        // no language evidence either way, never a mark.
        assertFalse(
            SuspectMarker.isLangMismatch(
                query = "123 456",
                answer = "That is a red Maggi packet.",
            )
        )
    }

    @Test
    fun `malay-garble query with english answer marks - garble of ms counts as ms by design`() {
        // inferLanguage deliberately labels Malay-garble AS ms (its doc:
        // "includes ASR garble of Malay speech"). So garbled-ms query +
        // English answer is genuine wrong-language failure evidence —
        // exactly what the marks exist to surface.
        assertTrue(
            SuspectMarker.isLangMismatch(
                query = "adaka to Patti se Jo",
                answer = "That is a red Maggi packet.",
            )
        )
    }

    @Test
    fun `english query with malay answer IS a mismatch - wrong-language bug evidence`() {
        // A valid-ms answer to an English query is exactly the
        // wrong-language bug the marks exist to surface — this must mark.
        assertTrue(
            SuspectMarker.isLangMismatch(
                query = "what is this",
                answer = "Itu paket Maggi merah.",
            )
        )
    }

    // ── Tag composition ─────────────────────────────────────────

    @Test
    fun `tags compose in stable order`() {
        val tags = SuspectMarker.tagsForStoredRecord(
            priorBargeIn = true,
            priorAsrFailure = true,
            langMismatch = true,
            repeatWithinWindow = true,
        )
        assertEquals(
            "${SuspectMarker.TAG_BARGE_IN_PRIOR} ${SuspectMarker.TAG_PRIOR_ASR_FAILURE} " +
                "${SuspectMarker.TAG_LANG_MISMATCH} ${SuspectMarker.TAG_REPEAT_60S}",
            tags,
        )
    }

    @Test
    fun `clean record composes empty string - feedback column stays clean`() {
        assertEquals(
            "",
            SuspectMarker.tagsForStoredRecord(
                priorBargeIn = false,
                priorAsrFailure = false,
                langMismatch = false,
                repeatWithinWindow = false,
            )
        )
    }
}

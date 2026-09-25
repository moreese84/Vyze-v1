package com.vyze.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SuspectSignals] — the ghost-text suspects that arm the
 * suspect ladder for confident English misrecognitions of Mandarin speech
 * (2026-09-25 device evidence: "speak in Chinese", "what are you talking",
 * "Chinese" returned as confident en_US results for Chinese queries).
 */
class SuspectSignalsTest {

    // ── Language-name echo ───────────────────────────────────────────

    @Test
    fun `bare language name is an echo`() {
        assertTrue(SuspectSignals.isLanguageNameEcho("Chinese"))
        assertTrue(SuspectSignals.isLanguageNameEcho("mandarin"))
        assertTrue(SuspectSignals.isLanguageNameEcho("Malay"))
        assertTrue(SuspectSignals.isLanguageNameEcho("中文"))
    }

    @Test
    fun `speak-in-language forms are echoes`() {
        assertTrue(SuspectSignals.isLanguageNameEcho("speak in Chinese"))
        assertTrue(SuspectSignals.isLanguageNameEcho("SPEAK CHINESE"))
        assertTrue(SuspectSignals.isLanguageNameEcho("speaking malay"))
        assertTrue(SuspectSignals.isLanguageNameEcho("chinese language"))
    }

    @Test
    fun `echo with polite tail still counts`() {
        assertTrue(SuspectSignals.isLanguageNameEcho("speak in chinese please"))
        assertTrue(SuspectSignals.isLanguageNameEcho("can you speak chinese"))
    }

    @Test
    fun `real queries are never echoes`() {
        assertFalse(SuspectSignals.isLanguageNameEcho("what is this"))
        assertFalse(SuspectSignals.isLanguageNameEcho("read the label for me"))
        assertFalse(SuspectSignals.isLanguageNameEcho("apa ini"))
        assertFalse(SuspectSignals.isLanguageNameEcho("这是什么东西"))
        // The dictionary word inside a real sentence does not trigger.
        assertFalse(SuspectSignals.isLanguageNameEcho("what color is this shirt"))
    }

    // ── Language-flip suspect ────────────────────────────────────────

    @Test
    fun `fluent english after ms-zh history is suspect`() {
        assertTrue(
            SuspectSignals.isFlipSuspect(
                resultIsEnglish = true, spokenMsZhHistory = true, ladderActive = false,
            )
        )
    }

    @Test
    fun `fluent english while ladder is mid-climb is suspect`() {
        assertTrue(
            SuspectSignals.isFlipSuspect(
                resultIsEnglish = true, spokenMsZhHistory = false, ladderActive = true,
            )
        )
    }

    @Test
    fun `english with no ms-zh evidence is not suspect`() {
        // First-contact English user — no signals, no extra listens.
        assertFalse(
            SuspectSignals.isFlipSuspect(
                resultIsEnglish = true, spokenMsZhHistory = false, ladderActive = false,
            )
        )
    }

    @Test
    fun `non-english result is never flip-suspect`() {
        assertFalse(
            SuspectSignals.isFlipSuspect(
                resultIsEnglish = false, spokenMsZhHistory = true, ladderActive = true,
            )
        )
    }
}

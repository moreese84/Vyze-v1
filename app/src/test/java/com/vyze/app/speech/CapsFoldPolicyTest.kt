package com.vyze.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the TTS caps-fold ([TTSManager.foldCapsWordsForSpeech]).
 *
 * Live 2026-11-07: "HILLARY" (and TENOF-style brands) reached the ear
 * spelled letter by letter — Google TTS reads unknown ALL-CAPS tokens
 * as initialisms. Folding 4+ letter caps words to Title case makes the
 * engine read them as words, while true initialisms keep spelling.
 */
class CapsFoldPolicyTest {

    @Test
    fun `long caps words fold to title case`() {
        // The user's exact case.
        assertEquals("Hillary", TTSManager.foldCapsWordsForSpeech("HILLARY"))
        assertEquals("Tenof", TTSManager.foldCapsWordsForSpeech("TENOF"))
        assertEquals(
            "Bottle holds Tenof, 30 Tablets.",
            TTSManager.foldCapsWordsForSpeech("Bottle holds TENOF, 30 Tablets.")
        )
        assertEquals("Petronas", TTSManager.foldCapsWordsForSpeech("PETRONAS"))
        assertEquals("Sony", TTSManager.foldCapsWordsForSpeech("SONY"))
    }

    @Test
    fun `short initialisms keep spelling`() {
        assertEquals("TV", TTSManager.foldCapsWordsForSpeech("TV"))
        assertEquals("BMW", TTSManager.foldCapsWordsForSpeech("BMW"))
        assertEquals("USA", TTSManager.foldCapsWordsForSpeech("USA"))
        assertEquals("OK", TTSManager.foldCapsWordsForSpeech("OK"))
        assertEquals("UM", TTSManager.foldCapsWordsForSpeech("UM"))
        assertEquals("LRT", TTSManager.foldCapsWordsForSpeech("LRT"))
        assertEquals("I", TTSManager.foldCapsWordsForSpeech("I"))
    }

    @Test
    fun `pinned four-letter initialisms keep spelling`() {
        // Malaysian initialisms pronounced letter by letter — the length
        // heuristic would fold them wrongly, so they are pinned. Add new
        // entries here with the same test shape when users report them.
        assertEquals("HSBC", TTSManager.foldCapsWordsForSpeech("HSBC"))
        assertEquals("OCBC", TTSManager.foldCapsWordsForSpeech("OCBC"))
        assertEquals("ECRL", TTSManager.foldCapsWordsForSpeech("ECRL"))
    }

    @Test
    fun `dotted alphanumeric and mixed-case tokens are untouched`() {
        assertEquals("U.S.A", TTSManager.foldCapsWordsForSpeech("U.S.A"))
        assertEquals("COVID19", TTSManager.foldCapsWordsForSpeech("COVID19"))
        assertEquals("G81", TTSManager.foldCapsWordsForSpeech("G81"))
        assertEquals("MP3", TTSManager.foldCapsWordsForSpeech("MP3"))
        assertEquals("RM4.50", TTSManager.foldCapsWordsForSpeech("RM4.50"))
        assertEquals("Tenofovir", TTSManager.foldCapsWordsForSpeech("Tenofovir"))
        assertEquals("eBay", TTSManager.foldCapsWordsForSpeech("eBay"))
    }

    @Test
    fun `apostrophe words fold their caps runs`() {
        assertEquals("O'Clock", TTSManager.foldCapsWordsForSpeech("O'CLOCK"))
    }

    @Test
    fun `blank input passes through`() {
        assertEquals("", TTSManager.foldCapsWordsForSpeech(""))
        assertEquals("   ", TTSManager.foldCapsWordsForSpeech("   "))
    }
}

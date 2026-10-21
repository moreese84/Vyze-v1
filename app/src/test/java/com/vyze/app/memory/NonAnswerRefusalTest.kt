package com.vyze.app.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the canned-refusal non-answer detector (2026-10-21).
 *
 * Ground truth: "Text is unclear." was stored like any answer and then
 * re-injected as "Prior scene memory" into the very next prompt (device
 * session 10:19:45 injection → 10:19:55 answer repeated the refusal — the
 * middle row of the three "Text is unclear." records). A refusal is an
 * honest utterance but carries zero scene fact: it must stay in the corpus
 * (dialogue history, exports, suspect analysis) yet never anchor a future
 * scene answer through similarity injection or text recall.
 */
class NonAnswerRefusalTest {

    @Test
    fun `canned english refusals are non-answers`() {
        assertTrue(NonAnswerRefusal.isNonAnswer("Text is unclear."))
        assertTrue(NonAnswerRefusal.isNonAnswer("The text is unclear."))
        assertTrue(NonAnswerRefusal.isNonAnswer("No text visible"))
        assertTrue(NonAnswerRefusal.isNonAnswer("not clearly visible"))
        assertTrue(NonAnswerRefusal.isNonAnswer("I cannot read this clearly"))
        assertTrue(NonAnswerRefusal.isNonAnswer("I cannot identify this card clearly."))
        assertTrue(NonAnswerRefusal.isNonAnswer("I do not know that."))
    }

    @Test
    fun `canned malay and chinese refusals are non-answers - localized failure phrases`() {
        // The FAILURE-PHRASE OVERRIDE pins these exact spoken forms per
        // language; the few-shots add the Teksnya/文字 spelling variants.
        assertTrue(NonAnswerRefusal.isNonAnswer("Teks tidak jelas."))
        assertTrue(NonAnswerRefusal.isNonAnswer("Teksnya tidak jelas."))
        assertTrue(NonAnswerRefusal.isNonAnswer("Tiada teks kelihatan."))
        assertTrue(NonAnswerRefusal.isNonAnswer("tidak kelihatan dengan jelas"))
        assertTrue(NonAnswerRefusal.isNonAnswer("Saya tidak tahu tentang itu."))
        assertTrue(NonAnswerRefusal.isNonAnswer("文字不清楚。"))
        assertTrue(NonAnswerRefusal.isNonAnswer("看不到文字"))
        assertTrue(NonAnswerRefusal.isNonAnswer("看不清楚。"))
        assertTrue(NonAnswerRefusal.isNonAnswer("我读不清楚。"))
        assertTrue(NonAnswerRefusal.isNonAnswer("我不知道"))
    }

    @Test
    fun `scene answers that merely tail with a refusal stay memory-eligible`() {
        // The few-shot itself produces this shape — real scene facts plus a
        // "not clearly visible" tail. Substring matching would poison-filter
        // a perfectly good memory row, so matching is WHOLE-OUTPUT only.
        assertFalse(NonAnswerRefusal.isNonAnswer(
            "That is a small dark object with a metal edge; its exact name is not clearly visible.",
        ))
        assertFalse(NonAnswerRefusal.isNonAnswer(
            "A blue metal shoe rack holds many pairs of shoes.",
        ))
        assertFalse(NonAnswerRefusal.isNonAnswer(
            "The sign says CLOSED in red letters.",
        ))
    }

    @Test
    fun `refusal phrase embedded mid-answer is not a whole-output match`() {
        // The phrase appears inside a longer description — the row still
        // carries scene facts and must stay injectable.
        assertFalse(NonAnswerRefusal.isNonAnswer(
            "The title says Welcome, the rest is text is unclear.",
        ))
    }

    @Test
    fun `null blank and punctuated-empty outputs are never non-answers`() {
        assertFalse(NonAnswerRefusal.isNonAnswer(null))
        assertFalse(NonAnswerRefusal.isNonAnswer(""))
        assertFalse(NonAnswerRefusal.isNonAnswer("   "))
        assertFalse(NonAnswerRefusal.isNonAnswer("..."))
    }
}

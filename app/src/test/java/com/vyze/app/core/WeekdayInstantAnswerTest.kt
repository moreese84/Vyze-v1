package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * JVM tests for [WeekdayInstantAnswer] — the deterministic endgame of the
 * date arc. Five device rounds of evidence (2026-09-29) showed any path
 * where the 2B model assembles weekday+date strings fabricates at some
 * rate; Kotlin now owns the arithmetic AND the answer string.
 *
 * All tests inject a FIXED clock — Tuesday 2026-09-29, the session's live
 * week — so expected pairings are literal, not recomputed.
 */
class WeekdayInstantAnswerTest {

    /** The session's live week: 2026-09-29 is a Tuesday. */
    private val tuesday = LocalDate.of(2026, 9, 29)

    // ── English ──────────────────────────────────────────────────────

    @Test
    fun `english this and next friday on a tuesday`() {
        assertEquals(
            "Friday is October 2.",
            WeekdayInstantAnswer.answerFor("what is the date this friday", "en", tuesday)
        )
        assertEquals(
            "Friday is October 9.",
            WeekdayInstantAnswer.answerFor("what is the date for next Friday", "en", tuesday)
        )
    }

    @Test
    fun `english wednesday and saturday the round-5 failures`() {
        // Round-5 device evidence: the model fabricated "Wednesday,
        // October 2" (a Friday). Kotlin cannot make this mistake.
        assertEquals(
            "Wednesday is October 7.",
            WeekdayInstantAnswer.answerFor("What is the date for next Wednesday?", "en", tuesday)
        )
        // Round-4/5 evidence: model answered "Saturday is October 1" (a
        // Thursday); round 5 got Oct 3 from the table — Kotlin agrees.
        assertEquals(
            "Saturday is October 10.",
            WeekdayInstantAnswer.answerFor("How about next Saturday", "en", tuesday)
        )
    }

    @Test
    fun `today-is-the-weekday edge picks today for this-form`() {
        // "this friday" ON a Friday = today (next occurrence incl. today).
        val friday = LocalDate.of(2026, 10, 2)
        assertEquals(
            "Friday is October 2.",
            WeekdayInstantAnswer.answerFor("this friday", "en", friday)
        )
    }

    // ── Bahasa Malaysia ─────────────────────────────────────────────

    @Test
    fun `malay jumaat depan and minggu ini`() {
        assertEquals(
            "Jumaat ialah 2 Oktober.",
            WeekdayInstantAnswer.answerFor("Jumaat minggu ini tarikh apa", "ms", tuesday)
        )
        assertEquals(
            "Jumaat ialah 9 Oktober.",
            WeekdayInstantAnswer.answerFor("Jumaat depan tarikh apa", "ms", tuesday)
        )
        assertEquals(
            "Rabu ialah 7 Oktober.",
            WeekdayInstantAnswer.answerFor("Rabu depan tarikh apa", "ms", tuesday)
        )
    }

    // ── Chinese ─────────────────────────────────────────────────────

    @Test
    fun `chinese simplified and traditional forms`() {
        // Simplified.
        assertEquals(
            "星期五是10月9日。",
            WeekdayInstantAnswer.answerFor("下个星期五是什么日期", "zh", tuesday)
        )
        // Traditional (ASR emits Traditional) — 週/個 variants.
        assertEquals(
            "星期五是10月9日。",
            WeekdayInstantAnswer.answerFor("下週五是什麼日期", "zh", tuesday)
        )
        // Sunday 天-form.
        assertEquals(
            "星期日是10月4日。",
            WeekdayInstantAnswer.answerFor("这个星期天是几号", "zh", tuesday)
        )
    }

    // ── Boundaries: what must NOT be answered locally ───────────────

    @Test
    fun `visual anchored date asks never answered locally`() {
        assertNull(
            WeekdayInstantAnswer.answerFor("what date is on this receipt", "en", tuesday)
        )
        assertNull(
            WeekdayInstantAnswer.answerFor("tarikh atas surat ini", "ms", tuesday)
        )
    }

    @Test
    fun `unsupported shapes fall through to the model`() {
        // Bare weekday with no this/next qualifier — ambiguous, model's job.
        assertNull(WeekdayInstantAnswer.answerFor("jumaat", "ms", tuesday))
        // Past-day ask — outside the table.
        assertNull(WeekdayInstantAnswer.answerFor("what was last monday", "en", tuesday))
        // Duration asks — the model + date contract handle these.
        assertNull(WeekdayInstantAnswer.answerFor("berapa hari lagi sampai raya", "ms", tuesday))
        // Non-date queries.
        assertNull(WeekdayInstantAnswer.answerFor("what is this", "en", tuesday))
        assertNull(WeekdayInstantAnswer.answerFor(null, "en", tuesday))
        assertNull(WeekdayInstantAnswer.answerFor("", "en", tuesday))
    }

    @Test
    fun `every parsed ask resolves to a true weekday pairing`() {
        // Property-style pin: for every trigger phrase, the returned date's
        // weekday must equal the requested weekday — the exact class of
        // fabrication the five device rounds exposed.
        val asks = listOf(
            "next monday" to DayOfWeek.MONDAY,
            "this tuesday" to DayOfWeek.TUESDAY,
            "next wednesday" to DayOfWeek.WEDNESDAY,
            "this thursday" to DayOfWeek.THURSDAY,
            "next friday" to DayOfWeek.FRIDAY,
            "this saturday" to DayOfWeek.SATURDAY,
            "next sunday" to DayOfWeek.SUNDAY,
        )
        // Answer strings carry no year; the fixed week is entirely in 2026.
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMMM d yyyy", java.util.Locale.US)
        for ((ask, day) in asks) {
            val answer = WeekdayInstantAnswer.answerFor(ask, "en", tuesday)
            assertTrue("no answer for: $ask", answer != null)
            val dateStr = answer!!.substringAfter("is ").trimEnd('.')
            val parsedDate = LocalDate.parse("$dateStr 2026", fmt)
            assertEquals(
                "wrong weekday pairing for: $ask",
                day, parsedDate.dayOfWeek
            )
        }
    }
}

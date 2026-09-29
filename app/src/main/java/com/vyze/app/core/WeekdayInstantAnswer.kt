package com.vyze.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * DETERMINISTIC WEEKDAY ANSWERS (2026-09-29, endgame of the date arc):
 * parseable weekday asks ("next Friday" / "Jumaat depan" / "下个星期五")
 * are answered by KOTLIN — no model inference at all — via the existing
 * instant-answer lane (same path as the battery query: zero capture, zero
 * prefill, ~0ms, no refusal possible, no fabrication possible).
 *
 * WHY DETERMINISTIC (five device rounds of evidence, 2026-09-29):
 *  - Unanchored 2B one-shots fabricated dates ("25 Oktober 20000",
 *    "October 7" for a Wednesday ask).
 *  - A static worked example was copied VERBATIM including its wrong date.
 *  - A Friday-only computed table left other weekdays to model arithmetic,
 *    which mislabeled pairings ("Saturday is October 1" — a Thursday).
 *  - The full all-weekdays table still suffered re-rendering fabricats
 *    ("Wednesday, October 2" — a Friday) ~1-in-3.
 * Conclusion: any path where the model ASSEMBLES weekday+date strings
 * keeps producing plausible garbage at some rate. Kotlin owns the
 * arithmetic AND the answer string; the model is never invoked.
 *
 * The this/next-week convention is a CODE decision (see [pickDate]),
 * not a model coin flip: "this X" = the next occurrence of X including
 * today; "next X" = the occurrence of X in the following calendar week.
 *
 * UNSUPPORTED shapes ("how many days until Raya", "what was the date last
 * Tuesday", bare "next friday" with no this/next qualifier... ) return
 * null and fall through to the model + date contract, which handles them.
 *
 * Pure object: text + clock in → String? out. No Android — JVM-tested.
 */
object WeekdayInstantAnswer {

    /** A weekday ask phrase and the day it names. */
    private data class Phrase(val text: String, val day: DayOfWeek, val nextWeek: Boolean)

    /**
     * Trigger phrases per language. Word-boundary regexes for Latin scripts
     * (substring matching proved enough for zh's shared-script keywords in
     * DATE_KEYWORDS, but Latin phrases nest: "friday" must not fire inside
     * a longer unmatched form). All lowercase — callers normalize.
     */
    private val PHRASES: List<Phrase> = buildList {
        // ── English ──────────────────────────────────────────────────
        val enNames = mapOf(
            DayOfWeek.MONDAY to "monday", DayOfWeek.TUESDAY to "tuesday",
            DayOfWeek.WEDNESDAY to "wednesday", DayOfWeek.THURSDAY to "thursday",
            DayOfWeek.FRIDAY to "friday", DayOfWeek.SATURDAY to "saturday",
            DayOfWeek.SUNDAY to "sunday",
        )
        for ((day, name) in enNames) {
            add(Phrase("\\bthis $name\\b", day, nextWeek = false))
            add(Phrase("\\bnext $name\\b", day, nextWeek = true))
            add(Phrase("\\bcoming $name\\b", day, nextWeek = false))
        }
        // ── Bahasa Malaysia ─────────────────────────────────────────
        // "ini" = this, "depan" = next; "hadapan" accepted as next too.
        val msNames = mapOf(
            DayOfWeek.MONDAY to "isnin", DayOfWeek.TUESDAY to "selasa",
            DayOfWeek.WEDNESDAY to "rabu", DayOfWeek.THURSDAY to "khamis",
            DayOfWeek.FRIDAY to "jumaat", DayOfWeek.SATURDAY to "sabtu",
            DayOfWeek.SUNDAY to "ahad",
        )
        for ((day, name) in msNames) {
            add(Phrase("\\b$name ini\\b", day, nextWeek = false))
            // "Jumaat minggu ini" = Friday THIS WEEK — the natural spoken
            // form (device row: "Jumaat minggu ini tarikh apa").
            add(Phrase("\\b$name minggu ini\\b", day, nextWeek = false))
            add(Phrase("\\b$name (depan|hadapan)\\b", day, nextWeek = true))
            add(Phrase("\\b$name minggu (depan|hadapan)\\b", day, nextWeek = true))
        }
        // ── Chinese (Simplified + Traditional; ASR emits Traditional) ─
        // "这/這/本X" = this X, "下(个|個)?X" = next X. Weekday glyphs are
        // shared across scripts: 一=Mon 二=Tue 三=Wed 四=Thu 五=Fri 六=Sat
        // 日/天=Sun. Prefixes: 星期, 礼拜/禮拜, 週/周.
        val zhDays = listOf(
            "一" to DayOfWeek.MONDAY, "二" to DayOfWeek.TUESDAY,
            "三" to DayOfWeek.WEDNESDAY, "四" to DayOfWeek.THURSDAY,
            "五" to DayOfWeek.FRIDAY, "六" to DayOfWeek.SATURDAY,
        )
        val zhPrefixes = listOf("星期", "礼拜", "禮拜", "週", "周")
        for ((glyph, day) in zhDays) {
            for (prefix in zhPrefixes) {
                // Optional 个/個 in THIS-forms too: "这个星期五".
                add(Phrase("(这|這|本)(个|個)?$prefix$glyph", day, nextWeek = false))
                add(Phrase("下(个|個)?$prefix$glyph", day, nextWeek = true))
            }
        }
        // Sunday: 日/天 endings (星期日/星期天/礼拜日/週天/...).
        for (prefix in zhPrefixes) {
            for (end in listOf("日", "天")) {
                add(Phrase("(这|這|本)(个|個)?$prefix$end", DayOfWeek.SUNDAY, nextWeek = false))
                add(Phrase("下(个|個)?$prefix$end", DayOfWeek.SUNDAY, nextWeek = true))
            }
        }
    }

    /** Date style per output language — matches dateClauseFor conventions. */
    private fun formatterFor(lang: String): DateTimeFormatter = when (lang) {
        "zh" -> DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
        "ms" -> DateTimeFormatter.ofPattern("d MMMM", Locale("ms"))
        else -> DateTimeFormatter.ofPattern("MMMM d", Locale.US)
    }

    /**
     * The convention, as code: "this X" = next occurrence of X including
     * today; "next X" = X in the FOLLOWING calendar week. On 2026-09-29
     * (a Tuesday): "this friday" → Oct 2, "next friday" → Oct 9.
     */
    private fun pickDate(today: LocalDate, day: DayOfWeek, nextWeek: Boolean): LocalDate {
        val delta = ((day.value - today.dayOfWeek.value + 7) % 7).toLong()
        val thisWeek = today.plusDays(if (delta == 0L) 0 else delta)
        return if (nextWeek) {
            val daysToNextMonday = ((8 - today.dayOfWeek.value) % 7).takeIf { it != 0 } ?: 7
            nextMondayOf(today, daysToNextMonday).plusDays((day.value - 1).toLong())
        } else {
            thisWeek
        }
    }

    private fun nextMondayOf(today: LocalDate, daysToNextMonday: Int): LocalDate =
        today.plusDays(daysToNextMonday.toLong())

    /**
     * Parse a normalized weekday ask and produce the spoken answer, or null
     * when the shape is unsupported (caller falls back to the model lane).
     *
     * @param today the device clock — injected for JVM tests
     */
    fun answerFor(query: String?, lang: String, today: LocalDate = LocalDate.now()): String? {
        if (query.isNullOrBlank()) return null
        val lower = query.lowercase().trim()
        // Visual-anchored shapes ("date on this receipt") are camera asks
        // even when they contain a weekday phrase — never answer locally.
        if (VyzeCoreController.companionDateAnchorHit(lower)) return null
        val phrase = PHRASES.firstOrNull { Regex(it.text).containsMatchIn(lower) }
            ?: return null
        val date = pickDate(today, phrase.day, phrase.nextWeek)
        val dayName = phrase.day.getDisplayName(TextStyle.FULL, localeFor(lang))
        return when (lang) {
            "zh" -> "${dayName}是${date.format(formatterFor(lang))}。"
            "ms" -> "$dayName ialah ${date.format(formatterFor(lang))}."
            else -> "$dayName is ${date.format(formatterFor(lang))}."
        }
    }

    private fun localeFor(lang: String): Locale = when (lang) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "ms" -> Locale("ms")
        else -> Locale.US
    }
}

package com.vyze.app.speech

import com.vyze.app.speech.LadderPolicy.DEFAULT_LADDER
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * JVM tests for [LadderPolicy] — the locale-fallback ladder's retry-index
 * contract. Pins the 2026-09-24 device regression: a Chinese speaker on an
 * English-default phone got FOUR identical dead cycles ("No speech detected")
 * because the old skip logic re-tried en-US after an unpinned en-US-default
 * failure and never reached zh-CN.
 */
class LadderPolicyTest {

    private val en = DEFAULT_LADDER[0]      // en-US
    private val ms = DEFAULT_LADDER[1]      // ms-MY
    private val zh = DEFAULT_LADDER[2]      // zh-CN
    private val exhausted = DEFAULT_LADDER.size

    // ── REGRESSION: the device-log scenario ──────────────────────────

    @Test
    fun `unpinned en-US-default NO_MATCH with no spoken history skips straight to ms`() {
        // The bug: old logic retried en-US (duplicate of device default).
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = null, detectedBundleTag = null
        )
        assertEquals(1, next) // ms-MY, NOT en-US again
    }

    @Test
    fun `unpinned failure with spoken-zh history orders retry at zh directly`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = zh, detectedBundleTag = null
        )
        assertEquals(2, next) // zh-CN first — the language actually spoken
    }

    @Test
    fun `unpinned failure with spoken-ms history orders retry at ms`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = ms, detectedBundleTag = null
        )
        assertEquals(1, next)
    }

    @Test
    fun `unpinned two-ladder ladder cannot overrun when jumping to last index`() {
        // Two-ladder edge: unpinned failure, spoken zh, ladder [en, zh] →
        // the spoken jump lands on the last index; must not throw/overrun.
        val two = listOf(en, zh)
        val next = LadderPolicy.nextRetryIndex(
            locales = two, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = zh, detectedBundleTag = null
        )
        assertEquals(1, next)
    }

    @Test
    fun `spoken language not in ladder falls back to first non-English rung`() {
        // lastSpokenMsZh guards its own language, but a fabricated Locale
        // ("ja") must not derail the jump: fall back to index 1 (ms).
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = Locale("ja"), detectedBundleTag = null
        )
        assertEquals(1, next)
    }

    // ── Skip-the-failed-pinned-language contract ─────────────────────

    @Test
    fun `pinned en-US failure from top retries at ms`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = "en-US", lastSpokenMsZh = null, detectedBundleTag = null
        )
        assertEquals(1, next)
    }

    @Test
    fun `pinned ms-MY failure skips ms and retries at zh`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 1,
            failedPinnedTag = "ms-MY", lastSpokenMsZh = ms, detectedBundleTag = null
        )
        assertEquals(2, next)
    }

    @Test
    fun `pinned zh-CN failure from top skips zh`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = "zh-CN", lastSpokenMsZh = null, detectedBundleTag = null
        )
        assertEquals(1, next)
    }

    @Test
    fun `suspect path skips claimed bundle language`() {
        // Recognizer CLAIMED en-US in its bundle (transcribed but implausible).
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = "en-US", lastSpokenMsZh = null, detectedBundleTag = "en-US"
        )
        assertEquals(1, next)
    }

    @Test
    fun `bundle and pinned disagree — both are skipped`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 0,
            failedPinnedTag = "ms-MY", lastSpokenMsZh = null, detectedBundleTag = "en-US"
        )
        assertEquals(2, next) // en-US claimed, ms-MY pinned → zh remains
    }

    // ── Sequencing & exhaustion ──────────────────────────────────────

    @Test
    fun `ladder advances one rung per failure from a ladder retry`() {
        // Retry session (pinned ms-MY) also NO_MATCHed → advance to zh.
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 1,
            failedPinnedTag = "ms-MY", lastSpokenMsZh = null, detectedBundleTag = null
        )
        assertEquals(2, next)
    }

    @Test
    fun `ladder exhausts when the last rung fails`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = 2,
            failedPinnedTag = "zh-CN", lastSpokenMsZh = zh, detectedBundleTag = null
        )
        assertEquals(exhausted, next)
    }

    @Test
    fun `already-exhausted index stays exhausted`() {
        val next = LadderPolicy.nextRetryIndex(
            locales = DEFAULT_LADDER, currentIndex = exhausted,
            failedPinnedTag = "en-US", lastSpokenMsZh = null, detectedBundleTag = null
        )
        assertEquals(exhausted, next)
    }

    @Test
    fun `unpinned exhaustion edge - spoken jump at last rung with that rung failed`() {
        // Ladder [en, ms]: unpinned failure, spoken ms, ms ALSO in the failed
        // set → jump lands on ms then skips it → exhausted.
        val two = listOf(en, ms)
        val next = LadderPolicy.nextRetryIndex(
            locales = two, currentIndex = 0,
            failedPinnedTag = null, lastSpokenMsZh = ms, detectedBundleTag = "ms-MY"
        )
        assertEquals(2, next)
    }
}

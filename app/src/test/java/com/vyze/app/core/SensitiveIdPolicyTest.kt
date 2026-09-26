package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the SENSITIVE-ID privacy contract (2026-09-26).
 *
 * Device-session origin: a "berapa duit ini" answer described a banknote as
 * a "kad pengenalan" and spoke a serial aloud. Owner decision: banknote
 * serials stay readable, but identity-card / bank-card / account numbers
 * must NEVER be spoken — the refusal is a pinned per-language phrase.
 */
class SensitiveIdPolicyTest {

    // ── Detection: sensitive asks MUST trip ──────────────────────

    @Test
    fun `identity card and account asks are detected across languages`() {
        // English
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("read my ID number"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("what is the card number"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("read my MyKad"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("what is the account number"))
        // Malay
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("nombor kad pengenalan saya apa"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("baca nombor ic ini"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("nombor akaun kad ini"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("nombor atas kad ini berapa"))
        // Chinese
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("我的身份证号码是多少"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("读一下这个卡号"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("这个银行卡号是什么"))
    }

    @Test
    fun `detection is case and whitespace insensitive`() {
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("Read My ID NUMBER please"))
        assertTrue(SensitiveIdPolicy.isSensitiveIdQuery("KAD PENGENALAN nombor"))
    }

    // ── The carve-out: banknote serials stay READABLE ────────────

    @Test
    fun `banknote serial asks are explicitly allowed`() {
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("sebutkan nombor siri duit itu"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("what is the serial on this note"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("nombor siri wang kertas ini"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("读一下这张钞票的序列号"))
    }

    // ── Ordinary money / scene asks never trip ───────────────────

    @Test
    fun `ordinary currency and scene asks do not trigger the refusal`() {
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("berapa duit ini"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("ini duit apa"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("wang kertas ini apa"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("what is this"))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("这是什么钱"))
    }

    @Test
    fun `null blank and tap queries never trigger`() {
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery(null))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery(""))
        assertFalse(SensitiveIdPolicy.isSensitiveIdQuery("   "))
        assertFalse(
            SensitiveIdPolicy.isSensitiveIdQuery(
                "User tapped at position (512, 384) [Target location: center]"
            )
        )
    }

    // ── The pinned refusal phrase ────────────────────────────────

    @Test
    fun `refusal phrase is pinned per language with English fallback`() {
        val ms = SensitiveIdPolicy.refusalPhrase("ms")
        val zh = SensitiveIdPolicy.refusalPhrase("zh")
        val en = SensitiveIdPolicy.refusalPhrase("en")
        val unknown = SensitiveIdPolicy.refusalPhrase("ja")

        // Same sentence every time (pinned — TTS never improvises digits).
        assertEquals(ms, SensitiveIdPolicy.refusalPhrase("ms"))
        assertEquals(zh, SensitiveIdPolicy.refusalPhrase("zh"))

        // Prohibition is stated; an alternative (describe, not read) is offered.
        assertTrue(ms.contains("dilarang"))
        assertTrue(ms.contains("nombor kad pengenalan"))
        assertTrue(zh.contains("禁止"))
        assertTrue(zh.contains("身份证"))
        assertTrue(en.contains("prohibited"))
        assertTrue(en.contains("bank card number"))

        // Unknown languages fall back to English, never null.
        assertEquals(en, unknown)
    }
}

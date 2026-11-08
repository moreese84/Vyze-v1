package com.vyze.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the engine static prompt blocks ([VlmEngineManager]).
 *
 * The directive + perspective clause ride EVERY image inference (~2.6-6 ms
 * per prompt token on weak CPUs), so their sizes are latency budgets: the
 * ceilings trip on regrowth. The directive's anchor phrases must stay
 * verbatim because [ResponseCleanup]'s echo fingerprints substring-match
 * them — reword an anchor and a prompt recital leaks to TTS undetected.
 */
class EnginePromptBudgetTest {

    @Test
    fun `directive stays inside its prefill budget`() {
        // MEASURED at compression (2026-11-07): 564 chars (was 943).
        // Pin with headroom so only genuine regrowth trips it.
        val len = VlmEngineManager.SYSTEM_DIRECTIVE.length
        assertTrue("SYSTEM_DIRECTIVE=$len exceeds prefill budget", len <= 650)
    }

    @Test
    fun `echo-marker anchors stay verbatim in the directive`() {
        // Substring match on the lowercased directive, mirroring
        // ResponseCleanup.isInstructionEcho's matching.
        val lower = VlmEngineManager.SYSTEM_DIRECTIVE.lowercase()
        assertTrue("persona anchor lost", lower.contains("you are vyze,"))
        assertTrue("audience anchor lost", lower.contains("speaking aloud to a blind user"))
        assertTrue("length anchor lost", lower.contains("answer in 1 short spoken sentence"))
    }

    @Test
    fun `every compressed rule clause is present`() {
        // Compression keeps all nine rules — one terse clause each. If a
        // future edit drops a rule here, the prompt layer must provably
        // carry it on every lane first (narration strips most blocks).
        val d = VlmEngineManager.SYSTEM_DIRECTIVE
        assertTrue("lead rule lost", d.contains("leading with what was asked"))
        assertTrue("echo ban lost", d.contains("never repeat, echo, or quote"))
        assertTrue("spatial rule lost", d.contains("only when asked WHERE"))
        assertTrue("OCR carve-out lost", d.contains("OCR EXCEPTION"))
        assertTrue("plain-text rule lost", d.contains("Plain spoken text only"))
        assertTrue("follow-up rule lost", d.contains("only what is new"))
        assertTrue("language rule lost", d.contains("[OUTPUT LANGUAGE]"))
    }

    @Test
    fun `perspective clauses stay inside their prefill budgets`() {
        // MEASURED at compression (2026-11-07): EN 214 (was 301),
        // MS 241 (was 304), ZH 64 (was 105). One renders per turn.
        val en = VlmEngineManager.PERSPECTIVE_EN.length
        val ms = VlmEngineManager.PERSPECTIVE_MS.length
        val zh = VlmEngineManager.PERSPECTIVE_ZH.length
        assertTrue("PERSPECTIVE_EN=$en exceeds budget", en <= 250)
        assertTrue("PERSPECTIVE_MS=$ms exceeds budget", ms <= 280)
        assertTrue("PERSPECTIVE_ZH=$zh exceeds budget", zh <= 80)
    }

    @Test
    fun `every perspective keeps all four rules`() {
        // Compression keeps: user-relative frame, WHERE-only spatial +
        // object-first, varied openings, you-addressing.
        val en = VlmEngineManager.PERSPECTIVE_EN
        assertTrue("en frame lost", en.contains("relative to the USER"))
        assertTrue("en spatial lost", en.contains("only for WHERE questions"))
        assertTrue("en variety lost", en.contains("Vary sentence openings"))
        assertTrue("en addressing lost", en.contains("never 'the user'"))
        val ms = VlmEngineManager.PERSPECTIVE_MS
        assertTrue("ms frame lost", ms.contains("relatif kepada PENGGUNA"))
        assertTrue("ms spatial lost", ms.contains("hanya untuk soalan DI MANA"))
        assertTrue("ms variety lost", ms.contains("Variasikan permulaan ayat"))
        assertTrue("ms addressing lost", ms.contains("jangan sebut 'pengguna'"))
        val zh = VlmEngineManager.PERSPECTIVE_ZH
        assertTrue("zh frame lost", zh.contains("以用户为基准"))
        assertTrue("zh spatial lost", zh.contains("只在问位置时提方位"))
        assertTrue("zh variety lost", zh.contains("句式开头要多样"))
        assertTrue("zh addressing lost", zh.contains("只用'你'"))
    }
}

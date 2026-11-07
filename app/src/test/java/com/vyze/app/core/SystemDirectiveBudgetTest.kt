package com.vyze.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the engine system directive ([VlmEngineManager.SYSTEM_DIRECTIVE]).
 *
 * The directive rides EVERY image inference (~2.6-6 ms per prompt token
 * on weak CPUs), so its size is a latency budget: the ceiling trips on
 * regrowth, and the anchor phrases must stay verbatim because
 * [ResponseCleanup]'s echo fingerprints substring-match them — reword an
 * anchor and a prompt recital leaks to TTS undetected.
 */
class SystemDirectiveBudgetTest {

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
}

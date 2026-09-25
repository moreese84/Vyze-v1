package com.vyze.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AsrPromptPolicy] — the model-native ASR instruction
 * contract. Pins the 2026-09-25 device regression: the old prompt hard-pinned
 * ONE output language ("in $langName into $langName text", from the active UI
 * locale), so Chinese speech after a ms-MY ladder retry was ORDERED into
 * Malay text ("Menganalisis ini, ini apa"). The transcriber must always be
 * allowed to output the language actually spoken.
 */
class AsrPromptPolicyTest {

    private val en = "English"
    private val ms = "Bahasa Malaysia"
    private val zh = "Mandarin Chinese"

    @Test
    fun `neutral prompt names all supported languages`() {
        val p = AsrPromptPolicy.buildTranscribePrompt(null)
        assertTrue(p.contains(en))
        assertTrue(p.contains(ms))
        assertTrue(p.contains(zh))
        assertTrue(p.contains("language actually spoken"))
    }

    @Test
    fun `hinted prompt still allows the other supported languages`() {
        // The regression case: UI locale is ms-MY, user speaks Chinese.
        val p = AsrPromptPolicy.buildTranscribePrompt("Malay")
        // The hint is ordered as LIKELY — never as the ONLY choice.
        assertTrue(p.contains("most likely in Malay"))
        assertTrue(p.contains("even if that differs from Malay"))
        // The other languages remain permitted outputs.
        assertTrue(p.contains(en))
        assertTrue(p.contains(zh))
    }

    @Test
    fun `no pinned-language form remains`() {
        // The old bug's exact shape — "in X into X text" — must never return.
        for (hint in listOf(null, "Malay", "Chinese", "English")) {
            val p = AsrPromptPolicy.buildTranscribePrompt(hint)
            assertFalse("pinned form returned for hint=$hint", p.contains(" into "))
        }
    }

    @Test
    fun `formatting constraints survive the rewrite`() {
        val p = AsrPromptPolicy.buildTranscribePrompt("Chinese")
        assertTrue(p.contains("no newlines"))
        assertTrue(p.contains("no markdown"))
        assertTrue(p.contains("write the digits"))
    }

    @Test
    fun `blank hint behaves as neutral`() {
        val p = AsrPromptPolicy.buildTranscribePrompt("   ")
        assertFalse(p.contains("most likely in"))
        assertTrue(p.contains("language actually spoken"))
    }
}

package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the brain-swap turn-format contract (swap plan M1.2).
 *
 * The MiniCPM bundle carries its OWN ChatML template — the engine must hand
 * it plain content (no Gemma role tags) while keeping the SAME anti-echo
 * delimiter contract that the Gemma builder's echo bug fixed: labeled
 * history inside the payload, the CURRENT query always last.
 */
class BrainTurnFormatTest {

    // ── dispatcher routes per brain ───────────────────────────────

    @Test
    fun `buildTurnPrompt routes MINICPM to the plain ChatML form`() {
        val out = VlmEngineManager.buildTurnPrompt(
            BrainModel.MINICPM_V47_1B,
            "What is in front of me?",
            "You are Vyze."
        )
        // Bundle's template renders roles — we must NEVER emit Gemma tags.
        assertFalse(out.contains("<start_of_turn>"))
        assertFalse(out.contains("<end_of_turn>"))
        assertFalse(out.contains("<|im_start|>"))
        assertTrue(out.startsWith("You are Vyze.\n\n"))
        assertTrue(out.endsWith("What is in front of me?"))
    }

    @Test
    fun `buildTurnPrompt routes GEMMA to the role-tagged form`() {
        val out = VlmEngineManager.buildTurnPrompt(
            BrainModel.GEMMA_4E2B,
            "What is in front of me?",
            "You are Vyze."
        )
        assertTrue(out.startsWith("<start_of_turn>user\n"))
        assertTrue(out.endsWith("\n<start_of_turn>model\n"))
    }

    // ── miniCPM plain form contract ───────────────────────────────

    @Test
    fun `miniCPM system prompt is prepended and blank lines separate it`() {
        val out = VlmEngineManager.companionBuildChatTurnPrompt(
            "Read this label",
            "Directive line"
        )
        assertEquals("Directive line\n\nRead this label", out)
    }

    @Test
    fun `miniCPM no system prompt means no leading separator`() {
        val out = VlmEngineManager.companionBuildChatTurnPrompt("What is this?")
        assertEquals("What is this?", out)
    }

    @Test
    fun `miniCPM history is labeled and the current query stays LAST`() {
        val out = VlmEngineManager.companionBuildChatTurnPrompt(
            userContent = "Is the box still there?",
            systemPrompt = "SYS",
            conversationTurns = listOf(
                "Where is my bag?" to "It is on the table.",
                "What color is it?" to "Blue."
            )
        )
        assertTrue(out.contains("User: Where is my bag?\nYou answered: It is on the table."))
        assertTrue(out.contains("User: What color is it?\nYou answered: Blue."))
        // The anti-echo invariant: generation must begin with the model's own
        // answer, so the current query is the final line of the payload.
        val tail = out.substringAfterLast("You answered: Blue.")
        assertEquals("\n\nIs the box still there?", tail)
    }

    @Test
    fun `miniCPM trailing whitespace of user content is trimmed`() {
        val out = VlmEngineManager.companionBuildChatTurnPrompt("Tail spaces   ")
        assertEquals("Tail spaces", out)
    }
}

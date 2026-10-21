package com.vyze.app.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the ANCHOR-ERA PARROT detector (2026-10-20).
 *
 * Ground truth: the strings matched here are the exact inline examples the
 * old prompt shipped (en/ms/zh), verified against git history of
 * DynamicPromptBuilder. Stored outputs containing them are provably
 * template parroting and are purged from Room memory at startup — on the
 * user's device such a row (record 527, "That is a dark blue shirt.")
 * outlived the prompt fix by one hop through the database and bled
 * "dark-colored" into a fresh answer via Prior-scene-memory injection.
 */
class AnchorEraParrotTest {

    // ── English anchors ─────────────────────────────────────────

    @Test
    fun `dark blue shirt era answer is detected`() {
        // The exact regression row pulled from the device DB (record 527).
        assertTrue(AnchorEraParrot.isAnchorEraParrot("That is a dark blue shirt."))
        // Case-insensitive: model output varies capitalization freely.
        assertTrue(AnchorEraParrot.isAnchorEraParrot("That is a Dark Blue Shirt you see."))
    }

    @Test
    fun `olive oil era answers are detected`() {
        assertTrue(AnchorEraParrot.isAnchorEraParrot("That is a bottle of olive oil."))
        assertTrue(AnchorEraParrot.isAnchorEraParrot("It is a green-lidded bottle of olive oil on the table."))
    }

    // ── Malay anchors (six-language example lists) ──────────────

    @Test
    fun `malay anchor translations are detected`() {
        assertTrue(AnchorEraParrot.isAnchorEraParrot("Itu ialah sebotol minyak zaitun."))
        assertTrue(AnchorEraParrot.isAnchorEraParrot("Itu baju biru gelap."))
    }

    // ── Chinese anchors ─────────────────────────────────────────

    @Test
    fun `chinese anchor translations are detected`() {
        assertTrue(AnchorEraParrot.isAnchorEraParrot("那是一瓶橄榄油。"))
        assertTrue(AnchorEraParrot.isAnchorEraParrot("那是一件深蓝色衬衫。"))
    }

    // ── Genuine scene descriptions must survive ─────────────────

    @Test
    fun `genuine scene answers are never purged`() {
        // The fresh-build answers from the user's retest — scene-bound,
        // abstract-template phrasing, no anchor object.
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a pair of dark-colored shoes you see in front of you."))
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a pink basket you actually see."))
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a brown door with a silver knob. Text is unclear."))
        // "dark blue" alone is not an anchor — the shirt phrase is what the
        // prompt shipped; a real blue object must stay memory-eligible.
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a dark blue backpack on the chair."))
    }

    // ── Bracket-template echo era (fix #5, 2026-10-21) ───────────

    @Test
    fun `bracket placeholder echoes are detected - device records 536-537`() {
        // The exact rows the user's retest stored after the anchor fix:
        // with the concrete examples gone, the model completed the then-
        // quoted placeholder template verbatim — the same parrot class
        // one era later. Empty feedback, so marks alone could not catch
        // them — this detector plus the injection filter did.
        assertTrue(AnchorEraParrot.isAnchorEraParrot("That is a [color] [object] you actually see."))
        assertTrue(AnchorEraParrot.isAnchorEraParrot("It says [printed text]."))
    }

    @Test
    fun `object-word answers without brackets are never purged`() {
        // Matching delegates to SuspectMarker.isTemplateEcho — bracketed
        // placeholders only. Bare "object" in a scene answer is not echo.
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a red and blue object on the table."))
        assertFalse(AnchorEraParrot.isAnchorEraParrot("That is a pink basket you actually see."))
    }

    // ── Degenerate inputs ───────────────────────────────────────

    @Test
    fun `null blank and empty outputs are never purged`() {
        assertFalse(AnchorEraParrot.isAnchorEraParrot(null))
        assertFalse(AnchorEraParrot.isAnchorEraParrot(""))
        assertFalse(AnchorEraParrot.isAnchorEraParrot("   "))
    }
}

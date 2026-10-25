package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM pins for the post-generation response cleanup (bug fixes: spoken
 * `[OUTPUT LANGUAGE]` tag leak, "...Text is unclear." stapled onto real
 * answers, and the cross-answer template-opener dedupe — live evidence
 * 2026-10-25).
 */
class ResponseCleanupTest {

    // ── stripPromptTags ────────────────────────────────────────────

    @Test
    fun `stripPromptTags removes an echoed OUTPUT LANGUAGE tag prefix`() {
        assertEquals(
            "A circular object sits on a white surface.",
            ResponseCleanup.stripPromptTags(
                "[OUTPUT LANGUAGE: English] A circular object sits on a white surface."
            )
        )
    }

    @Test
    fun `stripPromptTags removes template placeholders`() {
        assertEquals(
            "The shirt is dark blue.",
            ResponseCleanup.stripPromptTags("The shirt is [color] [object].".replace("is [color] [object]", "is dark blue"))
        )
        assertEquals(
            "The shirt is dark blue",
            ResponseCleanup.stripPromptTags("[color] The shirt is dark blue")
        )
    }

    @Test
    fun `stripPromptTags leaves ordinary text and punctuation untouched`() {
        val plain = "A black chair is at the right. Its legs are metal, size 40cm."
        assertEquals(plain, ResponseCleanup.stripPromptTags(plain))
    }

    @Test
    fun `stripPromptTags leaves CJK content untouched`() {
        val plain = "桌子上有一个盒子。它是棕色的。"
        assertEquals(plain, ResponseCleanup.stripPromptTags(plain))
    }

    @Test
    fun `stripPromptTags collapses the gap the tag leaves`() {
        assertEquals(
            "Stairs lead upward to the left.",
            ResponseCleanup.stripPromptTags("[OUTPUT LANGUAGE: English]   Stairs lead upward to the left.")
        )
    }

    // ── stripRefusalTail ───────────────────────────────────────────

    @Test
    fun `stripRefusalTail drops a trailing canned refusal when content precedes`() {
        assertEquals(
            "A silver refrigerator stands at Bottom-Center of the camera frame.",
            ResponseCleanup.stripRefusalTail(
                "A silver refrigerator stands at Bottom-Center of the camera frame. Text is unclear."
            )
        )
    }

    @Test
    fun `stripRefusalTail keeps a wholesale refusal intact`() {
        val refusal = "Text is unclear."
        assertEquals(refusal, ResponseCleanup.stripRefusalTail(refusal))
    }

    @Test
    fun `stripRefusalTail drops a trailing Malay refusal`() {
        assertEquals(
            "Kotak itu berwarna coklat di atas meja.",
            ResponseCleanup.stripRefusalTail("Kotak itu berwarna coklat di atas meja. Teks tidak jelas.")
        )
    }

    @Test
    fun `stripRefusalTail keeps a refusal that is not the tail position`() {
        // Whole-output matching is the memory layer's job; here only the
        // LAST sentence is considered, and only when content follows it.
        val text = "Text is unclear at first, but the box says Deli Fresh. Count 12."
        assertEquals(text, ResponseCleanup.stripRefusalTail(text))
    }

    @Test
    fun `stripRefusalTail is a no-op on blank input`() {
        assertEquals("", ResponseCleanup.stripRefusalTail(""))
    }

    // ── clean ──────────────────────────────────────────────────────

    @Test
    fun `clean applies tags and refusal tail together`() {
        assertEquals(
            "The stairs go up to the second floor.",
            ResponseCleanup.clean(
                "[OUTPUT LANGUAGE: English] The stairs go up to the second floor. No text visible."
            )
        )
    }

    // ── opener helpers (stream-side dedupe) ────────────────────────

    @Test
    fun `openerOf normalizes punctuation case and tags`() {
        val a = ResponseCleanup.openerOf("[OUTPUT LANGUAGE: English] A circular object sits on a white surface.")
        val b = ResponseCleanup.openerOf("A circular object sits on a white surface!")
        assertEquals(a, b)
        assertEquals("a circular object sits on a white surface", a)
    }

    @Test
    fun `openerOf differs for genuinely different openers`() {
        assertTrue(
            ResponseCleanup.openerOf("A black chair is at the right.") !=
                ResponseCleanup.openerOf("A silver refrigerator stands at the left.")
        )
    }

    @Test
    fun `removeFirstSentence returns the remainder`() {
        assertEquals(
            "It is made of plastic.",
            ResponseCleanup.removeFirstSentence("A circular object sits here. It is made of plastic.")
        )
    }

    @Test
    fun `removeFirstSentence returns the original for a single-sentence response`() {
        // Dedupe must never silence an answer that is one repeated sentence.
        val one = "A circular object sits on a white surface."
        assertEquals(one, ResponseCleanup.removeFirstSentence(one))
    }
}

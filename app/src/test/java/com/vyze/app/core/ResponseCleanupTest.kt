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

    // ── stripInstructionEcho (prompt-echo fix, run 2 live evidence) ──

    @Test
    fun `stripInstructionEcho drops the echoed language anchor line`() {
        assertEquals(
            "A red car is parked beside the wall.",
            ResponseCleanup.stripInstructionEcho(
                "Write every word of your response in English. " +
                    "Do not continue in any other language. " +
                    "A red car is parked beside the wall."
            )
        )
    }

    @Test
    fun `stripInstructionEcho drops the Malay anchor tail sentences`() {
        assertEquals(
            "Kotak itu di atas meja.",
            ResponseCleanup.stripInstructionEcho(
                "Do NOT use English. Do NOT translate. This is mandatory. " +
                    "Kotak itu di atas meja."
            )
        )
    }

    @Test
    fun `stripInstructionEcho drops a verbatim language-mirror and anti-echo recital`() {
        // The 301-char run-2 response, bracket tag included.
        val recital =
            "[OUTPUT LANGUAGE: English] Write every word of your response in English. " +
                "Do not continue in any other language. " +
                "LANGUAGE MIRRORING: Respond in the language named in the " +
                "[OUTPUT LANGUAGE] tag — the tag is the authority on the answer " +
                "language. Even when the query text itself reads like English, the " +
                "tag names the user's actual spoken language — answer in the tag's " +
                "language. If no tag is present, detect the language of the user's " +
                "query and respond strictly in that exact same language. Never " +
                "revert to default English if the user speaks another language. " +
                "CRITICAL: NEVER repeat, echo, or quote the user's query or question " +
                "at the start of your response. Begin immediately with the direct " +
                "description or answer. REMEMBER: Respond only in English. " +
                "Begin immediately with the answer itself — never repeat or echo the " +
                "user's question. You are Vyze, a fast, friendly visual assistant " +
                "speaking aloud to a blind user. Answer in 1 short spoken sentence."
        assertEquals("", ResponseCleanup.stripInstructionEcho(recital))
    }

    @Test
    fun `stripInstructionEcho matches curly apostrophes the model normalizes to`() {
        assertEquals(
            "A blue cup sits here.",
            ResponseCleanup.stripInstructionEcho(
                "Never repeat or echo the user’s question. A blue cup sits here."
            )
        )
    }

    @Test
    fun `stripInstructionEcho leaves a normal scene answer untouched`() {
        val answer =
            "A circular object sits on a white surface. It is made of plastic, " +
                "about 10cm wide, in front of you."
        assertEquals(answer, ResponseCleanup.stripInstructionEcho(answer))
    }

    @Test
    fun `stripInstructionEcho is a no-op on blank input`() {
        assertEquals("", ResponseCleanup.stripInstructionEcho(""))
    }

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
    fun `clean strips an echoed instruction block that wraps real content`() {
        assertEquals(
            "A red car is parked beside the wall.",
            ResponseCleanup.clean(
                "[OUTPUT LANGUAGE: English] Write every word of your response in English. " +
                    "Do not continue in any other language. " +
                    "A red car is parked beside the wall."
            )
        )
    }

    @Test
    fun `clean empties a response that is only instruction prose`() {
        // Contract pin for the controller's PROMPT-ECHO NET: an all-echo
        // response cleans to "" so the caller speaks the localized
        // fallback instead of reciting the prompt — and never goes silent.
        val echoOnly =
            "Write every word of your response in English. " +
                "CRITICAL: NEVER repeat, echo, or quote the user's query or " +
                "question at the start of your response."
        assertEquals("", ResponseCleanup.clean(echoOnly))
    }

    @Test
    fun `firstSentence skips echoed instructions to reach the real opener`() {
        assertEquals(
            "A red car is parked beside the wall.",
            ResponseCleanup.firstSentence(
                "[OUTPUT LANGUAGE: English] Write every word of your response in English. " +
                    "A red car is parked beside the wall. It is dusty."
            )
        )
    }

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

    // ── verbatim block match (live leak 2026-10-26 08:30:24) ────

    /**
     * The three DIRECT_RULES_PROSE sentences that REACHED TTS verbatim on
     * the new build this morning: 941-char response, markers/tags stripped
     * 720 chars, the remaining 221 chars of rule prose were spoken (user
     * report: "it still speak the prompt"). No fingerprint matched them —
     * the block match exists because of exactly this.
     */
    private val liveLeakTriple =
        "Answer directly in the first sentence. " +
            "Write in complete, natural spoken sentences with a subject and a verb — NEVER a " +
            "list of attributes separated by commas. " +
            "Name the object and give compact, useful details — size, color."

    @Test
    fun `the three rule-prose sentences that leaked to TTS are dropped`() {
        assertEquals("", ResponseCleanup.stripInstructionEcho(liveLeakTriple))
        assertEquals("", ResponseCleanup.clean(liveLeakTriple))
    }

    @Test
    fun `a rule sentence truncated at a clause still matches its block`() {
        // The live third sentence stopped at "— size, color." while the
        // block continues "…, material, state …" — equals/contains on the
        // full string would miss it; symmetric substring must not.
        assertEquals(
            "",
            ResponseCleanup.stripInstructionEcho(
                "Name the object and give compact, useful details — size, color."
            )
        )
    }

    @Test
    fun `a tag-wrapped rule recital is dropped on the pre-tag-strip TTS path`() {
        // sanitizeForTts runs the echo strip BEFORE tag stripping (streamed
        // chunks); clean() runs it after — both must see the same match, so
        // the block side strips tags inside its normalization too.
        assertEquals(
            "",
            ResponseCleanup.stripInstructionEcho(
                "[OUTPUT LANGUAGE: English] Respond with the answer only: never repeat, " +
                    "echo, or quote the question, never restate the task, and write every " +
                    "word in the language named in the [OUTPUT LANGUAGE] tag."
            )
        )
    }

    @Test
    fun `every static prompt rule sentence is dropped by the verbatim block match`() {
        // Coverage pin: any rule sentence ≥ the match floor must be
        // silenceable on its own — if a block grows a sentence the net
        // cannot catch, this fails instead of a device run discovering it.
        // Mirrors ResponseCleanup's split + normalization with public API.
        val split = Regex("(?<=[.!?。！？；;])\\s+")
        val kept = mutableListOf<String>()
        var dropped = 0
        DynamicPromptBuilder.STATIC_INSTRUCTION_BLOCKS
            .flatMap { it.split(split) }
            .map {
                ResponseCleanup.stripPromptTags(it).lowercase()
                    .replace('\u2019', '\'')
                    .replace(Regex("\\s+"), " ")
                    .trim { c -> c.isWhitespace() || c in ".,!?;:„\"'“”‘’…。！？；：、" }
            }
            .filter { it.length >= 30 }
            .forEach { sentence ->
                if (ResponseCleanup.stripInstructionEcho(sentence).isEmpty()) {
                    dropped++
                } else {
                    kept += sentence
                }
            }
        assertTrue("rule sentences still reaching TTS: $kept", kept.isEmpty())
        assertTrue("coverage too thin: only $dropped rule sentences checked", dropped >= 40)
    }

    @Test
    fun `a wholesale canned refusal inside the rules is never echo-dropped`() {
        // "I cannot identify this card clearly." is a substring of
        // BANK_CARD_RULES — the block match must not eat an honest refusal
        // that IS the whole answer (NonAnswerRefusal's contract).
        val refusal = "I cannot identify this card clearly."
        assertEquals(refusal, ResponseCleanup.stripInstructionEcho(refusal))
        assertEquals(refusal, ResponseCleanup.clean(refusal))
    }

    @Test
    fun `the reconstructed live response cleans to empty so the fallback speaks`() {
        // Run-3 reconstruction of the 08:30:24 response: real opener glued
        // to the anchor, the mirror block, and the rule-prose triple.
        // Contract: cleans to "" → controller's PROMPT-ECHO NET speaks the
        // localized fallback — never a line of the prompt.
        val response =
            "White[OUTPUT LANGUAGE: English] Write every word of your response in English. " +
                "Do not continue in any other language. " +
                "LANGUAGE MIRRORING: Respond in the language named in the " +
                "[OUTPUT LANGUAGE] tag — the tag is the authority on the answer language. " +
                liveLeakTriple
        assertEquals("", ResponseCleanup.clean(response))
    }
}

package com.vyze.app.core

import com.vyze.app.memory.NonAnswerRefusal

/**
 * POST-GENERATION RESPONSE CLEANUP — the structural layer between what the
 * model produced and what is spoken / stored / fed back into memory.
 *
 * Device-evidenced failures it exists for (live session 2026-10-25):
 *  - the model echoed the prompt's `[OUTPUT LANGUAGE: English]` tag and it
 *    was SPOKEN aloud and stored with the interaction (bug: tag leak);
 *  - a scene answer was stapled with a trailing canned refusal
 *    (`"...Text is unclear."`) — a refusal carries zero scene fact and must
 *    not ride along on a real answer;
 *  - consecutive answers opened with the identical template sentence
 *    (`"A circular object sits on a white surface."` twice) — deduped
 *    stream-side by the controller via [openerOf] + [removeFirstSentence].
 *
 * Refusal detection reuses [NonAnswerRefusal] so TTS, memory and this layer
 * can never disagree about what a canned refusal is.
 *
 * CONTRACT: PURE — strings in, strings out, no Android, no state.
 * JVM-testable ([ResponseCleanupTest]).
 */
object ResponseCleanup {

    /**
     * Prompt scaffolding the model reads back verbatim: `[OUTPUT LANGUAGE:
     * English]`, the `[color]`/`[object]` template placeholders, etc.
     * Bounded (≤120 chars, no nesting) so a legit bracketed aside in
     * unusual output cannot swallow the rest of the answer.
     */
    private val PROMPT_TAG_REGEX = Regex("\\[[^\\[\\]]{0,120}]")

    /** Sentence boundary: Latin + CJK terminators followed by whitespace. */
    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?。！？；;])\\s+")

    /** Remove echoed `[...]` prompt tags; collapse the gaps they leave. */
    fun stripPromptTags(text: String): String =
        text.replace(PROMPT_TAG_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Drop a trailing canned-refusal sentence ("Text is unclear.") ONLY when
     * real content precedes it — a response that IS the refusal wholesale is
     * an honest answer and is returned untouched.
     */
    fun stripRefusalTail(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val sentences = trimmed.split(SENTENCE_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size >= 2 && NonAnswerRefusal.isNonAnswer(sentences.last())) {
            return sentences.dropLast(1).joinToString(" ")
        }
        return trimmed
    }

    /** Full storage/dialogue cleanup: prompt tags + trailing refusal tail. */
    fun clean(text: String): String = stripRefusalTail(stripPromptTags(text))

    /** First sentence of [text] as spoken (tag-stripped), verbatim. */
    fun firstSentence(text: String): String =
        firstSentenceOrNull(stripPromptTags(text)) ?: ""

    /**
     * Normalized opener key for cross-answer comparison: first sentence,
     * lowercased, letters/digits only. Both sides of the dedupe comparison
     * MUST go through this (punctuation/spacing/tag differences are noise).
     */
    fun openerOf(text: String): String {
        val sentence = firstSentence(text)
        if (sentence.isEmpty()) return ""
        return sentence.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    /** First sentence of an already tag-stripped string, or null. */
    private fun firstSentenceOrNull(text: String): String? =
        text.trim().split(SENTENCE_BOUNDARY).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Everything after the first sentence — the caller's dedupe only applies
     * it when the result is non-blank, so a response that IS one repeated
     * sentence can never be silenced by this.
     */
    fun removeFirstSentence(text: String): String {
        val sentences = text.trim().split(SENTENCE_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size < 2) return text
        return sentences.drop(1).joinToString(" ")
    }
}

package com.vyze.app.core

import com.vyze.app.memory.NonAnswerRefusal

/**
 * POST-GENERATION RESPONSE CLEANUP — the structural layer between what the
 * model produced and what is spoken / stored / fed back into memory.
 *
 * Device-evidenced failures it exists for (live session 2026-10-25):
 *  - the model echoed the prompt's `[OUTPUT LANGUAGE: English]` tag and it
 *    was SPOKEN aloud and stored with the interaction (bug: tag leak);
 *  - the model recited the prompt's INSTRUCTION BLOCK verbatim as its
 *    answer (run 2, 4 occurrences: 301-char and 944-char responses that
 *    were the language-anchor line + LANGUAGE_MIRRORING_MANDATE +
 *    ANTI_ECHO_DIRECTIVE, spoken 14:45:10–14:45:20). The bracket tag was
 *    stripped, but the instruction prose around it is not a bracket — it
 *    reached TTS (see [stripInstructionEcho]);
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

    /**
     * INSTRUCTION-ECHO FINGERPRINTS — verbatim fragments of the PROMPT's
     * own instruction block, matched (lowercased) as SUBSTRINGS of a
     * sentence. A sentence containing one of these can only be the model
     * quoting its instructions; no scene answer or user-facing sentence
     * ever contains them (live evidence 2026-10-25 run 2: four responses
     * were these blocks recited verbatim, 301 and 944 chars).
     *
     * Every marker is anchored to a distinctive phrase so an ordinary
     * answer cannot match — broad words ("language", "answer") never
     * appear alone here.
     */
    private val INSTRUCTION_ECHO_MARKERS = listOf(
        // [OUTPUT LANGUAGE] anchor line — English and Malay forms.
        "write every word of your response",
        "do not continue in any other language",
        "do not use english",
        "do not translate",
        "this is mandatory",
        // LANGUAGE_MIRRORING_MANDATE.
        "language mirroring:",
        "the tag is the authority on the answer language",
        "the tag names the user's actual spoken language",
        "answer in the tag's language",
        "detect the language of the user's query",
        "respond strictly in that exact same language",
        "never revert to default english",
        // ANTI_ECHO_DIRECTIVE / TASK_OUTPUT_CONTRACT / bottom REMEMBER line.
        "critical: never repeat, echo, or quote",
        "begin immediately with the direct description",
        "begin immediately with the answer itself",
        "never repeat or echo the user's question",
        "respond with the answer only",
        "never restate the task",
        "remember: respond only in",
        // Engine/agent SYSTEM_DIRECTIVE recital.
        "you are vyze,",
        "speaking aloud to a blind user",
        "answer in 1 short spoken sentence",
        "answer the user's question directly in 1 short spoken sentence",
    )

    /** Remove echoed `[...]` prompt tags; collapse the gaps they leave. */
    fun stripPromptTags(text: String): String =
        text.replace(PROMPT_TAG_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** True when this sentence is prompt-instruction prose, not an answer. */
    private fun isInstructionEcho(sentence: String): Boolean {
        // Curly apostrophes: the model normalizes ' to ’ on some tokens,
        // which would otherwise defeat every marker carrying an apostrophe.
        val normalized = sentence.lowercase()
            .replace('\u2019', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
        return INSTRUCTION_ECHO_MARKERS.any { normalized.contains(it) }
    }

    /**
     * Drop sentences that recite the prompt's instruction block; collapse
     * the gaps. A response that is NOTHING but instruction prose becomes
     * empty — the caller's echo-net (VyzeCoreController.onComplete) is
     * responsible for speaking a localized fallback instead of going
     * silent. Pure, idempotent, sentence-aligned: streamed chunks are cut
     * on sentence boundaries, so every instruction sentence arrives whole.
     */
    fun stripInstructionEcho(text: String): String =
        text.split(SENTENCE_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isInstructionEcho(it) }
            .joinToString(" ")
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

    /**
     * Full storage/dialogue cleanup: prompt tags + echoed instruction block
     * + trailing refusal tail.
     */
    fun clean(text: String): String =
        stripRefusalTail(stripInstructionEcho(stripPromptTags(text)))

    /**
     * First sentence of [text] as SPOKEN (tag-stripped, instruction echo
     * dropped), verbatim — the opener key must describe what actually
     * reached the ear, so a response that opens with echoed instructions
     * compares by its first real answer sentence.
     */
    fun firstSentence(text: String): String =
        firstSentenceOrNull(stripInstructionEcho(stripPromptTags(text))) ?: ""

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

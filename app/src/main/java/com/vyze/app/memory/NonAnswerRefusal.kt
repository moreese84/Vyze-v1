package com.vyze.app.memory

/**
 * Detects CANNED REFUSAL / NON-ANSWER outputs — the prompt's own pinned
 * never-guess fallbacks ("Text is unclear.", "No text visible",
 * "Teksnya tidak jelas.", "文字不清楚。") in all three languages.
 *
 * MEMORY-POISONING GUARD (2026-10-21, device-evidenced): those refusals
 * were stored like any other interaction, and similarity search then
 * re-injected "Text is unclear." as "Prior scene memory" into the very next
 * prompt — the next answer repeated the refusal (session 10:19:45 →
 * 10:19:55, two of the three "Text is unclear." rows in a row). A refusal
 * carries ZERO scene fact; teaching the model its own past refusal as scene
 * fact anchors it on refusing again — the same one-hop-through-memory
 * poisoning the anchor-era purge exists for, one era later.
 *
 * Matching is WHOLE-OUTPUT equality after normalization (lowercase, outer
 * punctuation stripped), never substring: a genuine scene answer that
 * merely TAILS with "...its exact name is not clearly visible" still carries
 * scene facts and must stay memory-eligible (few-shots produce exactly that
 * shape).
 *
 * NOT a suspect mark and NOT purged: a refusal is an honest utterance —
 * useful as dialogue history and as corpus evidence. It is excluded only
 * from the prior-scene-memory channels (visual similarity injection and
 * spoken text recall) in [MemoryRepository].
 *
 * CONTRACT: PURE — strings in, decision out, no Android, no state.
 * JVM-testable ([NonAnswerRefusalTest]).
 */
object NonAnswerRefusal {

    /**
     * The canned fallbacks pinned in DynamicPromptBuilder — the English
     * quoted forms plus their [failurePhraseClauseFor]-pinned ms/zh
     * counterparts and the localized few-shot spellings.
     */
    private val REFUSALS: Set<String> = setOf(
        // en — DIRECT/CURRENCY/BANK_CARD/TEXT_ONLY rules + few-shots
        "text is unclear",
        "the text is unclear",
        "no text visible",
        "not clearly visible",
        "i cannot read this clearly",
        "i cannot identify this card clearly",
        "i do not know that",
        // ms — FAILURE-PHRASE OVERRIDE + few-shots
        "teks tidak jelas",
        "teksnya tidak jelas",
        "tiada teks kelihatan",
        "tidak kelihatan dengan jelas",
        "saya tidak dapat membaca nilai ini dengan jelas",
        "saya tidak dapat mengenal pasti kad ini dengan jelas",
        "saya tidak tahu tentang itu",
        // zh — FAILURE-PHRASE OVERRIDE + few-shots
        "文字不清楚",
        "看不到文字",
        "看不清楚",
        "我读不清楚",
        "我无法清楚辨认这张卡",
        "我不知道",
    )

    /**
     * True when [output] IS (a punctuated spelling of) a canned refusal —
     * i.e. it answers nothing about the scene and must never be injected or
     * spoken back as prior memory. Whole-output match only.
     */
    fun isNonAnswer(output: String?): Boolean {
        if (output.isNullOrBlank()) return false
        val normalized = output.lowercase()
            .trim { it.isWhitespace() || it in ".,!?;:„\"'“”‘’…。！？；：、" }
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return false
        return normalized in REFUSALS
    }
}

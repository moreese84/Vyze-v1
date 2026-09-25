package com.vyze.app.core

/**
 * Pure builder for the model-native ASR instruction prompt. Extracted from
 * [VyzeCoreController.transcribeAudio] so the contract is JVM-testable.
 *
 * ## The bug this fixes (device log, 2026-09-25)
 *
 * The old prompt pinned ONE language: "Transcribe the following speech
 * segment in $langName into $langName text", where $langName came from
 * [activeUserLocale] — typically ms-MY after a locale-ladder retry. A user
 * speaking MANDARIN into that prompt was ordered to produce MALAY text, and
 * the obedient model complied: "Menganalisis ini, ini apa" for Chinese
 * speech. The audio encoder heard fine; the instruction straitjacketed the
 * output. detectLocaleFromText then correctly read the Malay text as ms —
 * and the whole downstream pipeline answered in the wrong language.
 *
 * ## Contract
 *
 *  - The transcriber outputs THE LANGUAGE ACTUALLY SPOKEN, choosing among
 *    Vyze's supported recognition languages (English, Bahasa Malaysia,
 *    Mandarin Chinese) — never a hard pin to the active UI locale.
 *  - Formatting constraints stay identical to the proven original: plain
 *    single-line text, digits for numbers.
 */
object AsrPromptPolicy {

    /** The languages the transcriber may output, as instruction words. */
    val SUPPORTED_SPOKEN_LANGUAGES: List<String> = listOf(
        "English",
        "Bahasa Malaysia",
        "Mandarin Chinese",
    )

    /**
     * The ASR instruction. Pure — same input, same output.
     *
     * @param langHint optional display-language name (e.g. "Malay") to
     *        ORDER the model toward as the likely language; the model may
     *         still fall back to the other supported languages when the
     *         audio clearly does not match. Pass null for a fully
     *         language-neutral instruction.
     */
    fun buildTranscribePrompt(langHint: String? = null): String {
        val languageClause = if (langHint.isNullOrBlank()) {
            "The speech may be in any of these languages: " +
                SUPPORTED_SPOKEN_LANGUAGES.joinToString(", ") + ". " +
                "Transcribe the speech in the language actually spoken."
        } else {
            "The speech is most likely in $langHint, but it may also be in " +
                SUPPORTED_SPOKEN_LANGUAGES.filter { !it.equals(langHint, ignoreCase = true) }
                    .joinToString(", ") + ". " +
                "Transcribe the speech in the language actually spoken, " +
                "even if that differs from $langHint."
        }
        return "Transcribe the following speech segment. $languageClause " +
            "Follow these specific instructions for formatting the answer: " +
            "Only output the transcription, with no newlines. " +
            "Output plain text only: no markdown symbols, no bullets, no asterisks, no emoji. " +
            "When transcribing numbers, write the digits, i.e. write 1.7 and not one point seven, " +
            "and write 3 instead of three."
    }
}

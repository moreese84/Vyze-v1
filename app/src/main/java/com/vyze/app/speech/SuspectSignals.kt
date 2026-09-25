package com.vyze.app.speech

/**
 * Pure suspect-transcript signals for the online recognizer path. Text +
 * flags in → boolean out; no Android, no state — JVM-testable.
 *
 * ## The bug these fix (device log, 2026-09-25)
 *
 * A Chinese speaker's Mandarin was returned by the platform recognizer as
 * CONFIDENT ENGLISH GHOST TEXT: "speak in Chinese", "what are you talking",
 * "Chinese" — never NO_MATCH, so every failure-triggered rescue (ladder,
 * model-ASR) was bypassed and the ghosts were accepted as real English
 * queries. Two signals now catch that class:
 *
 *  1. LANGUAGE-FLIP: the conversation recently showed non-English speech
 *     (a ms/zh transcript was accepted, or the locale ladder is active
 *     after failed cycles) and the recognizer suddenly claims fluent
 *     English. Plausible-looking English after ms/zh evidence is treated
 *     as suspect — one pinned re-listen, then the offline audio replay.
 *  2. LANGUAGE-NAME ECHO: the transcript is (only) a language name — the
 *     recognizer's English model rendering/translating the user actually
 *     SAYING a language word ("中文" → "Chinese"). Meta-output, never a
 *     query.
 *
 * Both signals only ARM the existing suspect ladder — the transcript is
 * still delivered if the re-listen and replay both confirm it. Nothing is
 * dropped on a signal alone.
 */
object SuspectSignals {

    /** Normalized language-name echoes — the recognizer talking ABOUT language. */
    val LANGUAGE_NAME_ECHOES: Set<String> = setOf(
        "chinese", "mandarin", "cina", "hua yu", "zhong wen", "华语", "中文",
        "malay", "melayu", "bahasa melayu", "bahasa",
        "english", "inggeris", "inglis",
        "speak chinese", "speak in chinese", "speaking chinese",
        "speak malay", "speak in malay", "speaking malay",
        "speak english", "speak in english", "speaking english",
        "talk chinese", "talk in chinese",
        "chinese language", "malay language", "english language",
    )

    /** Lowercase, strip punctuation to spaces, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * True when the transcript is (essentially) just a language name —
     * meta-speech about language, never a visual query.
     */
    fun isLanguageNameEcho(transcript: String): Boolean {
        val n = normalize(transcript)
        if (n.isEmpty()) return false
        // Whole-transcript match, plus a trailing-cue tolerance ("speak in
        // chinese please" style tails still count — the cue words around
        // the name carry no query content).
        if (n in LANGUAGE_NAME_ECHOES) return true
        val stripped = n
            .replace(Regex("\\b(please|can you|saya|mahu|nak|cuba)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped in LANGUAGE_NAME_ECHOES
    }

    /**
     * Language-flip suspicion: fluent English claimed by the recognizer
     * while the conversation recently showed non-English speech. Armed by
     * EITHER recent spoken ms/zh history OR an active locale ladder (the
     * ladder only arms after failed recognition cycles — exactly the
     * struggling-user context in which ghost text appears).
     *
     * @param resultIsEnglish  the result's resolved locale is English
     * @param spokenMsZhHistory a ms/zh transcript was accepted this
     *        conversation (recent spoken-language evidence)
     * @param ladderActive    the locale ladder is mid-climb
     *        (index > 0)
     */
    fun isFlipSuspect(
        resultIsEnglish: Boolean,
        spokenMsZhHistory: Boolean,
        ladderActive: Boolean
    ): Boolean = resultIsEnglish && (spokenMsZhHistory || ladderActive)
}

package com.vyze.app.speech

/**
 * LAYER 1 of the anti-hallucination stack (L4, 2026-09-25): a transcript
 * sanity filter. Even a clip that PASSED Layer 0 (real classified speech)
 * can come back as SELF-TALK — the model describing itself, refusing, or
 * speaking its own confirmation lines, instead of transcribing the user.
 * Observed verbatim in the device corpus:
 *
 *   "I am a large language model, trained by Google."   (08:00 + 08:20 sessions)
 *   "Saya tidak dapat memproses permintaan anda."       (ms refusal-speak)
 *   "Tentu saya akan bantu." / "Ya, betul. Saya akan cuba."
 *                                                        (ms confirmation-
 *                                                         speak, 09-29)
 *
 * (Silence-fill greetings — "Selamat pagi" on a speechMs=0 clip — are
 * handled UPSTREAM by Layer 0 [com.vyze.app.device.SpeechGatePolicy]:
 * sub-floor clips are never transcribed at all. App-cue hybrids are
 * handled by the StudentRouter v3 branch. Layers stay disjoint.)
 *
 * CONTRACT:
 *  - PURE function: text in → boolean out. No Android, no state —
 *    JVM-testable (the [SuspectSignals] house style).
 *  - CONSERVATIVE by construction: this policy NEVER drops user speech on
 *    its own. It only arms the caller to re-ask / deliver the honest
 *    failure. A real query matching these patterns has never been observed
 *    — they are first-person model-identity statements and model refusals,
 *    not things users say to a camera assistant.
 *  - Language coverage mirrors Vyze's supported set: English, Bahasa
 *    Malaysia, Mandarin Chinese. Patterns normalize case/punctuation.
 *  - DEV-SIDE TEACHER (TypeSafe discipline): the pattern list is validated
 *    against the rolling corpus by the Jev harness (tools/jev_harness,
 *    cloud-only, never ships) — see selftalk.py. On-device ships only the
 *    distilled patterns below.
 */
object SelfTalkPolicy {

    /** Normalized self-talk fingerprints. Lowercase, punctuation-stripped. */
    val SELF_TALK_PATTERNS: List<Regex> = listOf(
        // Model self-identification (en) — the 08:00/08:20 hallucinations.
        Regex("\\bi am a (large )?language model\\b"),
        // "I'm" normalizes to "i m" (apostrophe → space) — allow both forms.
        Regex("\\bi\\s?m an ai( assistant)?\\b"),
        Regex("\\bas an ai( language model)?\\b"),
        Regex("\\bi cannot (and )?will not\\b"),
        // Polite-refusal forms (Jev teacher, 2026-09-25 corpus run: the one
        // genuine gap — "I'm sorry, I cannot fulfill this request."). The
        // normalizer renders both "I'm" and "I am" as "i m" / "i am".
        Regex("\\bi (am|m) sorry\\b.{0,30}\\bi cannot\\b"),
        Regex("\\bi cannot fulfill\\b"),
        // English refusal-speak on date/fact asks (2026-09-29 09:00 session):
        // the pre-fix refusal "I do not have access to real-time information
        // such as the date for next Friday. Please check a calendar." was
        // captured back as a QUERY and answered with a scene description.
        // "I cannot tell you" (the earlier refusal form) joins it — both are
        // assistant-speak, never camera queries. A user asking "why you
        // cannot tell me" cannot match: the patterns anchor on the
        // first-person "i cannot / i do not have".
        Regex("\\bi do not have (access|information)\\b"),
        Regex("\\bi cannot tell you\\b"),
        // Refusal-speak (ms) — the ms refusal hallucination.
        Regex("\\bsaya (tidak boleh|tidak dapat|tidak mampu)\\b"),
        Regex("\\bsebagai model (bahasa )?besar\\b"),
        // Confirmation/commitment-speak (ms) — 2026-09-29 device session:
        // Vyze's own spoken confirmation lines ("Tentu saya akan bantu.",
        // "Ya, betul. Saya akan cuba.") captured back as user queries and
        // ANSWERED with scene descriptions. First-person commitments are
        // assistant-speak, never a camera query. Anchored on "saya akan +
        // verb" so a bare user confirmation ("Ya, betul") still passes —
        // the askQueryConfirmation flow depends on it. A user replying
        // "saya akan cuba lagi" after a failure would match; the contract
        // below makes that cost one re-ask, not a lost query — cheaper than
        // another echoed turn burning a 5-second inference.
        Regex("\\bsaya akan (bantu|membantu|cuba|tolong)\\b"),
        // Refusal-speak (zh) — the same class in the third supported language.
        Regex("作为一个(大型)?语言模型"),
        Regex("我无法处理"),
        // Identity self-talk (zh) — 2026-09-30 device session (corpus row
        // ir_151, Jev 0.93): "你好,我是AI。" captured back as a user query
        // and answered with a scene description. zh parity for the en
        // identity family above, closing it across all three languages.
        // The normalizer renders "AI" as "ai". Anchored on the first-person
        // "我是" so a user asking ABOUT the model ("你是AI吗") never matches.
        // Watch-list sibling (1 occurrence, no pattern yet): "我是人工智能".
        Regex("我是ai"),
    )

    /** Lowercase, strip punctuation to spaces, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it.code > 0x2E7F) it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Self-identification / refusal check — the model speaking as ITSELF
     * is never the user's query, whatever the capture quality.
     */
    fun isSelfTalk(transcript: String): Boolean {
        val n = normalize(transcript)
        if (n.isEmpty()) return false
        return SELF_TALK_PATTERNS.any { it.containsMatchIn(n) }
    }
}

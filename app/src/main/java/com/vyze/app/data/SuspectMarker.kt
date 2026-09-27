package com.vyze.app.data

/**
 * IMPLICIT FAILURE MARKERS (plan C, "Live-user corpus growth without user
 * effort", 2026-09-24/27): the app identifies its own failures silently and
 * pre-labels interaction rows as suspects — the human only ever performs
 * ONE action (consenting to share), and the shared rows arrive PRE-LABELED
 * AS FAILURES, exactly the rows B3 / v3 branch distillation needs.
 *
 * EXTENDS the proven PreferenceLearner pattern (barge-ins as implicit
 * dissatisfaction, zero questions asked) to the whole pipeline.
 *
 * SIGNAL → MARK (plan table):
 *  - barge-in over a playing answer            → SUSPECT_BARGE_IN_PRIOR
 *      (attached to the NEXT stored record — the interrupting query's —
 *      because the interrupted record is already persisted; ts adjacency
 *      identifies the pair. Semantics: "the row BEFORE this one was
 *      interrupted mid-answer".)
 *  - recovery cue spoken ("please say that…")  → SUSPECT_PRIOR_ASR_FAILURE
 *      (attached to the retry's stored record; the failed attempt itself
 *      stores nothing — no inference happened)
 *  - query language ≠ answer language          → SUSPECT_LANG_MISMATCH
 *      (detected at store time via [InteractionLogRow.inferLanguage] on
 *      both sides — the wrong-language bug made corpus-visible)
 *  - same query repeated within the window     → SUSPECT_REPEAT_60S
 *      (answer didn't satisfy — the user asked again)
 *
 * NON-goals (layers stay disjoint): no self-talk marks here (SelfTalkPolicy
 * owns dropped transcripts and they never store records), no routing marks
 * (the shadow router logs its own ROUTE evidence), no UI, no persistence
 * beyond the interaction_records.feedback column this class feeds.
 *
 * CONTRACT: PURE — every function is text/params in → decision out, no
 * Android, no state (the caller keeps the recent-query ring and pending
 * flags). JVM-testable ([SuspectMarkerTest]).
 */
object SuspectMarker {

    // ── Tag vocabulary (single source of truth, grep-able in exports) ──

    const val TAG_BARGE_IN_PRIOR = "suspect_barge_in_prior"
    const val TAG_PRIOR_ASR_FAILURE = "suspect_prior_asr_failure"
    const val TAG_LANG_MISMATCH = "suspect_lang_mismatch"
    const val TAG_REPEAT_60S = "suspect_repeat_60s"

    /** Answer-didn't-satisfy window for repeat detection (plan: ~60s). */
    const val REPEAT_WINDOW_MS = 60_000L

    /**
     * Recovery-cue shapes — the user ASKING to be re-asked. Spoken when the
     * recognizer failed / the app said "I didn't catch that". en + ms + zh,
     * matched on the normalized (lowercase, punctuation-stripped) query,
     * whole-transcript or prefix (ASR tails like "please" are tolerated by
     * the caller's normalization, not by extra rules here).
     */
    val RECOVERY_CUES: Set<String> = setOf(
        // en
        "please say that again", "say that again", "say again",
        "can you say that again", "what did you say", "i didnt catch that",
        "i did not catch that", "come again",
        // ms
        "sila sebut sekali lagi", "sebut sekali lagi", "sila ulang",
        "ulang sekali", "apa yang anda kata", "tak dengar",
        // zh
        "请再说一遍", "再说一遍", "再说一次", "我没听清", "我没有听清",
        "你说什么", "你說什麼",
    )

    /**
     * True when the transcript is a recovery cue — ASR just failed and the
     * user is re-asking. The PREVIOUS interaction (if any) failed to be
     * heard; the retry's stored record carries [TAG_PRIOR_ASR_FAILURE].
     */
    fun isRecoveryCue(normalizedQuery: String): Boolean {
        val n = normalizedQuery.trim()
        if (n.isEmpty()) return false
        if (n in RECOVERY_CUES) return true
        // Prefix tolerance: "please say that again 2" style ASR tails keep
        // the cue as the dominating content when it opens the transcript.
        return RECOVERY_CUES.any { n.startsWith(it) }
    }

    /**
     * True when [query] repeats the recent query [lastQueryMsAgo] ms after
     * it was asked, with lenient equality (whitespace/case-insensitive).
     * A quick repeat is the user saying "that answer didn't satisfy".
     */
    fun isRepeatWithinWindow(
        query: String,
        lastQuery: String?,
        lastQueryMsAgo: Long?,
        windowMs: Long = REPEAT_WINDOW_MS,
    ): Boolean {
        if (lastQuery.isNullOrBlank() || lastQueryMsAgo == null) return false
        if (lastQueryMsAgo < 0 || lastQueryMsAgo > windowMs) return false
        val a = normalizeForRepeat(query)
        val b = normalizeForRepeat(lastQuery)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b
    }

    /**
     * Language-mismatch mark: the query's inferred language differs from
     * the ANSWER's inferred language. Uses the exporter's own inference so
     * the corpus rows and the marks can never disagree about language.
     * "unknown" on either side never marks (garble is not evidence of a
     * wrong-language bug).
     */
    fun isLangMismatch(query: String, answer: String): Boolean {
        val qLang = InteractionLogRow.inferLanguage(query)
        val aLang = InteractionLogRow.inferLanguage(answer)
        if (qLang == "unknown" || aLang == "unknown") return false
        return qLang != aLang
    }

    // ── Pending-flag → tag plumbing (the caller's flags, applied later) ──

    /**
     * The tags to persist on THIS stored record, given the pending flags
     * the controller collected since the last store. Order is stable for
     * grep-ability; empty string means no marks (feedback column stays
     * clean for future real user feedback).
     */
    fun tagsForStoredRecord(
        priorBargeIn: Boolean,
        priorAsrFailure: Boolean,
        langMismatch: Boolean,
        repeatWithinWindow: Boolean,
    ): String = buildList {
        if (priorBargeIn) add(TAG_BARGE_IN_PRIOR)
        if (priorAsrFailure) add(TAG_PRIOR_ASR_FAILURE)
        if (langMismatch) add(TAG_LANG_MISMATCH)
        if (repeatWithinWindow) add(TAG_REPEAT_60S)
    }.joinToString(" ")

    /**
     * Lenient repeat equality: lowercase, punctuation → space, collapse
     * whitespace — "What is in front of me?" repeats "what is in front
     * of me" (the trailing ? is ASR punctuation, not different content).
     * Mirrors the SuspectSignals.normalize house style.
     */
    private fun normalizeForRepeat(q: String): String =
        q.lowercase()
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
}

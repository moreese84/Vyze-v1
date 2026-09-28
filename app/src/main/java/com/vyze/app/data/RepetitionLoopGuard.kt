package com.vyze.app.data

/**
 * REPETITION-LOOP GUARD (pure policy, JVM-testable — the SelfTalkPolicy /
 * SuspectMarker house style).
 *
 * CONTEXT (2026-09-28 export sweep, row ir_85): a member-card read came
 * back as "It says New World Member Card with a number 999999999999…"
 * — a ~180-digit single-character loop — and the degenerate response went
 * to TTS uncaught. The hedging check (CONFIDENCE ABORT) guards the
 * OPPOSITE failure (uncertainty language); nothing guarded runaway
 * repetition, which small models produce under degenerate decoding and
 * which reads as confident garbage to a listener who cannot see the
 * screen.
 *
 * CONTRACT:
 *  - PURE function: response text in → boolean out. No Android, no state.
 *  - Two degenerate shapes are caught:
 *      1. a single character repeated ≥ [MAX_RUN] times consecutively
 *         (the ir_85 "9999…" shape)
 *      2. the same token repeated ≥ [MAX_TOKEN_REPEATS] times
 *         consecutively, regardless of token length ("dan dan dan dan
 *         dan dan" — no length exemption: six consecutive identical
 *         tokens are never natural speech)
 *  - Deliberately conservative so natural answers never trip: real
 *    sentences do not repeat one token six times in a row, and the
 *    observed failure was a ~180-char run against a threshold of 15.
 *    Serial/IC-shaped strings (≤ ~16 chars, mixed digits) sit far below
 *    the run threshold — the serial carve-out is unaffected.
 *  - FALSE-POSITIVE REVIEW: worst case a legitimately repetitive (and
 *    therefore useless) answer is replaced by the canned fallback — an
 *    acceptable loss, same posture as the keyword guard on
 *    "Hello"-prefixed answers.
 */
object RepetitionLoopGuard {

    /** A single character repeated this many times consecutively trips. */
    const val MAX_RUN = 15

    /** Consecutive identical tokens trip at this count. */
    const val MAX_TOKEN_REPEATS = 6

    /**
     * True when the response shows a degenerate repetition loop and must
     * not reach TTS. Null/blank input is never a loop (nothing to play).
     */
    fun isDegenerate(response: String?): Boolean {
        if (response.isNullOrBlank()) return false

        // Shape 1: single-character run (letters, digits, punctuation,
        // CJK — anything repeated ≥ MAX_RUN times in a row).
        var run = 1
        for (i in 1 until response.length) {
            run = if (response[i] == response[i - 1]) run + 1 else 1
            if (run >= MAX_RUN) return true
        }

        // Shape 2: consecutive identical tokens (whitespace-separated).
        val tokens = response.trim().split(Regex("\\s+"))
        var repeats = 1
        for (i in 1 until tokens.size) {
            repeats =
                if (tokens[i] == tokens[i - 1]) repeats + 1 else 1
            if (repeats >= MAX_TOKEN_REPEATS) return true
        }

        return false
    }
}

package com.vyze.app.util

/**
 * Storage-boundary redaction for persisted text (whole-app audit P1 #3/#4,
 * 2026-11-09).
 *
 * Scan history and interaction records persisted sensitive content verbatim
 * and unbounded: bank-card/IC numbers inside OCR takes, full VLM prompts
 * per interaction. Everything crossing a storage boundary passes through
 * here: long digit runs are masked and length is capped. Recall matching
 * and history review keep working on the sanitized text; live reads are
 * untouched (redaction applies at the store call only, never the spoken
 * path).
 *
 * Pure policy in the [SensitiveIdPolicy] house style: no Android, no state,
 * JVM-tested in StorageRedactionTest.
 */
object StorageRedaction {

    /** Fixed mask: length-preserving output would leak card-vs-IC shape. */
    const val MASK = "••••"

    /** Stored interaction prompts: covers observed full prompts (~6k max). */
    const val STORED_PROMPT_MAX_CHARS = 4000

    /** Stored answers / memory values: outputs are token-bounded already. */
    const val STORED_TEXT_MAX_CHARS = 4000

    /** Stored scan content: full OCR takes were unbounded per row. */
    const val STORED_SCAN_MAX_CHARS = 1000

    /** Stored scan metadata (barcode format) / text-lane queries. */
    const val STORED_META_MAX_CHARS = 200

    /**
     * 10+ digit runs with single space/dash separators: MYKad-12,
     * card-16, EAN-13, phones. Below the bar on purpose: ISO dates
     * (8 digits), banknote serials (~7, readable by owner decision),
     * prices, weights, dosages, times, coordinates.
     */
    private val LONG_DIGIT_RUN = Regex("""\d(?:[\s\-]?\d){9,}""")

    /**
     * Mask long digit runs with [MASK]. Everything else — words, short
     * numbers, CJK, punctuation — passes through byte-identical.
     */
    fun redactDigitRuns(text: String): String =
        LONG_DIGIT_RUN.replace(text, MASK)

    /**
     * Redact, then cap at [maxChars] with a "…" marker when truncated.
     * The marker keeps capped rows visibly partial in history review.
     */
    fun sanitizeForStorage(text: String, maxChars: Int): String {
        val redacted = redactDigitRuns(text)
        if (redacted.length <= maxChars) return redacted
        return redacted.take(maxChars) + "…"
    }
}

package com.vyze.app.speech

import java.util.Locale

/**
 * Pure decision policy for the locale-fallback ladder (the "no response"
 * rescue). Text/flags in → next-ladder-index out; no Android, no recognizer
 * state — fully JVM-testable.
 *
 * ## The bug this fixes (device log, 2026-09-24)
 *
 * A Chinese speaker on an English-default phone, ONLINE: the unpinned
 * session NO_MATCHed, and the ladder retried **en-US** — the very same
 * device-default English model that just failed (the old skip-(a)/(b)
 * logic only advanced past en-US when the session was PINNED to it).
 * The user had already spoken and stopped, so the duplicate en-US retry
 * heard silence and surfaced "No speech detected." — zh-CN was never
 * reached. Four taps, four identical dead cycles.
 *
 * ## Contract
 *
 *  1. An UNPINNED failure never re-tries index 0 (en-US): the session
 *     just effectively ran on the device-default English model, so a
 *     pinned en-US retry is a guaranteed duplicate. The retry jumps
 *     straight to the non-English ladder, ordered at the last
 *     successfully SPOKEN ms/zh language when known (ms-MY otherwise).
 *  1b. A failure of a PINNED session never retries index 0 either: index
 *     0 means "no ladder step", which re-resolves to the very pin that
 *     just failed (an infinite zh→zh loop with a declared zh voice).
 *  2. A locale the failed session was PINNED to is always skipped —
 *     re-running it would reproduce the same failure.
 *  3. A language the recognizer CLAIMED in its results bundle (suspect
 *     path — it transcribed but the transcript is implausible/low-conf)
 *     is also skipped.
 *  4. Bounded by construction: returns [locales].size when exhausted;
 *     the caller treats that as "fall through to error handling".
 */
object LadderPolicy {

    /** Mirrors [com.vyze.app.MainActivity.FALLBACK_RECOGNITION_LOCALES]. */
    val DEFAULT_LADDER: List<Locale> = listOf(
        Locale.US,
        Locale("ms", "MY"),
        Locale("zh", "CN")
    )

    /**
     * @param locales           the ladder, in priority order
     * @param currentIndex      ladder position when the failure occurred
     * @param failedPinnedTag   language tag the FAILED session was pinned to
     *                          ("en-US" / "ms-MY" / "zh-CN"), or null if it
     *                          ran unpinned
     * @param lastSpokenMsZh    last successfully SPOKEN ms/zh locale this
     *                          conversation, or null if none
     * @param detectedBundleTag language the recognizer CLAIMED for the failed
     *                          result, or null (NO_MATCH carries no bundle)
     * @return index of the next locale to retry, or [locales].size when the
     *         ladder is exhausted
     */
    fun nextRetryIndex(
        locales: List<Locale>,
        currentIndex: Int,
        failedPinnedTag: String?,
        lastSpokenMsZh: Locale?,
        detectedBundleTag: String?
    ): Int {
        if (currentIndex >= locales.size) return locales.size
        val failedTags = buildSet {
            failedPinnedTag?.let { add(it) }
            detectedBundleTag?.let { add(it) }
        }

        // (1) Unpinned failure: never re-try the device-default English
        //     model. Jump to the non-English ladder, ordered at the last
        //     SPOKEN ms/zh language when known.
        var idx = currentIndex
        if (failedPinnedTag == null && idx == 0) {
            val spokenIdx = lastSpokenMsZh
                ?.takeIf { it.language == "ms" || it.language == "zh" }
                ?.let { spoken -> locales.indexOfFirst { it.language == spoken.language } }
                ?.takeIf { it > 0 }
            idx = spokenIdx ?: 1
            if (idx >= locales.size) return locales.size
        }
        // (1b) Pinned failure: index 0 is ALSO a duplicate — it means "no
        //      ladder step", which re-resolves to the same pin that just
        //      failed. Floor the retry at the first real ladder step.
        if (failedPinnedTag != null && idx == 0) {
            idx = 1
            if (idx >= locales.size) return locales.size
        }

        // (2)+(3) Skip anything that just failed (pinned model / claimed
        //         bundle language).
        while (idx < locales.size && locales[idx].toLanguageTag() in failedTags) {
            idx++
        }
        return idx
    }
}

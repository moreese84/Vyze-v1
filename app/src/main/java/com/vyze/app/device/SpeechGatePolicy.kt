package com.vyze.app.device

/**
 * LAYER 0 of the anti-hallucination stack (L4, 2026-09-25): decide whether
 * a captured clip is worth transcribing AT ALL.
 *
 * Evidence (device logs, 2026-09-25): clips whose classified speech is
 * essentially empty are HALLUCINATION BAIT. speechMs=0 → "Selamat pagi"
 * and "Saya tidak dapat memproses permintaan anda"; earlier sessions'
 * self-identification hallucinations ("I am a large language model…")
 * all sat on sub-300ms speech. Every real query in the corpus carries
 * ≥900ms of classified speech. Between 0 and 300ms there is no observed
 * case of a useful transcription — only fabrication.
 *
 * CONTRACT:
 *  - Pure math — JVM-testable, no Android (mirrors [CaptureStopPolicy]).
 *  - A rejected clip returns "I didn't catch that" to the user (the
 *    honest failure) and never wakes the model — saving ~1.5s of wasted
 *    inference per silent tap and eliminating the fabrication class.
 *  - The floor must stay well BELOW the smallest observed real query
 *    (920ms) and ABOVE every observed hallucination (≤140ms): 300ms.
 *  - [CaptureStopPolicy]'s no-speech bail still records the clip — this
 *    gate is the QUALITY decision made after recording, not a latency
 *    lever. The two policies answer different questions.
 */
object SpeechGatePolicy {

    /**
     * Minimum classified speech (ms) for a clip to be worth transcribing.
     * Evidence-bounded: hallucinations live at ≤140ms, real queries at
     * ≥920ms (device corpus, 2026-09-24/25).
     */
    const val MIN_SPEECH_MS: Long = 300L

    /**
     * True when the clip carries enough classified speech to transcribe.
     * Pure — same input, same output.
     */
    fun shouldTranscribe(speechMs: Long): Boolean = speechMs >= MIN_SPEECH_MS
}

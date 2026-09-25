package com.vyze.app.speech

/**
 * Pure policy for choosing the voice-recognition engine at tap time.
 * Flags in → engine out; no Android, no state — JVM-testable.
 *
 * ## Why this exists (2026-09-25)
 *
 * The platform recognizer's Mandarin behavior is unfixable from our side:
 * online it returns confident English ghost text for Chinese speech
 * (device logs all day), and OEM GApps builds vary wildly. The app's OWN
 * engine — Gemma 4 E2B's audio encoder with the language-agnostic
 * [com.vyze.app.core.AsrPromptPolicy] prompt — transcribed Chinese 4/4
 * offline (这是什么/这个是什么 → glasses, chair, laptop; tap→answer ~6–8s).
 *
 * DESIGN A ("Gemma always", user-selected): when the gemmaAlways flag is
 * on, voice transcribes LOCALLY on every tap — online included. The
 * system recognizer is not consulted; the entire Google-bug class
 * (ghost text, ladder churn, locale mirroring, suspect signals) is
 * bypassed rather than fought. The privacy claim upgrades from "pixels
 * never leave" to "pixels and voice never leave — unconditionally".
 *
 * ## Contract
 *
 *  - FALLBACKS FIRST: regardless of the flag, the Gemma path requires
 *    the engine ready + idle, mic permission, no active noise pause,
 *    and no pending TTS. When any of those fail, the policy falls back
 *    to the system recognizer path — a slow local engine must never
 *    become a dead mic.
 *  - SYSTEM FALLBACK when the flag is off: unchanged legacy behavior
 *    (all of today's ladder/rescue/suspect work remains the online
 *    engine of record).
 *  - PURE: the caller supplies capability booleans; this object only
 *    decides.
 */
object VoiceEnginePolicy {

    enum class Engine {
        /** Local AudioCapture + Gemma audio-encoder transcription. */
        GEMMA_PRIMARY,

        /** Platform SpeechRecognizer (the legacy online path). */
        SYSTEM_RECOGNIZER,
    }

    /**
     * Decide the engine for this tap. Pure — same input, same output.
     *
     * @param gemmaAlwaysEnabled the Design A flag (debug toggle now;
     *        promotion is a deliberate manual act)
     * @param deviceOffline true when the device has no connectivity —
     *        the ORIGINAL offline-first contract: Gemma primary offline
     *        regardless of any flag, because the platform recognizer
     *        accepts offline sessions and returns nothing
     * @param engineReady the local Gemma engine is loaded and warm
     * @param engineIdle the engine is not mid-inference (one generation
     *        at a time — a running analysis must never be preempted)
     */
    fun chooseEngine(
        gemmaAlwaysEnabled: Boolean,
        deviceOffline: Boolean,
        engineReady: Boolean,
        engineIdle: Boolean
    ): Engine {
        if (engineReady && engineIdle) {
            // Design A: the local engine is usable — prefer it whenever the
            // flag says so, ONLINE INCLUDED. The offline-first contract
            // (Gemma primary whenever there is no network) is unchanged.
            if (gemmaAlwaysEnabled || deviceOffline) return Engine.GEMMA_PRIMARY
            // Legacy routing: flag off + online → the platform recognizer
            // with all its ladder/rescue layers (unchanged behavior).
            return Engine.SYSTEM_RECOGNIZER
        }
        // Engine unusable (not ready, busy, or absent): the system
        // recognizer is the only safe mic. Even with the flag on, a dead
        // local engine must not silence voice input.
        return Engine.SYSTEM_RECOGNIZER
    }
}

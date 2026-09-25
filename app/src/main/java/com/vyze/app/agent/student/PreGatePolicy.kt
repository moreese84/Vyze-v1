package com.vyze.app.agent.student

import com.vyze.app.agent.RouterDecision

/**
 * VLM PRE-GATE POLICY — routes traffic AWAY from the VLM entirely.
 *
 * The latency budget ([FUTURE_PLAN_JEV_STUDENT.md] lever #1): a Gemma
 * scene inference costs ~1.5–2s end to end. Several IGNORE families the
 * student already classifies with high confidence — app-cue echo, greeting
 * garble, filler-only transcripts — currently fall through the native
 * dispatch ladder to the VLM snapshot path and pay a full GPU inference
 * to produce a scene description nobody asked for ("Hello, I'm a model."
 * = ~2s of GPU work today).
 *
 * EXECUTION CONTRACT (mirrors the shadow-router discipline):
 *  - PURE function: text in → [ResponseMode] out. No I/O, no Android, no
 *    state, no TTS — the caller supplies the utterances via
 *    [PreGateAction]; the policy only decides. Fully unit-testable on the
 *    JVM.
 *  - CONSERVATIVE — admits exactly three high-confidence IGNORE families
 *    and nothing else. This is the deliberate "ships small" core:
 *      * app-cue echo    (exact-phrase; only strings Vyze itself speaks)
 *      * greeting garble (greeting opener + zero question markers)
 *      * filler-only     (token-emptiness after filler removal)
 *    Out-of-domain asks stay ON the VLM path for now: the seed evidence
 *    marks them IGNORE, but real-usage frequency is unproven and a wrong
 *    IGNORE there silences a real (if unserveable) ask — the catch-all
 *    escape hatch is preserved until device data says otherwise (v3 will
 *    revisit).
 *  - GATE-MATCHED: a decision routes to a response mode ONLY when the
 *    frozen fixtures prove the family (seed fixture p4/m9/n2 + device
 *    rows dev3–dev5: Jev IGNORE at 0.45–1.0; student 13/14 = 92.9% on the
 *    device gate). VLM_VOICE_QUERY and positive intents always pass
 *    through (PASS_THROUGH) — the pre-gate NEVER touches traffic that
 *    needs the camera or the model.
 *  - SHIPS DARK: gated behind [VyzeAgentRuntime.preGateEnabled] (default
 *    false). The runtime never enables itself; toggling is debug-only and
 *    process-local, restoring default-dark on every restart. This policy
 *    class itself is unconditionally safe to construct anywhere.
 */
object PreGatePolicy {

    /**
     * What the caller should do instead of the full VLM dispatch.
     */
    enum class ResponseMode {
        /**
         * Run the normal legacy dispatch (unchanged behavior). Every
         * positive intent and the general catch-all land here.
         */
        PASS_THROUGH,

        /**
         * Skip capture/VLM/TTS entirely: silent, near-zero latency. For
         * app-cue echoes — Vyze is ALREADY speaking; interrupting itself
         * to say "didn't catch that" would worsen the echo.
         */
        SILENT,

        /**
         * Speak a short localized "didn't catch that" cue via
         * [PreGateAction] — no capture, no VLM. For greeting garble and
         * filler-only transcripts.
         */
        GENTLE_IGNORE,
    }

    /**
     * The pre-gate verdict for a spoken transcript. Pure — same input,
     * same output, no side effects.
     */
    fun evaluate(spokenText: String): ResponseMode {
        val d = StudentRouter.decide(spokenText)
        if (d.action != RouterDecision.Action.IGNORE) return ResponseMode.PASS_THROUGH
        return when {
            d.reason.contains("app-cue echo") -> ResponseMode.SILENT
            // v3: the app's own status cue captured inside a garble hybrid —
            // same family as the echo (Vyze is already talking; answering a
            // scene nobody asked for is the dev6 regression). Silent skip.
            d.reason.contains("app-cue garble hybrid") -> ResponseMode.SILENT
            d.reason.contains("greeting garble") -> ResponseMode.GENTLE_IGNORE
            d.reason.contains("filler-only") -> ResponseMode.GENTLE_IGNORE
            else -> ResponseMode.PASS_THROUGH
        }
    }
}

/**
 * The utterances the pre-gate caller needs, injected by the fragment so
 * this layer stays JVM-pure and testable. Implementations supply
 * localized strings (TTSManager.localized) and the haptic pattern; the
 * pre-gate never touches TTS or the vibrator itself.
 */
interface PreGateAction {
    /**
     * Execute a gentle-ignore response: speak the short "didn't catch
     * that" cue and confirm by haptic. Fire-and-forget; the caller owns
     * threading and the speech lifecycle (same discipline as the instant
     * answer lane).
     */
    fun executeGentleIgnore()
}

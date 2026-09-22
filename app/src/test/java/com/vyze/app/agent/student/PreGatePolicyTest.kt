package com.vyze.app.agent.student

import com.vyze.app.agent.student.PreGatePolicy.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [PreGatePolicy] — the flag-gated VLM pre-gate (latency
 * lever #1). The policy is pure: text in → [ResponseMode] out, no Android,
 * no state. These tests pin the execution contract:
 *
 *  - ONLY the three high-confidence IGNORE families are admitted
 *    (app-cue echo / greeting garble / filler-only).
 *  - EVERY positive intent and the catch-all PASS THROUGH — the pre-gate
 *    must never touch traffic that needs the camera or the model.
 *  - Out-of-domain asks deliberately pass through too: seed evidence marks
 *    them IGNORE, but a wrong IGNORE there silences a real (if unserveable)
 *    ask — the catch-all escape hatch is preserved until v3.
 */
class PreGatePolicyTest {

    // ── Admitted family 1: app-cue echo → SILENT ─────────────────────

    @Test
    fun `app cue echo is silent`() {
        assertEquals(
            ResponseMode.SILENT,
            PreGatePolicy.evaluate("Please say that again"),
        )
    }

    @Test
    fun `app cue echo with apostrophe is silent`() {
        // Exercises the apostrophe-stripped normalization fix: the cue set
        // stores the stripped form, ASR punctuation variance must not break it.
        assertEquals(
            ResponseMode.SILENT,
            PreGatePolicy.evaluate("I didn't catch that. Double tap and try again."),
        )
    }

    @Test
    fun `app cue analyzing is silent`() {
        assertEquals(ResponseMode.SILENT, PreGatePolicy.evaluate("Analyzing"))
        assertEquals(ResponseMode.SILENT, PreGatePolicy.evaluate("Almost ready"))
    }

    // ── Admitted family 2: greeting garble → GENTLE_IGNORE ───────────

    @Test
    fun `greeting garble gets gentle ignore`() {
        // Device rows ir_13/ir_14: used to cost a full ~2s scene inference.
        assertEquals(
            ResponseMode.GENTLE_IGNORE,
            PreGatePolicy.evaluate("Hello, I'm a model."),
        )
        assertEquals(
            ResponseMode.GENTLE_IGNORE,
            PreGatePolicy.evaluate("Hey, I'm about this."),
        )
    }

    @Test
    fun `asr doubled greeting garble gets gentle ignore`() {
        // Live regression (2026-09-22): the recognizer committed "hello
        // hello" tagged ms_MY — the pre-gate must still admit it, so the
        // dispatch guard can skip the barge-in + locale mirror (garble must
        // never steer the TTS voice into Malay).
        assertEquals(
            ResponseMode.GENTLE_IGNORE,
            PreGatePolicy.evaluate("hello hello"),
        )
    }

    // ── Admitted family 3: filler-only → GENTLE_IGNORE ───────────────

    @Test
    fun `filler only transcript gets gentle ignore`() {
        assertEquals(
            ResponseMode.GENTLE_IGNORE,
            PreGatePolicy.evaluate("um, uh, err..."),
        )
    }

    // ── Pass-through: everything the pre-gate must NEVER touch ───────

    @Test
    fun `catch all voice query passes through`() {
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("what is paracetamol used for"),
        )
    }

    @Test
    fun `scene read color intents pass through`() {
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("what is in front of me"),
        )
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("you read this for me"),
        )
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("what color is this shirt"),
        )
    }

    @Test
    fun `ms deictic opener passes through as scene`() {
        // dev2: positive SCENE intent — never pre-gated.
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("Ini pula apa"),
        )
    }

    @Test
    fun `greeting with question content passes through`() {
        // A real request that merely starts with a greeting stays routable.
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("hello what is this"),
        )
    }

    @Test
    fun `out of domain asks deliberately pass through`() {
        // Seeded IGNORE evidence exists, but the pre-gate is conservative:
        // the catch-all escape hatch is preserved until device data (v3)
        // proves the family's real-usage frequency.
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("play some music"),
        )
        assertEquals(
            ResponseMode.PASS_THROUGH,
            PreGatePolicy.evaluate("what's the weather"),
        )
    }

    @Test
    fun `blank transcript passes through`() {
        // Empty handling is the dispatch ladder's job (isNotBlank guard);
        // the policy adds no opinion of its own.
        assertEquals(ResponseMode.PASS_THROUGH, PreGatePolicy.evaluate("   "))
    }
}

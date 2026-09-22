package com.vyze.app.agent.student

import com.vyze.app.agent.RouterDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [StudentRouter] — the offline distilled router
 * (Phase 3) whose rules come from the Phase 0 Jev harness labels.
 *
 * The fixture-level gate (student ≥ regex on the frozen corpus) lives in
 * [StudentRouterFixtureTest]; these tests pin the distilled branches
 * themselves, across all three supported languages.
 */
class StudentRouterTest {

    // ── Empty / noise ─────────────────────────────────────────────

    @Test
    fun `empty transcript is ignored`() {
        val d = StudentRouter.decide("   ")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `filler-only transcript is ignored`() {
        val d = StudentRouter.decide("errr... aaa...")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    // ── Read intent (legacy + distilled extras) ───────────────────

    @Test
    fun `english read intent`() {
        assertEquals(
            RouterDecision.Action.VLM_TEXT_READ,
            StudentRouter.decide("What does this medicine label say?").action,
        )
    }

    @Test
    fun `malay read intent via legacy keyword`() {
        assertEquals(
            RouterDecision.Action.VLM_TEXT_READ,
            StudentRouter.decide("Baca label ubat ini.").action,
        )
    }

    @Test
    fun `chinese read intent via legacy keyword`() {
        assertEquals(
            RouterDecision.Action.VLM_TEXT_READ,
            StudentRouter.decide("帮我读一读这个药瓶上的标签。").action,
        )
    }

    @Test
    fun `malay sign-reading ask via distilled extra`() {
        assertEquals(
            RouterDecision.Action.VLM_TEXT_READ,
            StudentRouter.decide("Papan tanda tu tulis apa?").action,
        )
    }

    // ── Scene describe (distilled) ────────────────────────────────

    @Test
    fun `english scene opener`() {
        val d = StudentRouter.decide("What is in front of me?")
        assertEquals(RouterDecision.Action.VLM_SCENE_DESCRIBE, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `malay scene opener`() {
        assertEquals(
            RouterDecision.Action.VLM_SCENE_DESCRIBE,
            StudentRouter.decide("Apa kat depan saya?").action,
        )
    }

    @Test
    fun `chinese scene opener`() {
        assertEquals(
            RouterDecision.Action.VLM_SCENE_DESCRIBE,
            StudentRouter.decide("我面前是什么？").action,
        )
    }

    // ── Color (distilled fast path) ───────────────────────────────

    @Test
    fun `english color ask is a fast path`() {
        val d = StudentRouter.decide("What color is this shirt?")
        assertEquals(RouterDecision.Action.FAST_COLOR_ANALYSIS, d.action)
        assertFalse(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `malay color ask is a fast path`() {
        assertEquals(
            RouterDecision.Action.FAST_COLOR_ANALYSIS,
            StudentRouter.decide("Apa warna baju ini?").action,
        )
    }

    // ── Light (distilled fast path) ───────────────────────────────

    @Test
    fun `english light ask is a fast path`() {
        val d = StudentRouter.decide("Is it dark in here?")
        assertEquals(RouterDecision.Action.LIGHT_CHECK, d.action)
        assertFalse(d.requiresVlm)
        assertFalse(d.includeCameraFrame)
    }

    @Test
    fun `malay light ask is a fast path`() {
        assertEquals(
            RouterDecision.Action.LIGHT_CHECK,
            StudentRouter.decide("Gelap ke di sini?").action,
        )
    }

    // ── Danger telemetry (labeled, never executed from speech) ────

    @Test
    fun `english danger phrase is SOS telemetry`() {
        val d = StudentRouter.decide("Help, I fell down and need assistance!")
        assertEquals(RouterDecision.Action.SOS, d.action)
        assertFalse(d.requiresVlm)
        assertTrue(d.reason.contains("gesture"))
    }

    @Test
    fun `malay danger phrase is SOS telemetry`() {
        assertEquals(
            RouterDecision.Action.SOS,
            StudentRouter.decide("Tolong, saya jatuh! Saya tak boleh bangun.").action,
        )
    }

    @Test
    fun `chinese danger phrase is SOS telemetry`() {
        assertEquals(
            RouterDecision.Action.SOS,
            StudentRouter.decide("救命，我摔倒了，起不来！").action,
        )
    }

    // ── Out-of-domain / device control (distilled IGNORE) ─────────

    @Test
    fun `english out-of-domain ask is ignored`() {
        val d = StudentRouter.decide("Play some music.")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `malay out-of-domain ask is ignored`() {
        assertEquals(
            RouterDecision.Action.IGNORE,
            StudentRouter.decide("Putar lagu sikit.").action,
        )
    }

    @Test
    fun `device control ask is ignored`() {
        assertEquals(
            RouterDecision.Action.IGNORE,
            StudentRouter.decide("Give me a double tap vibration.").action,
        )
    }

    // ── v2: app-cue echo (device corpus distillation) ─────────────

    @Test
    fun `app cue echo is ignored`() {
        val d = StudentRouter.decide("Please say that again")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `app cue echo with punctuation and case is ignored`() {
        assertEquals(
            RouterDecision.Action.IGNORE,
            StudentRouter.decide("Please, say that AGAIN!").action,
        )
    }

    @Test
    fun `app cue echo with straight apostrophe is ignored`() {
        // Regression: the normalizer strips apostrophes ("didn't" →
        // "didnt"), so the cue set must store the stripped form. Before the
        // fix the apostrophe entry could never match and this recapture of
        // Vyze's own spoken cue woke the VLM every time.
        assertEquals(
            RouterDecision.Action.IGNORE,
            StudentRouter.decide("I didn't catch that. Double tap and try again.").action,
        )
    }

    @Test
    fun `app cue echo with curly apostrophe is ignored`() {
        assertEquals(
            RouterDecision.Action.IGNORE,
            StudentRouter.decide("I didn\u2019t catch that. Double tap and try again.").action,
        )
    }

    @Test
    fun `app cue phrase inside a real request stays routable`() {
        // Not an exact cue equality — must NOT hit the app-cue branch.
        val d = StudentRouter.decide("Can you please say that again louder")
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
    }

    // ── v2: greeting garble (device corpus distillation) ──────────

    @Test
    fun `greeting garble without question content is ignored`() {
        val d = StudentRouter.decide("Hello, I'm a model.")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
    }

    @Test
    fun `second greeting garble variant is ignored`() {
        // The ir_14 trap: "this" contains "is" — word-boundary matching
        // must not read it as the question marker "is".
        val d = StudentRouter.decide("Hey, I'm about this.")
        assertEquals(RouterDecision.Action.IGNORE, d.action)
    }

    @Test
    fun `greeting with a real question still routes to the VLM`() {
        val d = StudentRouter.decide("Hello, what is in front of me?")
        assertEquals(RouterDecision.Action.VLM_SCENE_DESCRIBE, d.action)
    }

    @Test
    fun `greeting with a visual question falls through to voice query`() {
        val d = StudentRouter.decide("Hi, is someone standing near me?")
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
    }

    // ── v2: ms deictic opener (device corpus distillation) ────────

    @Test
    fun `malay deictic opener routes to scene describe`() {
        val d = StudentRouter.decide("Ini pula apa")
        assertEquals(RouterDecision.Action.VLM_SCENE_DESCRIBE, d.action)
        assertTrue(d.requiresVlm)
    }

    @Test
    fun `malay repeated deictic opener routes to scene describe`() {
        assertEquals(
            RouterDecision.Action.VLM_SCENE_DESCRIBE,
            StudentRouter.decide("Ini pula apa ini pula apa").action,
        )
    }

    @Test
    fun `malay follow-up yang ini pula stays a voice query`() {
        // Seed follow-up f8 — must NOT be swallowed by the new opener rule.
        assertEquals(
            RouterDecision.Action.VLM_VOICE_QUERY,
            StudentRouter.decide("Yang ini pula?").action,
        )
    }

    // ── Catch-all preserves the legacy fallback ───────────────────

    @Test
    fun `ambiguous visual question falls through to voice query`() {
        val d = StudentRouter.decide("Is someone standing near me?")
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
        assertTrue(d.requiresVlm)
    }

    @Test
    fun `malay catch-all preserves legacy phrasing`() {
        val d = StudentRouter.decide("Ada orang dekat dengan saya ke?")
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
    }

    @Test
    fun `decide is pure - same input same output`() {
        val q = "apa warna baju ini?"
        assertEquals(StudentRouter.decide(q), StudentRouter.decide(q))
    }
}

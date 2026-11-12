package com.vyze.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure decision router (single-master migration:
 * the ADK-native sub-agents were removed — execution now lives in
 * [AdkAgentManager] / [VyzeMasterAgent]).
 *
 * Scope: the PURE decision function ([VyzeShadowRouter.decide]). The
 * master-agent runner pipeline belongs to instrumented tests in a
 * later phase.
 */
class VyzeShadowRouterTest {

    private fun snapshot(
        engineReady: Boolean = true,
        isInferring: Boolean = false,
        captureAvailable: Boolean = true,
    ) = RouterSnapshot(engineReady, isInferring, captureAvailable)

    // ── Gesture routing ───────────────────────────────────────────

    @Test
    fun `single tap routes to VLM scene describe with frame`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SINGLE_TAP, tapX = 100, tapY = 200),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_SCENE_DESCRIBE, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `long press routes to light check without VLM`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.LONG_PRESS),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.LIGHT_CHECK, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `double tap defers to the UI voice session`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.DOUBLE_TAP),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    // ── Speech routing ────────────────────────────────────────────

    @Test
    fun `speech with reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "read the label for me"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `malay reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "baca teks ini"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    @Test
    fun `chinese reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "帮我读一下这个字"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    @Test
    fun `general speech routes to voice query`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "ini apa?"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `read inside other words does not misroute to text read`() {
        // Whole-app audit P2 #22 (2026-11-09): bare contains("read")
        // matched bread/thread/already and stole the legacy OCR-still
        // lane from the student router. Latin keywords are
        // word-boundary matched now (CJK stays substring — no word
        // boundaries there); the -ing form keeps its legacy routing.
        for (q in listOf(
            "is this bread fresh?",
            "what thread count are these sheets?",
            "i already asked about this",
        )) {
            val d = VyzeShadowRouter.decide(
                RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = q),
                snapshot(),
            )
            assertNotEquals(q, RouterDecision.Action.VLM_TEXT_READ, d.action)
        }
        val reading = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "start reading now"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, reading.action)
    }

    @Test
    fun `empty speech is ignored`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "   "),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `speech is case-insensitive on reading keywords`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "READ THIS LABEL"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    // ── Snapshot influence ────────────────────────────────────────

    @Test
    fun `engine-not-ready annotates the reason but keeps the legacy action`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "what is this"),
            snapshot(engineReady = false),
        )
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
        assertTrue(d.reason.contains("engine NOT ready"))
    }

    @Test
    fun `decide is pure - same input same output`() {
        val signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "apa ini")
        val s = snapshot()
        assertEquals(VyzeShadowRouter.decide(signal, s), VyzeShadowRouter.decide(signal, s))
    }

    // NOTE (P4 #105, 2026-11-09): the SessionEpisodeManager section lived
    // here — deleted with the manager (production never opened an episode).
}

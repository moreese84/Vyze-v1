package com.vyze.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the E0 per-frame budget probe (plan §E0).
 *
 * The probe is the instrument the E2-vs-E3 fork reads, so the pins here
 * are the fork itself: T0→first-audio pairing, the skip/abandon counters
 * that keep laziness OUT of the latency mean, and the exact verdict
 * thresholds (≤2.5 s → E2_VIABLE, ≥5 s → E3_PREREQUISITE, between →
 * AMBIGUOUS, <5 samples → INSUFFICIENT_DATA).
 *
 * Deterministic: an injected clock stepped by the test — no sleeps.
 */
class FrameBudgetProbeTest {

    private var clock = 10_000L
    private fun probe(capacity: Int = FrameBudgetProbe.DEFAULT_CAPACITY) =
        FrameBudgetProbe(nowMs = { clock }, capacity = capacity)

    /** One full continuous turn: capture, wait `ms`, first audio. */
    private fun FrameBudgetProbe.answerAfter(ms: Long, thermal: String = "GREEN") {
        beginFrame(thermal)
        clock += ms
        onFirstAudio()
    }

    // ── Pairing ──────────────────────────────────────────────────

    @Test
    fun `open then first audio pairs the frame and returns the sample`() {
        val p = probe()
        val t0 = p.beginFrame("GREEN")
        assertEquals(10_000L, t0)
        clock += 1_842
        val s = p.onFirstAudio()
        assertEquals(FrameBudgetProbe.Outcome.ANSWERED, s?.outcome)
        assertEquals(1_842L, s?.frameToAudioMs)
        assertEquals("GREEN", s?.thermalTier)
        assertEquals(1L, s?.index)

        val sum = p.summary()
        assertEquals(1, sum.answered)
        assertEquals(1_842L, sum.avgMs)
        // 1 sample < MIN_SAMPLES → no verdict, never a lucky guess.
        assertEquals(FrameBudgetProbe.Verdict.INSUFFICIENT_DATA, sum.verdict)
        assertNull(sum.openFrameT0Ms)
    }

    @Test
    fun `first audio with no open frame is ignored - other lanes cannot pollute`() {
        val p = probe()
        // Tap/voice/tutorial speech arrives with no continuous window open.
        assertNull(p.onFirstAudio())
        assertNull(p.onFirstAudio())
        val sum = p.summary()
        assertEquals(0, sum.answered)
        assertEquals(0, sum.sceneSkipped)
        assertEquals(0, sum.abandoned)
        assertEquals(FrameBudgetProbe.Verdict.INSUFFICIENT_DATA, sum.verdict)
    }

    @Test
    fun `audio while a frame is open pairs to that frame - single open window`() {
        val p = probe()
        p.beginFrame("GREEN")
        clock += 3_000
        // First chunk pairs; the SECOND chunk's onStart must find no window.
        assertNotNull(p.onFirstAudio())
        assertNull(p.onFirstAudio())
        assertEquals(1, p.summary().answered)
    }

    // ── Skip / abandon separation (the anti-fake-verdict pins) ───

    @Test
    fun `scene-gate skips are counted but never enter the latency mean`() {
        val p = probe()
        // 6 skipped ticks — a lazy average over these would say ~0 ms.
        repeat(6) {
            p.beginFrame("GREEN")
            clock += 1
            val s = p.onSceneSkip()
            assertEquals(FrameBudgetProbe.Outcome.SCENE_SKIPPED, s?.outcome)
            assertNull(s?.frameToAudioMs)
        }
        // One real answer at 4 s.
        p.answerAfter(4_000)
        val sum = p.summary()
        assertEquals(6, sum.sceneSkipped)
        assertEquals(1, sum.answered)
        assertEquals("skips leaked into the mean", 4_000L, sum.avgMs)
        assertEquals(FrameBudgetProbe.Verdict.INSUFFICIENT_DATA, sum.verdict)
    }

    @Test
    fun `superseded frames close as ABANDONED and are counted, not averaged`() {
        val p = probe()
        p.beginFrame("GREEN")
        clock += 500
        // Next capture supersedes: no audio ever arrived for frame 1.
        p.beginFrame("AMBER")
        clock += 2_000
        p.onFirstAudio()
        val sum = p.summary()
        assertEquals(1, sum.answered)
        assertEquals(1, sum.abandoned)
        assertEquals("abandoned window leaked into the mean", 2_000L, sum.avgMs)
        // Snapshot keeps both, with the honest outcome per frame.
        val snap = p.snapshot()
        assertEquals(FrameBudgetProbe.Outcome.ABANDONED, snap[0].outcome)
        assertEquals(FrameBudgetProbe.Outcome.ANSWERED, snap[1].outcome)
        // Thermal tier is per-frame (frame 2 was opened under AMBER).
        assertEquals("AMBER", snap[1].thermalTier)
    }

    // ── Verdict thresholds (the fork itself) ─────────────────────

    @Test
    fun `verdict boundary - exactly 2500ms average is E2_VIABLE`() {
        val p = probe()
        repeat(FrameBudgetProbe.MIN_SAMPLES_FOR_VERDICT) { p.answerAfter(2_500) }
        assertEquals(
            FrameBudgetProbe.Verdict.E2_VIABLE,
            p.summary().verdict,
        )
    }

    @Test
    fun `verdict boundary - exactly 5s average is E3_PREREQUISITE`() {
        val p = probe()
        repeat(FrameBudgetProbe.MIN_SAMPLES_FOR_VERDICT) { p.answerAfter(5_000) }
        assertEquals(
            FrameBudgetProbe.Verdict.E3_PREREQUISITE,
            p.summary().verdict,
        )
    }

    @Test
    fun `verdict - between the thresholds is AMBIGUOUS (spend prompt weight first)`() {
        val p = probe()
        repeat(FrameBudgetProbe.MIN_SAMPLES_FOR_VERDICT) { p.answerAfter(3_500) }
        assertEquals(FrameBudgetProbe.Verdict.AMBIGUOUS, p.summary().verdict)
    }

    @Test
    fun `verdict - one frame below the sample floor stays INSUFFICIENT_DATA`() {
        val p = probe()
        repeat(FrameBudgetProbe.MIN_SAMPLES_FOR_VERDICT - 1) { p.answerAfter(1_000) }
        assertEquals(FrameBudgetProbe.Verdict.INSUFFICIENT_DATA, p.summary().verdict)
        // ...and the same runs WITH the floor flips to a real verdict.
        p.answerAfter(1_000)
        assertEquals(FrameBudgetProbe.Verdict.E2_VIABLE, p.summary().verdict)
    }

    // ── Aggregates + ring ────────────────────────────────────────

    @Test
    fun `percentiles are nearest-rank over answered frames only`() {
        val p = probe()
        // 5 answered: 1s 2s 3s 4s 5s (+ skipped + abandoned outliers).
        listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L).forEach { p.answerAfter(it) }
        p.beginFrame("GREEN"); clock += 1; p.onSceneSkip()
        p.beginFrame("GREEN"); clock += 60_000 // 60 s with no audio…
        // …and the NEXT capture supersedes it → counted as ABANDONED,
        // still never averaged (the anti-fake-verdict separation).
        p.beginFrame("GREEN")

        val sum = p.summary()
        assertEquals(5, sum.answered)
        assertEquals(1, sum.sceneSkipped)
        assertEquals(1, sum.abandoned)        // …superseded → counted above
        assertEquals(3_000L, sum.avgMs)
        assertEquals(3_000L, sum.p50Ms)       // ceil(0.5·5) = 3rd value
        assertEquals(5_000L, sum.p95Ms)       // ceil(0.95·5) = 5th value
        assertEquals(5_000L, sum.maxMs)
        // 3,000 avg → AMBIGUOUS: the §E0 middle band really is reachable.
        assertEquals(FrameBudgetProbe.Verdict.AMBIGUOUS, sum.verdict)
    }

    @Test
    fun `ring drops oldest samples beyond capacity`() {
        val p = probe(capacity = 3)
        repeat(5) { p.answerAfter(1_000L + it * 100) }
        val snap = p.snapshot()
        assertEquals(3, snap.size)
        // Oldest two (index 1, 2) evicted — window keeps the freshest read.
        assertEquals(3L, snap.first().index)
        assertEquals(5L, snap.last().index)
        assertEquals(1_400L, p.summary().maxMs) // the newest frame (1000+400)
    }

    @Test
    fun `open frame is visible in the summary while awaiting audio`() {
        val p = probe()
        p.beginFrame("GREEN")
        assertEquals(10_000L, p.summary().openFrameT0Ms)
        clock += 100
        p.onSceneSkip()
        assertNull(p.summary().openFrameT0Ms)
    }
}

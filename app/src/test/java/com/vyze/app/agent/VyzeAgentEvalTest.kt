package com.vyze.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 6 JVM tests: shadow→live evaluation evidence
 * ([ShadowEvalRecorder] + [VyzeAgentRuntime.shouldPromoteToLive]).
 *
 * The flip-predicate tests seed the window END-TO-END — by running real
 * (fast, fake-op) queries through [VyzeAgentRuntime.tryLiveRouteTextOnly]
 * — so the tally reflects exactly what production would record.
 */
class VyzeAgentEvalTest {

    @Before
    fun isolate() {
        VyzeAgentRuntime.shadowEnabled = false // flag is per-process state
        VyzeAgentRuntime.resetLiveRunnerForTests()
        VyzeAgentRuntime.resetEvalForTests()
    }

    private fun snapshot(
        engineReady: Boolean = true,
        isInferring: Boolean = false,
        captureAvailable: Boolean = false,
    ) = RouterSnapshot(engineReady, isInferring, captureAvailable)

    private fun enabledRuntime() = VyzeAgentRuntime.apply { shadowEnabled = true }

    private suspend fun runQuery(
        rt: VyzeAgentRuntime,
        query: String = "what is that",
        thermalAllowed: Boolean = true,
        answer: String? = "ok",
        analyzeText: (suspend (String, String) -> String?)? = null,
    ): String? = rt.tryLiveRouteTextOnly(
        textOnlyQuery = query,
        snapshotProvider = { snapshot() },
        vlmInferenceAllowed = { thermalAllowed },
        analyzeText = analyzeText ?: { _, _ -> answer },
    )

    // ── Recorder math ─────────────────────────────────────────────

    @Test
    fun `summary tallies outcomes and averages answered latency only`() {
        val rec = ShadowEvalRecorder(capacity = 10)
        rec.record(ShadowEvalRecord("q1", "s1", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 100))
        rec.record(ShadowEvalRecord("q2", "s2", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 300))
        rec.record(ShadowEvalRecord("q3", null, ShadowEvalRecord.Outcome.DECLINED))
        rec.record(ShadowEvalRecord("q4", "s4", ShadowEvalRecord.Outcome.FAILED))
        rec.record(ShadowEvalRecord("q5", null, ShadowEvalRecord.Outcome.DECLINED_BUSY))

        val s = rec.summarize()
        assertEquals(5, s.total)
        assertEquals(2, s.answered)
        assertEquals(1, s.failed)
        assertEquals(1, s.declined)
        assertEquals(1, s.declinedBusy)
        assertEquals("avg latency must ignore non-answered rows", 200L, s.avgLatencyMs)
        assertEquals(0.4, s.successRate, 1e-9)
    }

    @Test
    fun `ring keeps only the most recent capacity observations`() {
        val rec = ShadowEvalRecorder(capacity = 3)
        for (i in 1..4) {
            rec.record(ShadowEvalRecord("q$i", "s$i", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 1))
        }
        assertEquals(3, rec.summarize().total)
        val ids = rec.snapshot().map { it.query }
        assertFalse("oldest row evicted", ids.contains("q1"))
        assertTrue("newest row kept", ids.contains("q4"))
    }

    @Test
    fun `empty window summarizes to zeros`() {
        val s = ShadowEvalRecorder().summarize()
        assertEquals(0, s.total)
        assertEquals(0.0, s.successRate, 1e-9)
        assertEquals(0L, s.avgLatencyMs)
    }

    // ── Flip predicate (seeded end-to-end through the runtime) ────

    @Test
    fun `promote is false while the window is too small`() = runBlocking {
        val rt = enabledRuntime()
        repeat(5) { runQuery(rt) }
        assertFalse(rt.shouldPromoteToLive())
    }

    @Test
    fun `promote is true at the promotion bar`() = runBlocking {
        val rt = enabledRuntime()
        // 24 answered of 30 = exactly the 0.80 rate, above both minimums.
        repeat(24) { assertEquals("ok", runQuery(rt)) }
        repeat(6) { assertNull(runQuery(rt, thermalAllowed = false)) }
        assertTrue(rt.shouldPromoteToLive())
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(24, s.answered)
        assertEquals(6, s.declined)
    }

    @Test
    fun `promote is false when the answer rate is below the bar`() = runBlocking {
        val rt = enabledRuntime()
        repeat(20) { runQuery(rt) }
        repeat(10) { runQuery(rt, thermalAllowed = false) } // declined, not failed
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(20, s.answered)
        assertFalse("20/30 = 0.67 < 0.80", rt.shouldPromoteToLive())
    }

    @Test
    fun `promote is false when failures dominate even with enough attempts`() = runBlocking {
        val rt = enabledRuntime()
        repeat(10) { runQuery(rt, answer = null) } // executed but FAILED
        repeat(20) { runQuery(rt, thermalAllowed = false) } // declined
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(0, s.answered)
        assertFalse(rt.shouldPromoteToLive())
    }

    @Test
    fun `declines are tallied as the baseline, never as failures`() = runBlocking {
        val rt = VyzeAgentRuntime // flag OFF → every call declines
        assertNull(runQuery(rt))
        assertNull(runQuery(rt))
        val s = rt.evalSummary()
        assertEquals(2, s.total)
        assertEquals(2, s.declined)
        assertEquals(0, s.failed)
    }

    @Test
    fun `a busy lane records DECLINED_BUSY, never FAILED`() = runBlocking {
        // Whole-app audit P3 #65 (2026-11-09): a turn refused for a busy
        // lane returned the same null as a genuine failure, so the eval
        // ring tallied it FAILED and DECLINED_BUSY was unreachable.
        val rt = enabledRuntime()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow: suspend (String, String) -> String? = { _, _ ->
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            "slow"
        }
        val first = async(Dispatchers.Default) {
            runQuery(rt, query = "busy one", analyzeText = slow)
        }
        assertTrue(
            "first turn never started",
            entered.await(10, java.util.concurrent.TimeUnit.SECONDS)
        )
        assertNull(runQuery(rt, query = "busy two"))
        release.countDown()
        assertEquals("slow", first.await())
        val s = rt.evalSummary()
        assertEquals(2, s.total)
        assertEquals(1, s.answered)
        assertEquals(1, s.declinedBusy)
        assertEquals(0, s.failed)
    }

    @Test
    fun `master lane leaves no orphan sessions after a normal turn`() = runBlocking {
        val rt = enabledRuntime()
        assertEquals("ok", runQuery(rt)) // one direct master-agent turn

        // The master lane deletes its ADK session in a finally on every
        // turn, so the in-memory store must hold ZERO lane sessions here —
        // no orphan sweep is needed anymore (single-master migration).
        val svc = AdkAgentManager.sessionServiceForInspection()
        if (svc != null) {
            val laneSessions = svc.listSessions(AdkAgentManager.APP_NAME, AdkAgentManager.USER_ID)
                .sessions.mapNotNull { it.key.id }
                .filter { it.startsWith("adk_master_") }
            assertTrue("master lane must not leak sessions: $laneSessions", laneSessions.isEmpty())
        }
    }

    // NOTE (P4 #105, 2026-11-09): the maintenanceTick tests lived here —
    // deleted with the tick (it only evicted from a store production
    // never populated). The orphan-session assert above is the real net.
}

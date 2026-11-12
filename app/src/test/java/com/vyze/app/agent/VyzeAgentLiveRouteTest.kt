package com.vyze.app.agent

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the live-route runtime
 * ([VyzeAgentRuntime.tryLiveRouteTextOnly]).
 *
 * Scope: grant/decline semantics over injected gates. Since Phase 5 the
 * granted path executes through the REAL ADK [com.google.adk.kt.runners
 * .InMemoryRunner] (runAsync event pipeline) with the injected engine op —
 * so these tests also prove the runner integration end-to-end on the JVM.
 * Android-context bindings remain with instrumented tests.
 */
class VyzeAgentLiveRouteTest {

    @org.junit.Before
    fun isolateRunner() {
        // The live runner caches its bound engine op; each test gets a fresh
        // construction so injections never leak across tests.
        VyzeAgentRuntime.resetLiveRunnerForTests()
    }

    private fun snapshot(
        engineReady: Boolean = true,
        isInferring: Boolean = false,
        captureAvailable: Boolean = false,
    ) = RouterSnapshot(engineReady, isInferring, captureAvailable)

    private fun runtime(): VyzeAgentRuntime = VyzeAgentRuntime

    private fun enabledRuntime() = runtime().apply { shadowEnabled = true }

    // ── Grants ────────────────────────────────────────────────────

    @Test
    fun `voice query grants and returns the engine answer`() = runBlocking {
        val rt = enabledRuntime()
        var gotPrompt: String? = null
        var gotSession: String? = null
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "what is paracetamol used for?",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { prompt, sid ->
                gotPrompt = prompt; gotSession = sid; "A pain reliever."
            },
        )
        // The op receives the ASSEMBLED prompt (persona + style +
        // question), verbatim per VyzeQueryContext.instructionFor.
        assertEquals(
            VyzeQueryContext().instructionFor("what is paracetamol used for?"),
            gotPrompt
        )
        assertTrue("session id must use the adk_master namespace", gotSession!!.startsWith("adk_master_"))
        assertEquals("A pain reliever.", answer)
        // Context assembly traveled with the invocation (Phase 5): the agent
        // prepends the persona + style directives to the raw question.
        assertTrue(
            "prompt must carry the persona directive",
            gotPrompt!!.contains(VyzeQueryContext.DEFAULT_PERSONA_DIRECTIVE)
        )
        assertTrue(
            "prompt must carry the answer-style directive",
            gotPrompt!!.contains(VyzeQueryContext.DEFAULT_ANSWER_STYLE_DIRECTIVE)
        )
        assertTrue(
            "prompt must end with the raw user question",
            gotPrompt!!.contains("User question: what is paracetamol used for?")
        )
        // Delimiter audit: the no-echo output contract is the final text
        // before the generation boundary — never the raw user question.
        assertTrue(
            "prompt must end with the no-echo output contract",
            gotPrompt!!.endsWith(VyzeQueryContext.OUTPUT_CONTRACT_TAIL)
        )
        assertFalse(rt.isLiveGenerationActive)
    }

    // ── Declines ──────────────────────────────────────────────────

    @Test
    fun `shadow flag off declines without calling the engine`() = runBlocking {
        val rt = runtime().apply { shadowEnabled = false }
        var called = false
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "what is this",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> called = true; "x" },
        )
        assertNull(answer)
        assertFalse(called)
    }

    @Test
    fun `reading intent declines (frame required - sightless fail-safe)`() = runBlocking {
        val rt = enabledRuntime()
        var called = false
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "read the label for me",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> called = true; "x" },
        )
        assertNull(answer)
        assertFalse(called)
    }

    @Test
    fun `engine not ready declines`() = runBlocking {
        val rt = enabledRuntime()
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "why is the sky blue",
            snapshotProvider = { snapshot(engineReady = false) },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> "x" },
        )
        assertNull(answer)
    }

    @Test
    fun `thermal refusal declines`() = runBlocking {
        val rt = enabledRuntime()
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "why is the sky blue",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { false },
            analyzeText = { _, _ -> "x" },
        )
        assertNull(answer)
    }

    @Test
    fun `second concurrent generation declines instead of queueing`() = runBlocking {
        val rt = enabledRuntime()
        // Simulate an in-flight generation directly.
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.GlobalScope.launch {
            rt.tryLiveRouteTextOnly(
                textOnlyQuery = "first query",
                snapshotProvider = { snapshot() },
                vlmInferenceAllowed = { true },
                analyzeText = { _, _ ->
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    "first answer"
                },
            )
        }
        firstStarted.await()
        assertTrue(rt.isLiveGenerationActive)

        val second = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "second query",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> "second answer" },
        )
        assertNull("a busy agent lane must decline, never queue", second)
        releaseFirst.complete(Unit)
        job.join()
        assertFalse(rt.isLiveGenerationActive)
    }

    @Test
    fun `engine null answer maps to decline`() = runBlocking {
        val rt = enabledRuntime()
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "why is the sky blue",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> null },
        )
        assertNull(answer)
        assertFalse(rt.isLiveGenerationActive)
    }

    @Test
    fun `engine exception maps to decline not crash`() = runBlocking {
        val rt = enabledRuntime()
        val answer = rt.tryLiveRouteTextOnly(
            textOnlyQuery = "why is the sky blue",
            snapshotProvider = { snapshot() },
            vlmInferenceAllowed = { true },
            analyzeText = { _, _ -> error("engine blew up") },
        )
        assertNull(answer)
        assertFalse(rt.isLiveGenerationActive)
    }

    // ── Decision logging is unchanged (shadow funnel intact) ─────

    @Test
    fun `declined routes still produce a decision with a reason`() {
        val signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "read the sign")
        val d = VyzeShadowRouter.decide(signal, snapshot())
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
        assertTrue(d.reason.isNotBlank())
    }
}

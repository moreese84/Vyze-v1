package com.vyze.app.speech

import com.vyze.app.speech.VoiceEnginePolicy.Engine.GEMMA_PRIMARY
import com.vyze.app.speech.VoiceEnginePolicy.Engine.SYSTEM_RECOGNIZER
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [VoiceEnginePolicy] — the Design A ("Gemma always")
 * engine-selection contract. Pins the 2026-09-25 decision: the platform
 * recognizer's Mandarin behavior is unfixable app-side, so the flag
 * routes voice to the local engine on every tap, online included, with
 * hard fallback guarantees.
 */
class VoiceEnginePolicyTest {

    // ── Design A: flag on → local engine, online included ────────────

    @Test
    fun `flag on + online + engine usable routes to gemma`() {
        assertEquals(
            GEMMA_PRIMARY,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = true, deviceOffline = false,
                engineReady = true, engineIdle = true,
            )
        )
    }

    @Test
    fun `flag on + offline + engine usable routes to gemma`() {
        assertEquals(
            GEMMA_PRIMARY,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = true, deviceOffline = true,
                engineReady = true, engineIdle = true,
            )
        )
    }

    // ── Legacy contract preserved when the flag is dark ──────────────

    @Test
    fun `flag off + online routes to system recognizer`() {
        assertEquals(
            SYSTEM_RECOGNIZER,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = false, deviceOffline = false,
                engineReady = true, engineIdle = true,
            )
        )
    }

    @Test
    fun `flag off + offline still routes to gemma (original contract)`() {
        // The original offline-first behavior must survive Design A.
        assertEquals(
            GEMMA_PRIMARY,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = false, deviceOffline = true,
                engineReady = true, engineIdle = true,
            )
        )
    }

    // ── Fallback guarantees: a dead local engine never kills the mic ──

    @Test
    fun `engine not ready falls back to system recognizer even with flag on`() {
        assertEquals(
            SYSTEM_RECOGNIZER,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = true, deviceOffline = false,
                engineReady = false, engineIdle = true,
            )
        )
    }

    @Test
    fun `engine busy falls back to system recognizer even with flag on`() {
        // One generation at a time — a running analysis is never preempted.
        assertEquals(
            SYSTEM_RECOGNIZER,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = true, deviceOffline = true,
                engineReady = true, engineIdle = false,
            )
        )
    }

    @Test
    fun `engine not ready + offline still falls back to system recognizer`() {
        // Unreachable in practice (offline always has the engine), but the
        // policy must still guarantee a live mic.
        assertEquals(
            SYSTEM_RECOGNIZER,
            VoiceEnginePolicy.chooseEngine(
                gemmaAlwaysEnabled = true, deviceOffline = true,
                engineReady = false, engineIdle = true,
            )
        )
    }
}

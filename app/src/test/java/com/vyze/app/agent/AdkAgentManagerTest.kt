package com.vyze.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for engine-op rebinding (whole-app audit P2 #38,
 * 2026-11-09).
 *
 * [AdkAgentManager.rebindEngine] wrote the bridgeProvider FIELD, but the
 * master agent had captured the initialize-time PARAMETER — the field was
 * never read, so every rebind (including the per-turn
 * [AdkAgentManager.executeWithBridge]) was a silent no-op and turns kept
 * resolving the stale engine op. The agent now resolves the current
 * field per turn, as the field's own KDoc always claimed.
 *
 * End-to-end through the real ADK runner with fake bridges: the answer
 * must come from the REBOUND bridge, never the initialize-time one.
 * Global-singleton isolation via [AdkAgentManager.resetForTests].
 */
class AdkAgentManagerTest {

    @Test
    fun `rebindEngine retargets the next turn`() = runBlocking {
        AdkAgentManager.resetForTests()
        try {
            val bridgeA = MasterModelBridge(
                generateText = { _, _ -> "from-A" },
                generateImage = { _, _, _ -> "from-A" },
            )
            val bridgeB = MasterModelBridge(
                generateText = { _, _ -> "from-B" },
                generateImage = { _, _, _ -> "from-B" },
            )
            AdkAgentManager.initialize(
                bridgeProvider = { bridgeA },
                frameProvider = { null },
                ocrFrame = { null },
                speakImmediate = { false },
                hapticPattern = {},
                forceRebuild = true,
            )
            val result = AdkAgentManager.executeWithBridge(
                bridge = bridgeB,
                query = "what is that",
            )
            // Pre-fix the agent kept resolving bridgeA ("from-A").
            assertEquals(
                "from-B",
                (result as AdkAgentManager.TurnResult.Answered).text
            )
        } finally {
            AdkAgentManager.resetForTests()
        }
    }

    @Test
    fun `concurrent second turn declines busy instead of queueing`() = runBlocking {
        // Whole-app audit P3 #65 (2026-11-09): a turn refused for a busy
        // lane returned the same null as a genuine failure, so the eval
        // ring tallied it FAILED and DECLINED_BUSY was unreachable.
        // execute() now returns a sealed TurnResult instead.
        AdkAgentManager.resetForTests()
        try {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val slowBridge = MasterModelBridge(
                generateText = { _, _ ->
                    entered.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    "slow-answer"
                },
                generateImage = { _, _, _ -> "slow-answer" },
            )
            AdkAgentManager.initialize(
                bridgeProvider = { slowBridge },
                frameProvider = { null },
                ocrFrame = { null },
                speakImmediate = { false },
                hapticPattern = {},
                forceRebuild = true,
            )
            val first = async(Dispatchers.Default) {
                AdkAgentManager.execute("slow query")
            }
            assertTrue(
                "first turn never started",
                entered.await(10, java.util.concurrent.TimeUnit.SECONDS)
            )
            val second = AdkAgentManager.execute("second query")
            assertEquals(AdkAgentManager.TurnResult.DeclinedBusy, second)
            release.countDown()
            assertEquals(
                "slow-answer",
                (first.await() as AdkAgentManager.TurnResult.Answered).text
            )
        } finally {
            AdkAgentManager.resetForTests()
        }
    }

    @Test
    fun `null engine answer maps to failed`() = runBlocking {
        AdkAgentManager.resetForTests()
        try {
            val nullBridge = MasterModelBridge(
                generateText = { _, _ -> null },
                generateImage = { _, _, _ -> null },
            )
            AdkAgentManager.initialize(
                bridgeProvider = { nullBridge },
                frameProvider = { null },
                ocrFrame = { null },
                speakImmediate = { false },
                hapticPattern = {},
                forceRebuild = true,
            )
            val result = AdkAgentManager.executeWithBridge(
                bridge = nullBridge,
                query = "what is that",
            )
            assertEquals(AdkAgentManager.TurnResult.Failed, result)
        } finally {
            AdkAgentManager.resetForTests()
        }
    }
}

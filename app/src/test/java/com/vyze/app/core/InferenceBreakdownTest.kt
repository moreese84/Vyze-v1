package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM pins for the inference timing wire format ([InferenceBreakdown]).
 *
 * The `[PIPELINE-BREAKDOWN]` / `[ANALYZE-BREAKDOWN]` / `[TEXT-BREAKDOWN]`
 * crash-log lines are the Tier-3 latency investigation surface: every
 * prefill-vs-decode claim is read off them with grep, so their field
 * order and spelling are a contract. In particular each line must carry
 * the serving backend (CPU vs GPU swap landed?) and the device tier —
 * without those, a 38 s prefill cannot be attributed to a cause.
 */
class InferenceBreakdownTest {

    private fun sample() = InferenceBreakdown(
        backend = "CPU",
        tier = "TIER_3",
        threads = 4,
        totalMs = 40_180L,
        tPreprocess = 12L,
        tJpeg = 34L,
        tPayload = 5L,
        tLane = 6L,
        tPrefill = 38_123L,
        decodeSoFarMs = 2_000L,
        decodeMs = 2_000L,
        streamedFirstToken = true,
        promptChars = 1400,
        outChars = 96,
    )

    @Test
    fun `pipeline line carries backend tier and every stage timing`() {
        assertEquals(
            "[PIPELINE-BREAKDOWN] backend=CPU tier=TIER_3 threads=4 " +
                "preprocess=12ms jpeg=34ms payload=5ms lane-wait=6ms " +
                "PREFILL=38123ms DECODE=2000ms-so-far",
            sample().formatPipeline()
        )
    }

    @Test
    fun `analyze line carries backend tier total and output size`() {
        assertEquals(
            "[ANALYZE-BREAKDOWN] backend=CPU tier=TIER_3 total=40180ms " +
                "preprocess=12ms jpeg=34ms payload=5ms lane-wait=6ms " +
                "prefill(firstToken)=38123ms decode=2000ms " +
                "prompt=1400chars out=96chars",
            sample().formatAnalyze()
        )
    }

    @Test
    fun `analyze line marks the no-streamed-token case honestly`() {
        val line = sample().copy(streamedFirstToken = false, decodeMs = 0L).formatAnalyze()
        assertEquals(
            "[ANALYZE-BREAKDOWN] backend=CPU tier=TIER_3 total=40180ms " +
                "preprocess=12ms jpeg=34ms payload=5ms lane-wait=6ms " +
                "prefill(firstToken)=38123ms decode=0ms " +
                "(no streamed token — single-message response) " +
                "prompt=1400chars out=96chars",
            line
        )
    }

    @Test
    fun `text lane line carries backend tier and prefill-decode split`() {
        assertEquals(
            "[TEXT-BREAKDOWN] backend=CPU tier=TIER_3 total=40180ms " +
                "prefill(firstToken)=38123ms decode=2000ms " +
                "prompt=1400chars out=96chars",
            sample().formatText()
        )
    }

    // ── Lane/prefill/decode split (audit P2 #67, 2026-11-09) ──
    // The old math measured lane-wait and prefill as the SAME quantity
    // (firstToken - stageStart) and printed it under both names, while
    // the PIPELINE line's DECODE-so-far was the preprocess sum. The
    // split below separates them off the generation-start instant.

    @Test
    fun `split separates lane prefill and decode`() {
        val split = companionSplitInferenceTiming(
            stageStartMs = 1_000L,
            generationStartMs = 1_500L,
            firstTokenAtMs = 6_500L,
            endMs = 9_000L
        )
        assertEquals(500L, split.laneMs)
        assertEquals(5_000L, split.prefillMs)
        assertEquals(2_500L, split.decodeMs)
    }

    @Test
    fun `split parts sum to the measured wall`() {
        val split = companionSplitInferenceTiming(
            stageStartMs = 1_000L,
            generationStartMs = 1_500L,
            firstTokenAtMs = 6_500L,
            endMs = 9_000L
        )
        assertEquals(9_000L - 1_000L, split.laneMs + split.prefillMs + split.decodeMs)
    }

    @Test
    fun `no streamed token keeps lane but zeroes prefill and decode`() {
        val split = companionSplitInferenceTiming(
            stageStartMs = 1_000L,
            generationStartMs = 1_500L,
            firstTokenAtMs = 0L,
            endMs = 9_000L
        )
        assertEquals(500L, split.laneMs)
        assertEquals(0L, split.prefillMs)
        assertEquals(0L, split.decodeMs)
    }

    @Test
    fun `generation never started zeroes everything`() {
        val split = companionSplitInferenceTiming(
            stageStartMs = 1_000L,
            generationStartMs = 0L,
            firstTokenAtMs = 0L,
            endMs = 9_000L
        )
        assertEquals(0L, split.laneMs)
        assertEquals(0L, split.prefillMs)
        assertEquals(0L, split.decodeMs)
    }

    @Test
    fun `same-millisecond boundaries collapse to zero`() {
        val split = companionSplitInferenceTiming(
            stageStartMs = 1_000L,
            generationStartMs = 1_500L,
            firstTokenAtMs = 1_500L,
            endMs = 1_500L
        )
        assertEquals(500L, split.laneMs)
        assertEquals(0L, split.prefillMs)
        assertEquals(0L, split.decodeMs)
    }
}

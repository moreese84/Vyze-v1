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
}

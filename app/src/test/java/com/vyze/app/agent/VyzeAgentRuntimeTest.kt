package com.vyze.app.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for [VyzeAgentRuntime.releaseForTests] (whole-app audit
 * P3 #79): the full lane-state release must restore every lane flag to
 * its SHIPPED default, so no state leaks across tests. Self-cleaning
 * via finally — a failure here must never darken or brighten siblings.
 *
 * (P4 #105 retired the episode store the original version of this test
 * also covered — production never opened an episode.)
 */
class VyzeAgentRuntimeTest {

    @Test
    fun `pre-gate ships lit by default`() {
        // Promotion 2026-11-14: junk transcripts skip the VLM out of the
        // box. Any default flip (including the kill switch) must update
        // this pin deliberately, not by accident.
        assertTrue(VyzeAgentRuntime.preGateEnabled)
    }

    @Test
    fun `releaseForTests restores shipped defaults`() {
        VyzeAgentRuntime.shadowEnabled = true
        VyzeAgentRuntime.preGateEnabled = false
        VyzeAgentRuntime.gemmaAlwaysEnabled = true
        try {
            VyzeAgentRuntime.releaseForTests()
            assertFalse(VyzeAgentRuntime.shadowEnabled)
            assertTrue(VyzeAgentRuntime.preGateEnabled)
            assertFalse(VyzeAgentRuntime.gemmaAlwaysEnabled)
        } finally {
            VyzeAgentRuntime.releaseForTests()
        }
    }
}

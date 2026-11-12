package com.vyze.app.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for [VyzeAgentRuntime.releaseForTests] (whole-app audit
 * P3 #79): the full lane-state release must clear every lane flag, so
 * no state leaks across tests. Self-cleaning via finally — a failure
 * here must never darken or brighten siblings.
 *
 * (P4 #105 retired the episode store the original version of this test
 * also covered — production never opened an episode.)
 */
class VyzeAgentRuntimeTest {

    @Test
    fun `releaseForTests clears lane flags`() {
        VyzeAgentRuntime.shadowEnabled = true
        VyzeAgentRuntime.preGateEnabled = true
        VyzeAgentRuntime.gemmaAlwaysEnabled = true
        try {
            VyzeAgentRuntime.releaseForTests()
            assertFalse(VyzeAgentRuntime.shadowEnabled)
            assertFalse(VyzeAgentRuntime.preGateEnabled)
            assertFalse(VyzeAgentRuntime.gemmaAlwaysEnabled)
        } finally {
            VyzeAgentRuntime.releaseForTests()
        }
    }
}

package com.vyze.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM pins for [SingleFlight]: concurrent callers join one execution,
 * late callers start a fresh one, failures propagate to every joiner
 * without wedging the gate.
 */
class SingleFlightTest {

    @Test
    fun `concurrent callers run the block once and share the result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = SingleFlight<Boolean>(scope)
            val runs = AtomicInteger(0)
            val results = (1..8).map {
                async {
                    gate.run {
                        runs.incrementAndGet()
                        delay(100)
                        true
                    }
                }
            }.awaitAll()
            assertEquals(1, runs.get())
            assertEquals(8, results.size)
            assertTrue(results.all { it })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a caller after completion starts a fresh execution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = SingleFlight<Int>(scope)
            val runs = AtomicInteger(0)
            assertEquals(1, gate.run { runs.incrementAndGet() })
            assertEquals(2, gate.run { runs.incrementAndGet() })
            assertEquals(2, runs.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `block failure reaches every joiner and the gate stays usable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = SingleFlight<Boolean>(scope)
            val runs = AtomicInteger(0)
            val failures = (1..4).map {
                async {
                    try {
                        gate.run {
                            runs.incrementAndGet()
                            delay(50)
                            throw IllegalStateException("boom")
                        }
                        fail("joiner should have thrown")
                        false
                    } catch (e: IllegalStateException) {
                        true
                    }
                }
            }.awaitAll()
            assertEquals(1, runs.get())
            assertTrue(failures.all { it })
            // Next wave retries instead of replaying the failure.
            assertEquals(true, gate.run { true })
            assertEquals(1, runs.get())
        } finally {
            scope.cancel()
        }
    }
}

package com.vyze.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [TurnTelemetryLatch] (audit #64).
 *
 * The latch is the one-shot arming primitive behind turn telemetry: exactly
 * one row per armed turn no matter how many completion paths race (engine
 * callback vs watchdog vs user cancel). All tests invoke the production
 * latch — no reimplementation (audit #98).
 */
class TurnTelemetryLatchTest {

    @Test
    fun `consume on unarmed latch returns null`() {
        assertNull(TurnTelemetryLatch().consume())
    }

    @Test
    fun `arm then consume returns snapshot with task type and start`() {
        val latch = TurnTelemetryLatch()
        latch.arm("scene_direct", 1000L)
        val snap = latch.consume()
        assertNotNull(snap)
        assertEquals("scene_direct", snap!!.taskType)
        assertEquals(1000L, snap.startMs)
    }

    @Test
    fun `second consume returns null - one-shot`() {
        val latch = TurnTelemetryLatch()
        latch.arm("scene_direct", 1000L)
        assertNotNull(latch.consume())
        assertNull(latch.consume())
    }

    @Test
    fun `disarm without consume suppresses the row`() {
        val latch = TurnTelemetryLatch()
        latch.arm("scene_direct", 1000L)
        latch.disarm()
        assertNull(latch.consume())
    }

    @Test
    fun `disarm on unarmed latch is a no-op`() {
        val latch = TurnTelemetryLatch()
        latch.disarm()
        assertNull(latch.consume())
    }

    @Test
    fun `retag changes task type and keeps start`() {
        val latch = TurnTelemetryLatch()
        latch.arm("ocr_first", 2000L)
        latch.retag("ocr_fast_path_degradation")
        val snap = latch.consume()
        assertNotNull(snap)
        assertEquals("ocr_fast_path_degradation", snap!!.taskType)
        assertEquals(2000L, snap.startMs)
    }

    @Test
    fun `retag on unarmed latch is a no-op`() {
        val latch = TurnTelemetryLatch()
        latch.retag("scene_direct")
        assertNull(latch.consume())
    }

    @Test
    fun `re-arm overwrites the pending snapshot`() {
        val latch = TurnTelemetryLatch()
        latch.arm("scene_direct", 1000L)
        latch.arm("text_only", 3000L)
        val snap = latch.consume()
        assertNotNull(snap)
        assertEquals("text_only", snap!!.taskType)
        assertEquals(3000L, snap.startMs)
    }

    @Test
    fun `isArmed tracks arm consume and disarm`() {
        val latch = TurnTelemetryLatch()
        assertFalse(latch.isArmed())
        latch.arm("scene_direct", 1000L)
        assertTrue(latch.isArmed())
        latch.consume()
        assertFalse(latch.isArmed())
        latch.arm("scene_direct", 1000L)
        assertTrue(latch.isArmed())
        latch.disarm()
        assertFalse(latch.isArmed())
    }

    @Test
    fun `concurrent consume has exactly one winner`() {
        val latch = TurnTelemetryLatch()
        latch.arm("scene_direct", 1000L)
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        repeat(threads) {
            Thread {
                start.await()
                if (latch.consume() != null) winners.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, winners.get())
    }
}

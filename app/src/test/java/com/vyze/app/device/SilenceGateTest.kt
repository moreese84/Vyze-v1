package com.vyze.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SilenceGate] — the L1 noise-floor calibration behind the
 * offline-voice latency fix (latency lever #2). The gate is pure math:
 * samples in → threshold out, no Android. These tests pin the contract:
 *
 *  - Uncalibrated gates behave like the old fixed 0.015 floor (safety net)
 *  - Calibration derives the threshold RELATIVE to the measured floor
 *  - A noisy room raises the gate (no more full-8s ambient recordings)
 *  - A loud room can never lock the gate (ABS_MAX safety valve)
 *  - Malformed calibration input leaves the fallback state untouched
 */
class SilenceGateTest {

    private fun FloatArray.rms(): Float {
        var sumSq = 0.0
        for (s in this) sumSq += s.toDouble() * s
        return Math.sqrt(sumSq / size).toFloat()
    }

    /** Fills `n` samples with a constant amplitude (positive half-wave). */
    private fun constant(n: Int, amplitude: Float): FloatArray =
        FloatArray(n) { amplitude }

    // ── Uncalibrated behavior: old contract preserved ──────────────────

    @Test
    fun `uncalibrated gate uses the fixed fallback threshold`() {
        val gate = SilenceGate()
        assertEquals(SilenceGate.FALLBACK_FLOOR_RMS * SilenceGate.MARGIN, gate.thresholdRms, 1e-6f)
        assertFalse(gate.isCalibrated)
    }

    @Test
    fun `uncalibrated gate classifies like the old fixed floor`() {
        val gate = SilenceGate()
        // 0.01 RMS speech-below threshold → silence; 0.05 → speech
        assertTrue(gate.isSilence(0.01f))
        assertFalse(gate.isSilence(0.05f))
    }

    // ── Calibration: relative threshold ─────────────────────────────────

    @Test
    fun `calibration sets floor and raises threshold above it`() {
        val gate = SilenceGate()
        val ambient = constant(1600, 0.05f) // 0.05 RMS room tone
        gate.calibrate(ambient)
        assertTrue(gate.isCalibrated)
        assertEquals(0.05f, gate.floorRms, 1e-3f)
        // threshold = max(floor × 1.8, floor + 0.012), clamped by ABS_MAX
        val expected = Math.min(Math.max(0.05f * 1.8f, 0.05f + 0.012f), SilenceGate.ABS_MAX)
        assertEquals(expected, gate.thresholdRms, 1e-4f)
        assertTrue(expected > 0.05f)
    }

    @Test
    fun `noisy room raises the gate above ambient but below speech`() {
        val gate = SilenceGate()
        gate.calibrate(constant(1600, 0.10f)) // loud room: 0.10 RMS ambient
        // Room tone itself now classifies as silence (was impossible before)
        assertTrue(gate.isSilence(0.10f))
        // A real voice well above the room floor still counts as speech
        assertFalse(gate.isSilence(0.25f))
    }

    @Test
    fun `quiet room keeps an absolute headroom above the floor`() {
        val gate = SilenceGate()
        gate.calibrate(constant(1600, 0.002f)) // near-silent room
        // Relative margin (0.0036) is below the absolute minimum headroom,
        // so the ABS_MIN_MARGIN term must win.
        assertEquals(0.002f + SilenceGate.ABS_MIN_MARGIN, gate.thresholdRms, 1e-5f)
    }

    @Test
    fun `very loud room is clamped by ABS_MAX so the gate never locks`() {
        val gate = SilenceGate()
        gate.calibrate(constant(1600, 0.5f)) // extreme ambient
        assertEquals(SilenceGate.ABS_MAX, gate.thresholdRms, 1e-4f)
        // Even at the clamp, room-level audio (0.3) classifies as silence
        assertTrue(gate.isSilence(0.3f))
        assertFalse(gate.isSilence(0.4f))
    }

    // ── Malformed input: fallback state survives ─────────────────────────

    @Test
    fun `null sample leaves fallback state untouched`() {
        val gate = SilenceGate()
        gate.calibrate(null)
        assertFalse(gate.isCalibrated)
        assertEquals(SilenceGate.FALLBACK_FLOOR_RMS, gate.floorRms, 1e-6f)
    }

    @Test
    fun `empty sample leaves fallback state untouched`() {
        val gate = SilenceGate()
        gate.calibrate(FloatArray(0))
        assertFalse(gate.isCalibrated)
    }

    @Test
    fun `all-zero buffer (dead mic) leaves fallback state untouched`() {
        val gate = SilenceGate()
        gate.calibrate(constant(1600, 0f))
        assertFalse(gate.isCalibrated)
        assertEquals(SilenceGate.FALLBACK_FLOOR_RMS * SilenceGate.MARGIN, gate.thresholdRms, 1e-6f)
    }

    @Test
    fun `out-of-bounds window is rejected`() {
        val gate = SilenceGate()
        val samples = constant(100, 0.1f)
        gate.calibrate(samples, offset = 50, length = 100) // 50+100 > 100
        assertFalse(gate.isCalibrated)
    }

    // ── Offset/length window (mirrors the real partial-read call) ────────

    @Test
    fun `calibration respects offset and length window`() {
        val gate = SilenceGate()
        val samples = FloatArray(3200) { i -> if (i < 1600) 0.1f else 0f }
        // Only the FIRST half is real ambient — the rest is untouched zeros
        gate.calibrate(samples, offset = 0, length = 1600)
        assertTrue(gate.isCalibrated)
        assertEquals(0.1f, gate.floorRms, 1e-3f)
    }

    // ── End-to-end: RMS of a serialized capture round-trips ──────────────

    @Test
    fun `rms helper produces stable classification across chunk boundaries`() {
        val gate = SilenceGate()
        gate.calibrate(constant(1600, 0.08f))
        // Speech chunk at 3× floor must classify as speech
        val speech = constant(480, 0.24f)
        assertFalse(gate.isSilence(speech.rms()))
        // Ambient-level chunk must classify as silence
        val ambient = constant(480, 0.08f)
        assertTrue(gate.isSilence(ambient.rms()))
    }
}

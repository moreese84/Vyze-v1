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

    // ── Failure mode 3: transient-poisoned calibration (2026-09-28) ────

    /** 300ms calibration window: 640 samples of thump + 4160 of ambient. */
    private fun thumpedWindow(thumpAmp: Float, ambientAmp: Float): FloatArray {
        val samples = FloatArray(4800) // 300ms at 16kHz = 15 chunks of 320
        for (i in 0 until 640) samples[i] = thumpAmp // the double-tap thump
        for (i in 640 until samples.size) samples[i] = ambientAmp
        return samples
    }

    @Test
    fun `desk thump in the calibration window cannot poison the floor`() {
        val gate = SilenceGate()
        // Device evidence 2026-09-28: a capture-start thump (~0.3 amplitude)
        // inside real quiet-room ambient (0.002) dragged the OVERALL-RMS
        // floor to ~0.11 — 50× the true room — and every ask was rejected.
        gate.calibrate(thumpedWindow(thumpAmp = 0.3f, ambientAmp = 0.002f))
        // The median-of-chunks floor must land on the AMBIENT, not the thump.
        assertEquals(0.002f, gate.floorRms, 1e-3f)
        // Threshold stays in speech-reachable territory.
        assertEquals(0.002f + SilenceGate.ABS_MIN_MARGIN, gate.thresholdRms, 1e-4f)
        // A normal voice (0.05) is speech under the repaired gate...
        assertFalse(gate.isSilence(0.05f))
        // ...and the ambient itself is silence.
        assertTrue(gate.isSilence(0.002f))
    }

    @Test
    fun `old overall-rms behavior would have been poisoned on the same window`() {
        // Regression mirror: pin WHY the median exists. The overall RMS of
        // the poisoned window is ~0.11 — with the old code the derived
        // threshold (~0.13+) sat ABOVE normal speech (0.05), deafening the
        // gate. If this ever equals the ambient floor again, the median
        // logic has regressed.
        val window = thumpedWindow(thumpAmp = 0.3f, ambientAmp = 0.002f)
        var sumSq = 0.0
        for (s in window) sumSq += s.toDouble() * s
        val overallRms = Math.sqrt(sumSq / window.size).toFloat()
        assertTrue("overall RMS should be thump-dominated", overallRms > 0.05f)

        val gate = SilenceGate()
        gate.calibrate(window)
        assertTrue(
            "median floor must sit far below the poisoned overall RMS",
            gate.floorRms < overallRms / 10f
        )
    }

    @Test
    fun `majority-loud window still raises the gate — genuine noisy rooms unchanged`() {
        val gate = SilenceGate()
        // 4160 samples of real 0.1 ambient + a brief 0.002 dip: the median
        // must land on the loud ambient (steady noise IS the floor).
        val samples = FloatArray(4800)
        for (i in 0 until 640) samples[i] = 0.002f
        for (i in 640 until samples.size) samples[i] = 0.1f
        gate.calibrate(samples)
        assertEquals(0.1f, gate.floorRms, 1e-3f)
        // Noisy-room contract intact: room tone is silence, voice is speech.
        assertTrue(gate.isSilence(0.1f))
        assertFalse(gate.isSilence(0.25f))
    }

    @Test
    fun `constant amplitude yields the same floor as the old overall-rms code`() {
        // Backward-compat invariant: for constant windows (all existing
        // callers' quiet-room behavior) median == overall RMS exactly.
        val gate = SilenceGate()
        gate.calibrate(constant(4800, 0.05f))
        assertEquals(0.05f, gate.floorRms, 1e-3f)
        val expected = Math.min(Math.max(0.05f * 1.8f, 0.05f + 0.012f), SilenceGate.ABS_MAX)
        assertEquals(expected, gate.thresholdRms, 1e-4f)
    }

    @Test
    fun `a thump spanning 140ms still cannot move the median`() {
        val gate = SilenceGate()
        // 7 of 15 chunks loud (~140ms burst): still a minority → ignored.
        val samples = FloatArray(4800)
        for (i in 0 until 2240) samples[i] = 0.3f
        for (i in 2240 until samples.size) samples[i] = 0.002f
        gate.calibrate(samples)
        assertEquals(0.002f, gate.floorRms, 1e-3f)
    }

    @Test
    fun `desk ring covering most of a short window is defeated by the 800ms quietest-half floor`() {
        // THE DEVICE- OBSERVED ESCALATION (2026-09-28 08:58): on a desk the
        // knock RINGS ~500ms — a plain median over a 300ms window still
        // landed on the ring (floor 0.0263, ask rejected). With the 800ms
        // window the ring decays inside it; the quietest half must find
        // the true ambient.
        val gate = SilenceGate()
        val samples = FloatArray(25600) // 800ms at 16kHz = 40 chunks of 320
        for (i in 0 until 8000) samples[i] = 0.3f   // 500ms of desk ring
        for (i in 8000 until samples.size) samples[i] = 0.002f // quiet tail
        gate.calibrate(samples)
        // Plain median over 40 chunks would be 0.3 (ring dominates 25/40).
        // The quietest half must land on the ambient instead.
        assertEquals(0.002f, gate.floorRms, 1e-3f)
        assertEquals(0.002f + SilenceGate.ABS_MIN_MARGIN, gate.thresholdRms, 1e-4f)
        // Normal speech is reachable again.
        assertFalse(gate.isSilence(0.05f))
    }

    @Test
    fun `persistent loud ambient still raises the gate even with the quietest-half rule`() {
        // The guardrail the other way: if the ROOM is genuinely loud for
        // the whole window, the quietest half must NOT hide it (a steady
        // noisy room is a real floor, not a transient).
        val gate = SilenceGate()
        gate.calibrate(constant(25600, 0.1f))
        assertEquals(0.1f, gate.floorRms, 1e-3f)
        assertTrue(gate.isSilence(0.1f))
        assertFalse(gate.isSilence(0.25f))
    }

    // ── Desk-occluded speech margin (failure mode 5, 2026-09-28) ──────

    @Test
    fun `desk muffled speech clears the bar in a near silent room`() {
        // Device truth 09:16–09:17: floor 0.0006–0.0078, speech peaks
        // 0.016–0.025 (desk muffles the voice). The old floor+0.012 bar
        // (0.0126–0.0198) halved the classified speech and rejected asks
        // at 280ms against the 300ms bar. With floor+0.006 the same
        // speech clears the gate ~3× over.
        val gate = SilenceGate()
        gate.calibrate(constant(25600, 0.0006f))
        assertEquals(0.0006f + SilenceGate.ABS_MIN_MARGIN, gate.thresholdRms, 1e-5f)
        assertFalse("desk-muffled speech must classify as speech", gate.isSilence(0.02f))
        assertTrue("room hiss at floor level stays silence", gate.isSilence(0.001f))
    }

    @Test
    fun `noisy rooms are unaffected by the margin change relative rule dominates`() {
        // At floor 0.05 the relative margin (×1.8 = 0.09) dominates the
        // absolute margin at either value — noisy-room behavior identical.
        val gate = SilenceGate()
        gate.calibrate(constant(4800, 0.05f))
        assertEquals(0.09f, gate.thresholdRms, 1e-4f)
    }
}

package com.vyze.app.ui.delegates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the capture-readiness gate
 * ([CameraSetupDelegate.readinessVerdict]) — the pure classifier that
 * decides whether a delivered analyzer frame has real image CONTENT.
 *
 * Device evidence (2026-09-30 corpus): 「您面前是纯绿色的背景」×2 — snapshots
 * taken in the camera surface-init window served a UNIFORM frame to the
 * VLM, which described a green background nobody was looking at. The gate
 * delays such captures a few frames instead of answering nonsense.
 *
 * Design contract pinned here:
 *  - UNIFORM is the ONLY refusing verdict (uniform surface, any color and
 *    any brightness — a green, black, or white init frame all qualify).
 *  - Dark TEXTURED scenes are TOO_DARK → served with a log line, never
 *    refused: the auto-torch system owns brightness, and dark scenes
 *    legitimately contain content.
 *  - Threshold boundaries behave as documented (variance ≤ max → UNIFORM;
 *    mean < dark floor with content → TOO_DARK).
 */
class CameraReadinessTest {

    // ── The hallucination class: uniform init frames ─────────────────

    @Test
    fun `uniform green init frame is refused`() {
        // Solid green RGB (0,255,0) → BT.601 luma ≈ 149.6, variance ≈ 0
        // (the actual device failure shape).
        assertEquals(
            CameraSetupDelegate.FrameReadiness.UNIFORM,
            CameraSetupDelegate.readinessVerdict(meanLuma = 149.6, variance = 0.0)
        )
    }

    @Test
    fun `uniform dark init frame is also refused`() {
        // A near-black init frame is just as content-free as a green one —
        // the verdict keys on VARIANCE, not color.
        assertEquals(
            CameraSetupDelegate.FrameReadiness.UNIFORM,
            CameraSetupDelegate.readinessVerdict(meanLuma = 3.0, variance = 0.5)
        )
    }

    @Test
    fun `uniform white init frame is also refused`() {
        assertEquals(
            CameraSetupDelegate.FrameReadiness.UNIFORM,
            CameraSetupDelegate.readinessVerdict(meanLuma = 240.0, variance = 1.2)
        )
    }

    // ── Real content passes ──────────────────────────────────────────

    @Test
    fun `textured scene frame is ready`() {
        // Real indoor scenes: sensor noise alone keeps sampled variance
        // far above the uniform bar (std ≈ 5 → var ≈ 25 floor).
        assertEquals(
            CameraSetupDelegate.FrameReadiness.READY,
            CameraSetupDelegate.readinessVerdict(meanLuma = 110.0, variance = 900.0)
        )
    }

    @Test
    fun `low-contrast but non-uniform frame is ready`() {
        // A wall at arm's length: nearly flat but with noise + shading
        // gradient — variance just above the bar must pass.
        assertEquals(
            CameraSetupDelegate.FrameReadiness.READY,
            CameraSetupDelegate.readinessVerdict(meanLuma = 95.0, variance = 26.0)
        )
    }

    // ── Dark scenes are served, never refused ────────────────────────

    @Test
    fun `dark textured scene is flagged but served - TOO_DARK not refused`() {
        // Night room with the torch off: genuinely dark, genuinely content.
        assertEquals(
            CameraSetupDelegate.FrameReadiness.TOO_DARK,
            CameraSetupDelegate.readinessVerdict(meanLuma = 8.0, variance = 60.0)
        )
    }

    // ── Boundary semantics ───────────────────────────────────────────

    @Test
    fun `variance exactly at the bar counts as uniform`() {
        assertEquals(
            CameraSetupDelegate.FrameReadiness.UNIFORM,
            CameraSetupDelegate.readinessVerdict(meanLuma = 100.0, variance = 25.0)
        )
    }

    @Test
    fun `mean exactly at the dark floor counts as ready`() {
        assertEquals(
            CameraSetupDelegate.FrameReadiness.READY,
            CameraSetupDelegate.readinessVerdict(meanLuma = 10.0, variance = 100.0)
        )
    }
}

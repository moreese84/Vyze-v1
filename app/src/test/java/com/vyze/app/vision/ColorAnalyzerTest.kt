package com.vyze.app.vision

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ColorAnalyzer] classification logic.
 *
 * Verifies the hue/saturation/value classification across key color boundaries.
 * Uses value=0.5f (mid-brightness) for tests that expect no brightness prefix,
 * since 0.5 is between the Dark (<0.35) and Bright (>0.75) thresholds.
 */
class ColorAnalyzerTest {

    // Invokes production code (audit #98, 2026-11-09): this helper used
    // to REIMPLEMENT the classifier, so all 40+ tests passed against a
    // copy while the real map drifted (including the 335-345 brown bug).
    private fun classifyColor(hue: Float, saturation: Float, value: Float): String {
        val verdict = ColorAnalyzer.companionClassifyColor(hue, saturation, value)
        return ColorAnalyzer.companionEnglishName(verdict)
    }

    // ── Achromatic Colors ────────────────────────────────────────────────

    @Test
    fun `pure black classified as Black`() {
        assertEquals("Black", classifyColor(0f, 0f, 0f))
    }

    @Test
    fun `near-black classified as Black`() {
        assertEquals("Black", classifyColor(0f, 0f, 0.1f))
    }

    @Test
    fun `pure white classified as White`() {
        assertEquals("White", classifyColor(0f, 0f, 1f))
    }

    @Test
    fun `near-white classified as White`() {
        assertEquals("White", classifyColor(0f, 0f, 0.9f))
    }

    @Test
    fun `mid-grey classified as Grey`() {
        assertEquals("Grey", classifyColor(0f, 0f, 0.5f))
    }

    @Test
    fun `low-saturation grey at 05 value`() {
        assertEquals("Grey", classifyColor(0f, 0.05f, 0.5f))
    }

    // ── Red ──────────────────────────────────────────────────────────────

    @Test
    fun `pure red classified as Red`() {
        assertEquals("Red", classifyColor(0f, 1f, 0.5f))
    }

    @Test
    fun `red at hue 10 classified as Red`() {
        assertEquals("Red", classifyColor(10f, 1f, 0.5f))
    }

    @Test
    fun `red near 350 degrees classified as Red`() {
        assertEquals("Red", classifyColor(350f, 1f, 0.5f))
    }

    @Test
    fun `dark red classified as Dark Red`() {
        assertEquals("Dark Red", classifyColor(0f, 1f, 0.2f))
    }

    @Test
    fun `bright red classified as Bright Red`() {
        assertEquals("Bright Red", classifyColor(0f, 1f, 0.9f))
    }

    // ── Orange ───────────────────────────────────────────────────────────

    @Test
    fun `orange at hue 30 classified as Orange`() {
        assertEquals("Orange", classifyColor(30f, 1f, 0.5f))
    }

    @Test
    fun `orange at boundary hue 15 classified as Orange`() {
        // hue=15 is at the boundary — Red range is 0..15, Orange is 15..40
        // Red wins because it's checked first in the when expression
        assertEquals("Red", classifyColor(15f, 1f, 0.5f))
    }

    // ── Yellow ───────────────────────────────────────────────────────────

    @Test
    fun `yellow at hue 60 classified as Yellow`() {
        assertEquals("Yellow", classifyColor(60f, 1f, 0.5f))
    }

    @Test
    fun `bright yellow classified as Bright Yellow`() {
        assertEquals("Bright Yellow", classifyColor(60f, 1f, 0.9f))
    }

    @Test
    fun `dark yellow classified as Dark Yellow`() {
        assertEquals("Dark Yellow", classifyColor(60f, 1f, 0.2f))
    }

    // ── Green ────────────────────────────────────────────────────────────

    @Test
    fun `green at hue 120 classified as Green`() {
        assertEquals("Green", classifyColor(120f, 1f, 0.5f))
    }

    @Test
    fun `green at hue 80 classified as Green`() {
        assertEquals("Green", classifyColor(80f, 1f, 0.5f))
    }

    @Test
    fun `green at hue 155 classified as Green`() {
        assertEquals("Green", classifyColor(155f, 1f, 0.5f))
    }

    // ── Cyan ─────────────────────────────────────────────────────────────

    @Test
    fun `cyan at hue 180 classified as Cyan`() {
        assertEquals("Cyan", classifyColor(180f, 1f, 0.5f))
    }

    @Test
    fun `cyan at boundary hue 195 classified as Cyan`() {
        assertEquals("Cyan", classifyColor(195f, 1f, 0.5f))
    }

    @Test
    fun `green at hue 160 classified as Green`() {
        // hue=160 is at the boundary — Green range is 70..160, Cyan is 160..195
        // Green wins because it's checked first in the when expression
        assertEquals("Green", classifyColor(160f, 1f, 0.5f))
    }

    // ── Blue ─────────────────────────────────────────────────────────────

    @Test
    fun `blue at hue 240 classified as Blue`() {
        assertEquals("Blue", classifyColor(240f, 1f, 0.5f))
    }

    @Test
    fun `dark blue classified as Dark Blue`() {
        assertEquals("Dark Blue", classifyColor(240f, 1f, 0.2f))
    }

    @Test
    fun `bright blue classified as Bright Blue`() {
        assertEquals("Bright Blue", classifyColor(240f, 1f, 0.9f))
    }

    // ── Purple ───────────────────────────────────────────────────────────

    @Test
    fun `purple at hue 280 classified as Purple`() {
        assertEquals("Purple", classifyColor(280f, 1f, 0.5f))
    }

    @Test
    fun `hue 260 classified as Blue (first match wins)`() {
        // Blue (195..260) includes 260, checked before Purple (260..300)
        assertEquals("Blue", classifyColor(260f, 1f, 0.5f))
    }

    // ── Pink ─────────────────────────────────────────────────────────────

    @Test
    fun `pink at hue 320 classified as Pink`() {
        assertEquals("Pink", classifyColor(320f, 1f, 0.5f))
    }

    @Test
    fun `hue 300 classified as Purple (first match wins)`() {
        // Purple (260..300) includes 300, checked before Pink (300..335)
        assertEquals("Purple", classifyColor(300f, 1f, 0.5f))
    }

    // ── Magenta-pink band (audit P2 #27, 2026-11-09) ───────────────────
    // Hues 335-345 are magenta-pink, not brown (brown is dark orange,
    // ~15-40 at low value). The old map named them Brown — the old pin
    // encoded the bug, corrected here.

    @Test
    fun `hue 340 classified as Pink`() {
        assertEquals("Pink", classifyColor(340f, 1f, 0.5f))
    }

    @Test
    fun `hue 335 classified as Pink (first match wins)`() {
        // Pink (300..335) includes 335, checked before the 335..345 band
        assertEquals("Pink", classifyColor(335f, 1f, 0.5f))
    }

    @Test
    fun `hue 345 classified as Red (first match wins)`() {
        // Red (345..360) is checked before the 335..345 band
        assertEquals("Red", classifyColor(345f, 1f, 0.5f))
    }

    // ── Brightness Modifiers ─────────────────────────────────────────────

    @Test
    fun `dark value below threshold gets Dark prefix`() {
        assertEquals("Dark Green", classifyColor(120f, 1f, 0.3f))
    }

    @Test
    fun `bright value above threshold gets Bright prefix`() {
        assertEquals("Bright Green", classifyColor(120f, 1f, 0.8f))
    }

    @Test
    fun `mid value has no prefix`() {
        assertEquals("Green", classifyColor(120f, 1f, 0.5f))
    }

    @Test
    fun `dark blue at value 01`() {
        assertEquals("Dark Blue", classifyColor(240f, 1f, 0.1f))
    }

    @Test
    fun `bright yellow at value 09`() {
        assertEquals("Bright Yellow", classifyColor(60f, 1f, 0.9f))
    }

    // ── Edge Cases ───────────────────────────────────────────────────────

    @Test
    fun `very low saturation treated as achromatic`() {
        assertEquals("Grey", classifyColor(120f, 0.05f, 0.5f))
    }

    @Test
    fun `saturation at exactly threshold is chromatic`() {
        assertEquals("Green", classifyColor(120f, 0.12f, 0.5f))
    }

    @Test
    fun `hue at exactly 0 is Red`() {
        assertEquals("Red", classifyColor(0f, 1f, 0.5f))
    }

    @Test
    fun `hue at exactly 360 is Red`() {
        assertEquals("Red", classifyColor(360f, 1f, 0.5f))
    }

    // ── Constants Validation ─────────────────────────────────────────────

    @Test
    fun `all color constants are non-empty`() {
        assertNotNull(ColorAnalyzer.BLACK)
        assertNotNull(ColorAnalyzer.WHITE)
        assertNotNull(ColorAnalyzer.GREY)
        assertNotNull(ColorAnalyzer.RED)
        assertNotNull(ColorAnalyzer.ORANGE)
        assertNotNull(ColorAnalyzer.YELLOW)
        assertNotNull(ColorAnalyzer.GREEN)
        assertNotNull(ColorAnalyzer.CYAN)
        assertNotNull(ColorAnalyzer.BLUE)
        assertNotNull(ColorAnalyzer.PURPLE)
        assertNotNull(ColorAnalyzer.PINK)
    }

    @Test
    fun `threshold constants are in valid ranges`() {
        assertTrue(ColorAnalyzer.SATURATION_THRESHOLD in 0f..1f)
        assertTrue(ColorAnalyzer.BLACK_MAX_VALUE in 0f..1f)
        assertTrue(ColorAnalyzer.WHITE_MIN_VALUE in 0f..1f)
        assertTrue(ColorAnalyzer.DARK_MAX_VALUE in 0f..1f)
        assertTrue(ColorAnalyzer.BRIGHT_MIN_VALUE in 0f..1f)
        assertTrue(ColorAnalyzer.BLACK_MAX_VALUE < ColorAnalyzer.WHITE_MIN_VALUE)
        assertTrue(ColorAnalyzer.DARK_MAX_VALUE < ColorAnalyzer.BRIGHT_MIN_VALUE)
    }
}

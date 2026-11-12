package com.vyze.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the OCR merge fixes (whole-app audit P2 #25/#81/#82,
 * 2026-11-09).
 *
 * - #25: element confidence is a platform type — a null unboxed here
 *   used to NPE the whole read into a silent (null, 0f).
 * - #81: both recognizers return the same printed line and the row join
 *   spoke it twice — intra-row containment dedup.
 * - #82: element-less lines voted a confident 0.85 into the merge
 *   average that gates the 0.85 fast-path — unknown confidence is now
 *   excluded from the average instead of inventing evidence.
 */
class OcrHelperTest {

    private fun line(text: String, confidence: Float?): OcrHelper.OcrLine =
        OcrHelper.OcrLine(top = 0, left = 0, bottom = 10, text = text, confidence = confidence)

    private fun texts(lines: List<OcrHelper.OcrLine>): List<String> = lines.map { it.text }

    // ── Line confidence: null-safe average (#25, #82) ─────────────

    @Test
    fun `line confidence averages usable elements`() {
        assertEquals(0.8f, OcrHelper.companionLineConfidence(listOf(0.9f, 0.7f))!!, 0.001f)
    }

    @Test
    fun `line confidence skips null elements`() {
        assertEquals(0.8f, OcrHelper.companionLineConfidence(listOf(0.9f, null, 0.7f))!!, 0.001f)
    }

    @Test
    fun `line confidence is unknown when no element reports`() {
        assertNull(OcrHelper.companionLineConfidence(emptyList()))
        assertNull(OcrHelper.companionLineConfidence(listOf(null, null)))
    }

    // ── Intra-row dedup (#81) ─────────────────────────────────────

    @Test
    fun `exact duplicate lines collapse keeping the first`() {
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("Monosodium Glutamat", 0.9f), line("Monosodium Glutamat", 0.8f))
        )
        assertEquals(listOf("Monosodium Glutamat"), texts(kept))
        assertEquals(0.9f, kept[0].confidence!!, 0.001f)
    }

    @Test
    fun `cjk exact duplicates collapse`() {
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("味精", 0.9f), line("味精", 0.7f))
        )
        assertEquals(listOf("味精"), texts(kept))
    }

    @Test
    fun `latin subset of a mixed line is dropped`() {
        // The Chinese engine returns the full mixed line; the Latin
        // engine returns its Latin subset — speaking both duplicates.
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("Monosodium Glutamat 味精", 0.8f), line("Monosodium Glutamat", 0.9f))
        )
        assertEquals(listOf("Monosodium Glutamat 味精"), texts(kept))
    }

    @Test
    fun `longer line wins regardless of arrival order`() {
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("Monosodium Glutamat", 0.9f), line("Monosodium Glutamat 味精", 0.8f))
        )
        assertEquals(listOf("Monosodium Glutamat 味精"), texts(kept))
    }

    @Test
    fun `complementary halves are both kept`() {
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("Monosodium", 0.9f), line("味精", 0.8f))
        )
        assertEquals(listOf("Monosodium", "味精"), texts(kept))
    }

    @Test
    fun `short coincidences are kept`() {
        // "I" is contained in "IN" — a length-1 containment is
        // coincidence, not duplication.
        val kept = OcrHelper.companionDedupRowLines(
            listOf(line("I", 0.9f), line("IN", 0.8f))
        )
        assertEquals(listOf("I", "IN"), texts(kept))
    }

    @Test
    fun `empty row stays empty`() {
        assertEquals(emptyList<OcrHelper.OcrLine>(), OcrHelper.companionDedupRowLines(emptyList()))
    }
}

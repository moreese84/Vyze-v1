package com.vyze.app.ui.delegates

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the color-analysis ROI centering (whole-app audit
 * P3 #88).
 *
 * Triple-tap color analysis draws only a small center crop of the
 * view instead of a full-frame bitmap. The crop is centered by
 * translating the canvas before the draw; a sign error here would
 * silently sample the wrong region, so the offsets are pinned against
 * the production companion — no copies (audit #98).
 */
class GestureRouterRoiTest {

    @Test
    fun `portrait view centers the crop`() {
        val (tx, ty) = GestureRouter.companionRoiTranslate(1080, 2400, 96)
        assertEquals((96 - 1080) / 2f, tx, 0.001f)
        assertEquals((96 - 2400) / 2f, ty, 0.001f)
    }

    @Test
    fun `square view centers the crop`() {
        val (tx, ty) = GestureRouter.companionRoiTranslate(500, 500, 100)
        assertEquals(-200f, tx, 0.001f)
        assertEquals(-200f, ty, 0.001f)
    }

    @Test
    fun `crop larger than the view stays centered`() {
        val (tx, ty) = GestureRouter.companionRoiTranslate(48, 48, 96)
        assertEquals(24f, tx, 0.001f)
        assertEquals(24f, ty, 0.001f)
    }
}

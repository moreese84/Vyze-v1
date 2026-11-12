package com.vyze.app.ui.fragments

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the gallery downsample math (audit #60).
 *
 * Full-res photos OOM low-tier phones, so GalleryFragment decodes with a
 * sample size that fits the longest edge into [GalleryFragment.GALLERY_MAX_EDGE_PX].
 * All expectations invoke the production companion — no reimplementation
 * (audit #98).
 */
class GallerySampleSizeTest {

    private fun sampleSize(width: Int, height: Int): Int =
        GalleryFragment.companionSampleSizeForMaxEdge(width, height)

    @Test
    fun `max edge constant is 2048`() {
        assertEquals(2048, GalleryFragment.GALLERY_MAX_EDGE_PX)
    }

    @Test
    fun `small image needs no downsampling`() {
        assertEquals(1, sampleSize(100, 100))
    }

    @Test
    fun `image exactly at max edge needs no downsampling`() {
        assertEquals(1, sampleSize(2048, 1024))
    }

    @Test
    fun `image one pixel over halves`() {
        assertEquals(2, sampleSize(2049, 100))
    }

    @Test
    fun `five megapixel landscape quarters`() {
        assertEquals(4, sampleSize(5000, 3000))
    }

    @Test
    fun `portrait uses the height edge`() {
        assertEquals(4, sampleSize(3000, 5000))
    }

    @Test
    fun `ten thousand pixel panorama samples to eight`() {
        assertEquals(8, sampleSize(10000, 100))
    }

    @Test
    fun `zero dimensions fall back to full sample`() {
        assertEquals(1, sampleSize(0, 0))
    }

    @Test
    fun `negative dimensions fall back to full sample`() {
        assertEquals(1, sampleSize(-5, 10))
    }
}

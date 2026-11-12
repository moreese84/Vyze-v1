package com.vyze.app.ui.delegates

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for stride-aware YUV_420_888 → NV21 packing (whole-app
 * audit P2 #24, 2026-11-09).
 *
 * The decoder copied plane buffers wholesale, ignoring rowStride and
 * pixelStride: padded-stride devices (seen on some MediaTek/Exynos
 * ISPs) produced sheared frames, and pixelStride-1 chroma was
 * concatenated V-then-U instead of interleaved VU. The converter now
 * samples rows at their strides and always interleaves chroma.
 */
class CameraSetupDelegateTest {

    @Test
    fun `tight planes pack y plus interleaved vu`() {
        // 4x2: Y rows "0123","4567"; chroma 2x1.
        val nv21 = CameraSetupDelegate.companionYuv420888ToNv21(
            yBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), yRowStride = 4,
            uBytes = byteArrayOf(10, 11), uRowStride = 2, uPixelStride = 1,
            vBytes = byteArrayOf(20, 21), vRowStride = 2, vPixelStride = 1,
            width = 4, height = 2
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 20, 10, 21, 11),
            nv21
        )
    }

    @Test
    fun `padded luma rows skip the padding`() {
        val nv21 = CameraSetupDelegate.companionYuv420888ToNv21(
            yBytes = byteArrayOf(0, 1, 2, 3, 99, 99, 4, 5, 6, 7, 99, 99), yRowStride = 6,
            uBytes = byteArrayOf(10, 11), uRowStride = 2, uPixelStride = 1,
            vBytes = byteArrayOf(20, 21), vRowStride = 2, vPixelStride = 1,
            width = 4, height = 2
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 20, 10, 21, 11),
            nv21
        )
    }

    @Test
    fun `pixelstride-2 chroma samples every other byte`() {
        // Interleaved-plane devices expose U/V with pixelStride 2 over
        // shared padded rows — sampling must stride, not copy wholesale.
        val nv21 = CameraSetupDelegate.companionYuv420888ToNv21(
            yBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), yRowStride = 4,
            uBytes = byteArrayOf(10, 99, 11, 99), uRowStride = 4, uPixelStride = 2,
            vBytes = byteArrayOf(20, 99, 21, 99), vRowStride = 4, vPixelStride = 2,
            width = 4, height = 2
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 20, 10, 21, 11),
            nv21
        )
    }

    @Test
    fun `chroma row padding is stepped per row`() {
        // 4x4: two chroma rows with padded U/V row strides.
        val nv21 = CameraSetupDelegate.companionYuv420888ToNv21(
            yBytes = ByteArray(16) { it.toByte() }, yRowStride = 4,
            uBytes = byteArrayOf(10, 11, 99, 99, 12, 13, 99, 99),
            uRowStride = 4, uPixelStride = 1,
            vBytes = byteArrayOf(20, 21, 99, 99, 22, 23, 99, 99),
            vRowStride = 4, vPixelStride = 1,
            width = 4, height = 4
        )
        val expectedY = ByteArray(16) { it.toByte() }
        val expectedChroma = byteArrayOf(20, 10, 21, 11, 22, 12, 23, 13)
        assertArrayEquals(expectedY + expectedChroma, nv21)
    }

    @Test
    fun `short buffers clamp instead of crashing`() {
        // Malformed strides must degrade to black, never throw on the
        // analysis thread (the caller maps any throw to a null frame).
        val nv21 = CameraSetupDelegate.companionYuv420888ToNv21(
            yBytes = byteArrayOf(7), yRowStride = 4,
            uBytes = byteArrayOf(), uRowStride = 2, uPixelStride = 1,
            vBytes = byteArrayOf(), vRowStride = 2, vPixelStride = 1,
            width = 4, height = 2
        )
        assertArrayEquals(
            byteArrayOf(7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            nv21
        )
    }

    @Test
    fun `high-res still target is exactly 4 by 3`() {
        // Whole-app audit P2 #26 (2026-11-09): the ImageCapture builder
        // set 2400x1350 (16:9) under a comment promising 4:3 full-sensor
        // field of view — document tops and bottoms were cropped out of
        // text reads. The target keeps the 2400 width (same horizontal
        // glyph detail) and restores the cropped rows at 4:3.
        val w = CameraSetupDelegate.HIGH_RES_STILL_WIDTH
        val h = CameraSetupDelegate.HIGH_RES_STILL_HEIGHT
        assertEquals(2400, w)
        assertEquals(1800, h)
        // Exact 4:3 by integer cross-multiplication (no float fuzz).
        assertEquals(0, w * 3 - h * 4)
    }
}

package com.vyze.app.vision
import com.vyze.app.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Analyzes the dominant color of a [Bitmap] by sampling the central 20%
 * region of interest (ROI) and classifying the average HSV value into one
 * of 12 distinct human-readable color terms.
 *
 * ## Localization
 * All color names and brightness modifiers are loaded from string resources
 * (values/strings.xml, values-ms/strings.xml, values-zh/strings.xml),
 * enabling automatic support for English, Bahasa Melayu, and Mandarin.
 *
 * ## Sampling Strategy
 * The center 20% of the image is used to avoid edge artifacts.
 *
 * ## Color Classification
 * Pixels are converted from RGB to HSV. The dominant color is classified by:
 *  1. Saturation check → Black / Grey / White
 *  2. Hue mapping → Red, Orange, Yellow, Green, Cyan, Blue, Purple, Pink, Brown
 *  3. Brightness modifier → "Dark" or "Bright" prefix
 */
class ColorAnalyzer {

    /**
     * Analyzes the center 20% ROI of the given [Bitmap] and returns a
     * human-readable color name localized via string resources.
     *
     * @param context  Used to load localized color name strings.
     * @param bitmap   The input image.
     * @return A localized color name like "Dark Blue", "Bright Yellow", "Grey", etc.
     */
    fun analyzeCenterColor(context: Context, bitmap: Bitmap): String {
        if (bitmap.width < 4 || bitmap.height < 4) return context.getString(R.string.color_unknown)

        // ── Step 1: Extract center 20% ROI ─────────────────────────────
        val roiWidth = (bitmap.width * ROI_FRACTION).toInt().coerceAtLeast(1)
        val roiHeight = (bitmap.height * ROI_FRACTION).toInt().coerceAtLeast(1)
        val startX = ((bitmap.width - roiWidth) / 2).coerceAtLeast(0)
        val startY = ((bitmap.height - roiHeight) / 2).coerceAtLeast(0)

        val roiBitmap = Bitmap.createBitmap(bitmap, startX, startY, roiWidth, roiHeight)

        try {
            // ── Step 2: Sample pixels and accumulate RGB ───────────────────
            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var pixelCount = 0

            val step = max(1, min(roiWidth, roiHeight) / SAMPLE_GRID)

            for (y in 0 until roiHeight step step) {
                for (x in 0 until roiWidth step step) {
                    val pixel = roiBitmap.getPixel(x, y)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    pixelCount++
                }
            }

            if (pixelCount == 0) return context.getString(R.string.color_unknown)

            val avgR = (totalR / pixelCount).toInt()
            val avgG = (totalG / pixelCount).toInt()
            val avgB = (totalB / pixelCount).toInt()

            // ── Step 3: Convert RGB → HSV ──────────────────────────────────
            val hsv = FloatArray(3)
            Color.RGBToHSV(avgR, avgG, avgB, hsv)
            val hue = hsv[0]        // 0–360
            val saturation = hsv[1] // 0–1
            val value = hsv[2]      // 0–1

            // ── Step 4: Classify ───────────────────────────────────────────
            return classifyColor(context, hue, saturation, value)
        } finally {
            // Security: recycle temporary bitmap to prevent memory leaks
            if (!roiBitmap.isRecycled) roiBitmap.recycle()
        }
    }

    /**
     * Classifies an HSV triplet into a localized human-readable color name.
     * The verdict comes from [companionClassifyColor] (shared with the
     * English overload, JVM-tested); only the resource lookup is local.
     */
    private fun classifyColor(context: Context, hue: Float, saturation: Float, value: Float): String {
        val verdict = companionClassifyColor(hue, saturation, value)
        val baseColor = context.getString(
            when (verdict.name) {
                ColorName.BLACK -> R.string.color_black
                ColorName.WHITE -> R.string.color_white
                ColorName.GREY -> R.string.color_grey
                ColorName.RED -> R.string.color_red
                ColorName.ORANGE -> R.string.color_orange
                ColorName.YELLOW -> R.string.color_yellow
                ColorName.GREEN -> R.string.color_green
                ColorName.CYAN -> R.string.color_cyan
                ColorName.BLUE -> R.string.color_blue
                ColorName.PURPLE -> R.string.color_purple
                ColorName.PINK -> R.string.color_pink
            }
        )
        return when (verdict.shade) {
            ColorShade.DARK -> context.getString(R.string.color_dark_prefix, baseColor)
            ColorShade.BRIGHT -> context.getString(R.string.color_bright_prefix, baseColor)
            ColorShade.NORMAL -> baseColor
        }
    }

    // NOTE (P4 #106, 2026-11-09): the context-free analyzeCenterColor
    // overload + classifyColorEnglish lived here with zero callers in
    // every source set — deleted. The Context overload above is the
    // only color entry point.

    companion object {
        const val TAG = "ColorAnalyzer"
        const val ROI_FRACTION = 0.20f
        const val SAMPLE_GRID = 20
        const val SATURATION_THRESHOLD = 0.12f
        const val BLACK_MAX_VALUE = 0.15f
        const val WHITE_MIN_VALUE = 0.85f
        const val DARK_MAX_VALUE = 0.35f
        const val BRIGHT_MIN_VALUE = 0.75f

        // English fallback constants
        const val BLACK = "Black"; const val WHITE = "White"; const val GREY = "Grey"
        const val RED = "Red"; const val ORANGE = "Orange"; const val YELLOW = "Yellow"
        const val GREEN = "Green"; const val CYAN = "Cyan"; const val BLUE = "Blue"
        const val PURPLE = "Purple"; const val PINK = "Pink"

        /**
         * Base-color verdict. No BROWN: brown is dark orange (~15-40 at
         * low value), not a hue band — hues 335-345 are magenta-pink
         * (audit P2 #27).
         */
        internal enum class ColorName {
            BLACK, WHITE, GREY, RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK
        }

        /** Brightness verdict. */
        internal enum class ColorShade { DARK, NORMAL, BRIGHT }

        /** One classification: base color + brightness. */
        internal data class ColorVerdict(val name: ColorName, val shade: ColorShade)

        /**
         * Classify an HSV triplet. Pure — shared by both overloads and
         * JVM-tested in ColorAnalyzerTest (which used to reimplement
         * this logic instead of invoking it — audit #98). Hue ranges
         * keep first-match-wins boundary overlaps (15→Red, 160→Green,
         * 195→Cyan, 260→Blue, 300→Purple, 335→Pink, 345→Red).
         */
        internal fun companionClassifyColor(hue: Float, saturation: Float, value: Float): ColorVerdict {
            if (saturation < SATURATION_THRESHOLD) {
                val name = when {
                    value < BLACK_MAX_VALUE -> ColorName.BLACK
                    value > WHITE_MIN_VALUE -> ColorName.WHITE
                    else -> ColorName.GREY
                }
                return ColorVerdict(name, ColorShade.NORMAL)
            }
            val name = when (hue) {
                in 0.0..15.0, in 345.0..360.0 -> ColorName.RED
                in 15.0..40.0 -> ColorName.ORANGE
                in 40.0..70.0 -> ColorName.YELLOW
                in 70.0..160.0 -> ColorName.GREEN
                in 160.0..195.0 -> ColorName.CYAN
                in 195.0..260.0 -> ColorName.BLUE
                in 260.0..300.0 -> ColorName.PURPLE
                in 300.0..345.0 -> ColorName.PINK
                else -> ColorName.RED
            }
            val shade = when {
                value < DARK_MAX_VALUE -> ColorShade.DARK
                value > BRIGHT_MIN_VALUE -> ColorShade.BRIGHT
                else -> ColorShade.NORMAL
            }
            return ColorVerdict(name, shade)
        }

        /** Render a verdict in English (the legacy overload's output). */
        internal fun companionEnglishName(verdict: ColorVerdict): String {
            val base = when (verdict.name) {
                ColorName.BLACK -> BLACK
                ColorName.WHITE -> WHITE
                ColorName.GREY -> GREY
                ColorName.RED -> RED
                ColorName.ORANGE -> ORANGE
                ColorName.YELLOW -> YELLOW
                ColorName.GREEN -> GREEN
                ColorName.CYAN -> CYAN
                ColorName.BLUE -> BLUE
                ColorName.PURPLE -> PURPLE
                ColorName.PINK -> PINK
            }
            return when (verdict.shade) {
                ColorShade.DARK -> "Dark $base"
                ColorShade.BRIGHT -> "Bright $base"
                ColorShade.NORMAL -> base
            }
        }
    }
}

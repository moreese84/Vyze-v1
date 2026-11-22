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

        // DEBUG evidence capture (2026-11-14): device reports every color
        // wrong (black→orange/blue, yellow→dark blue, white→grey) — persist
        // the exact input + sampled ROI so calibration reads pixels, not
        // theories (stale pin? mis-aim? exposure?). Ring of 3, PNG, best
        // effort, release builds skip entirely.
        if (com.vyze.app.util.CrashLogFile.isDebugBuild()) {
            try {
                debugSaveIndex = (debugSaveIndex + 1) % 3
                val dir = java.io.File(context.cacheDir, "color_debug")
                dir.mkdirs()
                java.io.FileOutputStream(java.io.File(dir, "color_in_${debugSaveIndex}.png")).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                java.io.FileOutputStream(java.io.File(dir, "color_roi_${debugSaveIndex}.png")).use {
                    roiBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (_: Throwable) { /* evidence is optional */
            }
        }

        try {
            // ── Step 2: Sample ROI (median) + frame (illuminant) ─────────
            // MEDIAN, not mean (2026-11-14): a black notebook next to a
            // light table averages to grey — median reports the dominant
            // region instead. The full-frame sparse mean estimates the
            // scene illuminant for gray-world correction below.
            val reds = mutableListOf<Int>()
            val greens = mutableListOf<Int>()
            val blues = mutableListOf<Int>()
            var illumR = 0L
            var illumG = 0L
            var illumB = 0L
            var illumCount = 0

            val step = max(1, min(roiWidth, roiHeight) / SAMPLE_GRID)
            for (y in 0 until roiHeight step step) {
                for (x in 0 until roiWidth step step) {
                    val pixel = roiBitmap.getPixel(x, y)
                    reds.add(Color.red(pixel))
                    greens.add(Color.green(pixel))
                    blues.add(Color.blue(pixel))
                }
            }
            val frameStep = max(1, min(bitmap.width, bitmap.height) / (SAMPLE_GRID * 2))
            for (y in 0 until bitmap.height step frameStep) {
                for (x in 0 until bitmap.width step frameStep) {
                    val pixel = bitmap.getPixel(x, y)
                    illumR += Color.red(pixel)
                    illumG += Color.green(pixel)
                    illumB += Color.blue(pixel)
                    illumCount++
                }
            }

            if (reds.isEmpty() || illumCount == 0) return context.getString(R.string.color_unknown)

            val medR = companionMedian(reds)
            val medG = companionMedian(greens)
            val medB = companionMedian(blues)
            // ── Step 2b: Capped gray-world white balance ───────────────
            // Indoor warm light tints dark neutrals orange (device-proven:
            // black notebook read ORANGE). Normalize by the scene
            // illuminant with capped gains so strongly colored scenes
            // (all-red room) only partially correct instead of graying.
            val balanced = companionWhiteBalance(
                medR, medG, medB,
                (illumR / illumCount).toInt(),
                (illumG / illumCount).toInt(),
                (illumB / illumCount).toInt()
            )
            val balR = balanced.first
            val balG = balanced.second
            val balB = balanced.third

            // ── Step 3: Convert RGB → HSV ──────────────────────────────────
            val hsv = FloatArray(3)
            Color.RGBToHSV(balR, balG, balB, hsv)
            val hue = hsv[0]        // 0–360
            val saturation = hsv[1] // 0–1
            val value = hsv[2]      // 0–1

            // ── Step 4: Classify ───────────────────────────────────────────
            val verdict = classifyColor(context, hue, saturation, value)
            // Calibration trace (2026-11-14): PERSISTED (CrashLogFile —
            // logcat rotates in ~2 min on Transsion devices) so black/
            // white threshold tuning reads device numbers, not guesses.
            // One line per analysis; negligible volume.
            com.vyze.app.util.CrashLogFile.log(
                TAG,
                "color verdict=$verdict rgb=($balR,$balG,$balB) " +
                    "raw=($medR,$medG,$medB) " +
                    "hsv=(${hsv[0].toInt()}," +
                    "${"%.2f".format(java.util.Locale.US, hsv[1])}," +
                    "${"%.2f".format(java.util.Locale.US, hsv[2])}) " +
                    "roi=${roiWidth}x${roiHeight}@($startX,$startY)"
            )
            return verdict
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
        // Indoor calibration (2026-11-14, GT50, well-lit living room):
        // a black notebook reads value≈0.29 (auto-exposure lifts blacks)
        // and a white mouse reads value≈0.70 (indoor whites never reach
        // lab 0.85). Old bounds (0.15/0.85) classified both as grey —
        // and warm tint pushed the notebook to orange. Bounds now hug
        // measured indoor values; dark-gray/light-gray confusions at the
        // seams are acceptable approximations for spoken use.
        const val BLACK_MAX_VALUE = 0.32f
        const val WHITE_MIN_VALUE = 0.65f
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

        /** Gain clamp for gray-world correction (see [companionWhiteBalance]). */
        const val WHITE_BALANCE_MAX_GAIN = 2.0f
        const val WHITE_BALANCE_MIN_GAIN = 0.5f

        /** Ring index for debug evidence captures (shared: instances are per-call). */
        @Volatile
        private var debugSaveIndex = 0

        /**
         * Median of sampled channel values. Pure — JVM-tested.
         * Median beats mean for ROI color: a black notebook beside a
         * light table averages to grey, while the median reports the
         * dominant region. Empty input yields 0 (caller guards first).
         */
        internal fun companionMedian(values: List<Int>): Int {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2
        }

        /**
         * Capped gray-world white balance (von Kries). Pure — JVM-tested.
         * Corrects each ROI channel by the scene illuminant estimate
         * (full-frame mean): `out = median * mean(illum) / illum_c`, gains
         * clamped to [0.5, 2.0] so strongly colored scenes only partially
         * correct instead of graying out. Zero illuminant channels pass
         * through unscaled (no divide-by-zero). Returns (R, G, B).
         */
        internal fun companionWhiteBalance(
            r: Int, g: Int, b: Int,
            illumR: Int, illumG: Int, illumB: Int
        ): Triple<Int, Int, Int> {
            val avg = (illumR + illumG + illumB) / 3.0f
            fun correct(v: Int, illum: Int): Int {
                if (illum <= 0) return v.coerceIn(0, 255)
                val gain = (avg / illum).coerceIn(WHITE_BALANCE_MIN_GAIN, WHITE_BALANCE_MAX_GAIN)
                return (v * gain).toInt().coerceIn(0, 255)
            }
            return Triple(correct(r, illumR), correct(g, illumG), correct(b, illumB))
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

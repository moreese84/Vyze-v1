package com.vyze.app.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device OCR helper using Google ML Kit Text Recognition.
 *
 * ## Supported Scripts
 * - **Latin**: English, Malay, Indonesian, Vietnamese, Turkish, European languages
 * - **Chinese**: Simplified + Traditional Chinese
 *
 * ## How It Works
 * ML Kit automatically detects the script in the image and routes to the
 * correct recognizer. No manual script selection needed.
 *
 * ## Latency
 * ~80-150ms on a mid-range phone — 10-30x faster than full VLM inference.
 *
 * ## Usage
 * Call [extractText] from a coroutine. Returns extracted text or null
 * if nothing was recognized.
 */
class OcrHelper {

    /**
     * Latin script recognizer — covers English, Malay, Indonesian,
     * Vietnamese, Turkish, Spanish, French, German, and most European languages.
     */
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    /**
     * Chinese script recognizer — covers Simplified and Traditional Chinese.
     */
    private val chineseRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * Extract text from a camera bitmap using ML Kit on-device OCR.
     *
     * Runs BOTH the Latin (English/Malay/…) and Chinese recognizers and merges
     * their lines in reading order — required for packaging that mixes scripts
     * in one sentence. See [extractMerged] for the merge rules.
     *
     * @param bitmap Camera frame — will NOT be recycled (caller manages lifecycle)
     * @return Extracted text string, or null if nothing was recognized
     */
    suspend fun extractText(bitmap: Bitmap): String? = extractMerged(bitmap)?.first

    /**
     * Extract text with confidence metadata.
     * Returns a pair of (text, confidence) where confidence is 0.0-1.0.
     * Useful for deciding whether to fall back to Gemma for interpretation.
     */
    suspend fun extractTextWithConfidence(bitmap: Bitmap): Pair<String?, Float> = extractMerged(bitmap)

    /**
     * OCR with the LATIN (English/Malay/…) AND CHINESE recognizers BOTH always
     * running, then merged line-by-line in reading order.
     *
     * Product packaging frequently mixes scripts — Malay/English and Chinese in
     * the SAME sentence ("Sila ambil 1 bungkus 请服用一包"). The old Latin-first
     * logic stopped at the first Latin result and silently dropped the Chinese
     * half of such labels. Both recognizers now contribute, and a line is kept
     * only if it actually belongs to that recognizer's script (Chinese lines
     * must contain a CJK ideograph; Latin lines a Latin letter or digit), so an
     * English-only page is not polluted by Chinese-recognizer noise.
     *
     * @return (merged text in reading order, average element confidence)
     */
    private suspend fun extractMerged(bitmap: Bitmap): Pair<String?, Float> =
        withContext(Dispatchers.Default) {
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val latinResult = try {
                    latinRecognizer.process(inputImage).await()
                } catch (e: Exception) {
                    // Audit P3 #80: NEVER swallow silently — a missing or
                    // broken recognizer model must be distinguishable from
                    // "no text in this script" in logcat. The message is
                    // SDK-side (never user content).
                    Log.w(TAG, "OCR latin recognizer failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }

                val chineseResult = try {
                    chineseRecognizer.process(inputImage).await()
                } catch (e: Exception) {
                    // Audit P3 #80: same — a missing Chinese model download
                    // used to look exactly like a text-free frame.
                    Log.w(TAG, "OCR chinese recognizer failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }

                val lines = mutableListOf<OcrLine>()
                collectUsableLines(latinResult, isChinese = false, lines)
                collectUsableLines(chineseResult, isChinese = true, lines)

                if (lines.isEmpty()) {
                    Log.d(TAG, "OCR: no text found (latin + chinese)")
                    return@withContext Pair(null, 0f)
                }

                // Group into visual rows — code-switched lines from the two
                // recognizers share a vertical band, so they join one row and
                // are ordered left→right; the rows themselves run top→bottom.
                val rows = groupIntoRows(lines)
                val ordered = rows.sortedBy { row -> row.minOf { it.top } }
                val sb = StringBuilder()
                var totalConf = 0f
                var totalChars = 0
                for (row in ordered) {
                    // Both recognizers return the same printed line —
                    // dedup before joining or the row speaks twice (#81).
                    val rowLines = companionDedupRowLines(row.sortedBy { it.left })
                    val rowText = rowLines.joinToString(" ") { it.text }.trim()
                    if (rowText.isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(rowText)
                    // Unknown-confidence lines keep their text but are
                    // excluded from the average (#82).
                    for (l in rowLines) {
                        val c = l.confidence ?: continue
                        totalConf += c * l.text.length
                        totalChars += l.text.length
                    }
                }

                val merged = sb.toString()
                if (merged.length < MIN_TEXT_LENGTH) {
                    Log.d(TAG, "OCR merged result too short")
                    return@withContext Pair(null, 0f)
                }
                Log.d(TAG, "OCR merged: ${merged.length} chars across ${ordered.size} rows")
                Pair(merged, if (totalChars > 0) totalConf / totalChars else 0f)

            } catch (e: Exception) {
                Log.e(TAG, "extractTextWithConfidence failed: ${e.message}")
                Pair(null, 0f)
            }
        }

    /**
     * One recognized text line with its visual band + average confidence.
     * Null confidence = the line reported no usable value: its text is
     * kept but it is excluded from the merge average (#82). Internal for
     * the JVM-tested merge helpers.
     */
    internal class OcrLine(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val text: String,
        val confidence: Float?
    )

    /** Collect the lines of one recognizer that genuinely belong to its script. */
    private fun collectUsableLines(
        result: com.google.mlkit.vision.text.Text?,
        isChinese: Boolean,
        out: MutableList<OcrLine>
    ) {
        if (result == null) return
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text?.trim() ?: continue
                if (text.isEmpty()) continue
                val usable = if (isChinese) {
                    containsCjkIdeograph(text)
                } else {
                    text.length >= 2 && text.any { it.isLetter() || it.isDigit() }
                }
                if (!usable) continue
                val box = line.boundingBox ?: continue
                out.add(OcrLine(box.top, box.left, box.bottom, text, lineConfidence(line)))
            }
        }
    }

    /** True if the text contains at least one CJK ideograph (any real Chinese). */
    private fun containsCjkIdeograph(text: String): Boolean =
        text.any { ch ->
            val c = ch.code
            (c in 0x4E00..0x9FFF) ||  // CJK Unified Ideographs
                (c in 0x3400..0x4DBF) ||  // Extension A
                (c in 0xF900..0xFAFF)     // Compatibility Ideographs
        }

    /**
     * Average confidence of a line's usable elements, or null when the
     * line reports none (#25/#82). Element confidence is a platform
     * type — it is read as nullable so a missing value can never NPE
     * the whole read into a silent (null, 0f) — and element-less lines
     * no longer vote a confident 0.85 into the merge average that
     * gates the 0.85 fast-path.
     */
    private fun lineConfidence(line: com.google.mlkit.vision.text.Text.Line): Float? {
        val confidences: List<Float?> = line.elements.map { it.confidence }
        return companionLineConfidence(confidences)
    }

    /**
     * Group lines into visual rows. Lines whose vertical bands overlap by at
     * least half the shorter line's height belong to the same text row — this
     * is what re-joins the Latin and Chinese halves of one printed line.
     */
    private fun groupIntoRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.top }) {
            val existing = rows.firstOrNull { row -> row.any { sameRow(it, line) } }
            if (existing != null) existing.add(line) else rows.add(mutableListOf(line))
        }
        return rows
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0) return false
        val shorterHeight = minOf(a.bottom - a.top, b.bottom - b.top)
        return overlap >= shorterHeight * 0.5f
    }

    /**
     * Release recognizer resources. Call when OCR is no longer needed.
     */
    fun close() {
        try {
            latinRecognizer.close()
            chineseRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "close error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OcrHelper"

        /** Minimum characters for OCR result to be considered useful. */
        private const val MIN_TEXT_LENGTH = 3

        /**
         * Average of usable element confidences, or null when none
         * report. Pure — JVM-tested in OcrHelperTest.
         */
        internal fun companionLineConfidence(confidences: List<Float?>): Float? {
            val usable = confidences.filterNotNull()
            if (usable.isEmpty()) return null
            return usable.sum() / usable.size
        }

        /**
         * Intra-row dedup (audit P2 #81): the Latin and Chinese
         * recognizers both return the same printed line, and the row
         * join would speak it twice. Exact dupes collapse (first kept);
         * a line contained in a kept longer line is dropped; a longer
         * arrival evicts the shorter it contains — longer wins
         * regardless of order. Comparison is case/space-insensitive;
         * containment needs [DEDUP_MIN_CONTAINED] chars so 1-2 char
         * coincidences ("I" in "IN") survive. Complementary halves
         * ("Monosodium" + "味精") never contain each other and are
         * always kept. Pure — JVM-tested in OcrHelperTest.
         */
        internal fun companionDedupRowLines(lines: List<OcrLine>): List<OcrLine> {
            if (lines.size < 2) return lines
            val kept = mutableListOf<OcrLine>()
            for (line in lines) {
                val norm = normDedupText(line.text)
                if (kept.any { normDedupText(it.text) == norm }) continue
                if (kept.any {
                        normDedupText(it.text).contains(norm) && norm.length >= DEDUP_MIN_CONTAINED
                    }
                ) continue
                kept.removeAll {
                    val k = normDedupText(it.text)
                    norm.contains(k) && k.length >= DEDUP_MIN_CONTAINED && k.length < norm.length
                }
                kept.add(line)
            }
            return kept
        }

        private fun normDedupText(text: String): String =
            text.lowercase().replace(Regex("\\s+"), " ").trim()

        /** Min contained length for containment dedup (see above). */
        private const val DEDUP_MIN_CONTAINED = 3
    }
}

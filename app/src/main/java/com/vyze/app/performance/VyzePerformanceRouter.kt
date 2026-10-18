package com.vyze.app.performance

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.vyze.app.VyzeApplication
import com.vyze.app.core.ThermalPowerController
import com.vyze.app.core.VyzeCoreController
import com.vyze.app.speech.TTSManager
import com.vyze.app.vision.BarcodeHelper
import com.vyze.app.vision.OcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * OCR-first routing for Vyze snapshot queries.
 *
 * This is the V1 routing layer for the checklist item:
 * 1. Route text, labels, documents, and barcodes to ML Kit first, and speak
 *    OCR results immediately.
 * 2. Send OCR text — not the image — to MiniCPM for cleanup, translation, or
 *    explanation. Send the image directly to MiniCPM only for scene/object
 *    questions.
 *
 * This class does NOT own the engine or the camera. It is a pure decision layer
 * that the existing snapshot trigger calls before it starts the VLM. It is
 * deliberately small: it decides the task type and the first-response plan,
 * and returns a small structured description of what should happen next.
 */
object VyzePerformanceRouter {

    // ── public result type ─────────────────────────────────────────────────

    sealed class RoutePlan {
        /** ML Kit OCR is the primary source; maybe speak it immediately. */
        data class OcrFirst(
            val intent: TaskIntent,
            val ocrText: String,
            val ocrConfidence: Float,
            val barcode: BarcodeResult?,
            val speakNow: Boolean,
            val vlmWillRefine: Boolean,
        ) : RoutePlan()

        /** Scene/object question: send image directly to MiniCPM. */
        data class SceneDirect(
            val intent: TaskIntent,
            val speakNow: Boolean,
            val vlmWillRun: Boolean,
        ) : RoutePlan()

        /** Knowledge-only question with no image needed. */
        data class TextOnly(
            val intent: TaskIntent,
            val speakNow: Boolean,
        ) : RoutePlan()
    }

    sealed class TaskIntent {
        object ReadText : TaskIntent()
        object ReadLabel : TaskIntent()
        object ReadDocument : TaskIntent()
        object ReadBarcode : TaskIntent()
        object SceneDescribe : TaskIntent()
        object SceneObject : TaskIntent()
        object Knowledge : TaskIntent()
        object Unknown : TaskIntent()
    }

    data class BarcodeResult(
        val rawValue: String,
        val formatName: String,
    )

    data class RouteDecision(
        val plan: RoutePlan,
        val taskType: String,
        val ocrTextSentToNlu: String?,
        val imageSentToVlm: Boolean,
        val ocrTextForLogOnly: String?,
    )

    // ── entrypoints ────────────────────────────────────────────────────────

    /**
     * Decide how to handle a query + frame.
     *
     * This is intentionally synchronous on the main thread for the routing
     * decision; the OCR run that follows happens in the snapshot trigger via
     * the controller's existing ML Kit pipeline. This function returns the
     * decision and the plan before any heavy async work starts.
     */
    fun decide(
        context: Context,
        query: String?,
        frame: Bitmap?,
    ): RouteDecision {
        val intent = classifyIntent(query)
        val taskType = taskTypeName(intent)

        return when (intent) {
            is TaskIntent.ReadText,
            is TaskIntent.ReadLabel,
            is TaskIntent.ReadDocument,
            is TaskIntent.ReadBarcode -> {
                val ocr = bestAvailableOcr(context, frame)
                val ocrText = ocr?.text.orEmpty()
                val speakNow = ocrText.isNotBlank() &&
                    (ocr?.confidence ?: 0f) >= MIN_SPEAK_NOW_CONFIDENCE &&
                    !wouldPreferVlmForHumor(context, query, ocrText)
                RouteDecision(
                    plan = RoutePlan.OcrFirst(
                        intent = intent,
                        ocrText = ocrText,
                        ocrConfidence = ocr?.confidence ?: 0f,
                        barcode = ocr?.barcode,
                        speakNow = speakNow,
                        vlmWillRefine = !speakNow && intent is TaskIntent.ReadDocument,
                    ),
                    taskType = TASK_OCR_FIRST,
                    ocrTextSentToNlu = ocrText.ifBlank { null },
                    imageSentToVlm = false,
                    ocrTextForLogOnly = ocr?.text,
                )
            }

            is TaskIntent.SceneDescribe,
            is TaskIntent.SceneObject -> {
                RouteDecision(
                    plan = RoutePlan.SceneDirect(
                        intent = intent,
                        speakNow = false,
                        vlmWillRun = true,
                    ),
                    taskType = TASK_SCENE_DIRECT,
                    ocrTextSentToNlu = null,
                    imageSentToVlm = true,
                    ocrTextForLogOnly = null,
                )
            }

            is TaskIntent.Knowledge -> {
                RouteDecision(
                    plan = RoutePlan.TextOnly(
                        intent = intent,
                        speakNow = true,
                    ),
                    taskType = TASK_TEXT_ONLY,
                    ocrTextSentToNlu = null,
                    imageSentToVlm = false,
                    ocrTextForLogOnly = null,
                )
            }

            TaskIntent.Unknown -> {
                // Conservative default: scene direct for any visual frame.
                if (frame != null && !frame.isRecycled) {
                    RouteDecision(
                        plan = RoutePlan.SceneDirect(
                            intent = TaskIntent.Unknown,
                            speakNow = false,
                            vlmWillRun = true,
                        ),
                        taskType = TASK_SCENE_DIRECT,
                        ocrTextSentToNlu = null,
                        imageSentToVlm = true,
                        ocrTextForLogOnly = null,
                    )
                } else {
                    RouteDecision(
                        plan = RoutePlan.TextOnly(
                            intent = TaskIntent.Unknown,
                            speakNow = false,
                        ),
                        taskType = TASK_TEXT_ONLY,
                        ocrTextSentToNlu = null,
                        imageSentToVlm = false,
                        ocrTextForLogOnly = null,
                    )
                }
            }
        }
    }

    /**
     * Best-effort ML Kit OCR from already-ready singletons.
     *
     * This is a V1 helper that runs OCR on a frame, optionally with a barcode
     * pre-pass, and returns the combined best text + confidence + barcode.
     * It is NOT the engine lane; it only decides the plan. The actual OCR work
     * is performed by the controller's existing OcrHelper/BarcodeHelper pipeline.
     */
    suspend fun runOcrFirstPipeline(
        context: Context,
        frame: Bitmap,
        query: String?,
    ): OcrFirstPayload {
        val ocr = OcrHelper()
        val barcode = BarcodeHelper()
        try {
            val textResult = ocr.extractTextWithConfidence(frame)
            val barcodeResult = barcode.scan(frame)

            val text = textResult.first
            val confidence = textResult.second
            val barcodePayload = barcodeResult?.let { (raw, fmt) ->
                BarcodeResult(rawValue = raw, formatName = fmt)
            }

            val intent = classifyIntent(query)
            val speakNow = text != null && text.isNotBlank() &&
                confidence >= MIN_SPEAK_NOW_CONFIDENCE &&
                !wouldPreferVlmForHumor(context, query?.takeIf { it.isNotBlank() }, text)

            return OcrFirstPayload(
                text = text,
                confidence = confidence,
                barcode = barcodePayload,
                intent = intent,
                speakNow = speakNow,
                vlmWillRefine = !speakNow &&
                    intent in listOf(
                        TaskIntent.ReadDocument,
                        TaskIntent.ReadLabel,
                    ),
                firstSentence = firstSentenceForTts(text),
            )
        } finally {
            try { ocr.close() } catch (_: Throwable) {}
        }
    }

    // ── internal helpers ───────────────────────────────────────────────────

    /**
     * Pure query-intent classifier. Deliberately ignores everything except the
     * query text so it stays JVM-testable; context is reserved for future
     * personalization signals.
     */
    internal fun classifyIntent(query: String?): TaskIntent {
        if (query.isNullOrBlank()) return TaskIntent.Unknown
        val lower = query.lowercase()

        // Reading intents: text/labels/documents/barcodes.
        // Barcode/QR detection runs FIRST — an ask can name the barcode without
        // any generic read keyword ("scan the barcode", 扫码) and ML Kit's
        // scanner (not the OCR recognizer) is the right first responder.
        if (isBarcodeIntent(lower)) return TaskIntent.ReadBarcode
        if (isReadIntent(lower)) {
            if (isDocumentIntent(lower)) return TaskIntent.ReadDocument
            if (isLabelIntent(lower)) return TaskIntent.ReadLabel
            return TaskIntent.ReadText
        }

        // Scene/object intents: describe what is here, what is this, etc.
        if (isSceneOrObjectIntent(lower)) return TaskIntent.SceneObject
        if (isDescribeIntent(lower)) return TaskIntent.SceneDescribe

        // Knowledge intents: no camera needed.
        if (isKnowledgeIntent(lower)) return TaskIntent.Knowledge

        return TaskIntent.Unknown
    }

    private fun taskTypeName(intent: TaskIntent): String = when (intent) {
        is TaskIntent.ReadText -> "ocr-read-text"
        is TaskIntent.ReadLabel -> "ocr-read-label"
        is TaskIntent.ReadDocument -> "ocr-read-document"
        is TaskIntent.ReadBarcode -> "ocr-read-barcode"
        is TaskIntent.SceneDescribe -> "scene-direct"
        is TaskIntent.SceneObject -> "scene-object"
        is TaskIntent.Knowledge -> "knowledge-text-only"
        TaskIntent.Unknown -> "unknown"
    }

    private fun isReadIntent(lower: String): Boolean {
        return lower.contains("read") ||
            lower.contains("label") ||
            lower.contains("text") ||
            lower.contains("sign") ||
            lower.contains("document") ||
            lower.contains("baca") ||
            lower.contains("teks") ||
            lower.contains("label") ||
            lower.contains("tulisan") ||
            lower.contains("tanda") ||
            lower.contains("dokumen") ||
            lower.contains("包装") ||
            lower.contains("标签") ||
            lower.contains("文字") ||
            lower.contains("读") ||
            lower.contains("读一下") ||
            // number reading asks
            lower.contains("number") ||
            lower.contains("nombor") ||
            lower.contains("号")
    }

    private fun isBarcodeIntent(lower: String): Boolean {
        return lower.contains("barcode") ||
            lower.contains("bar code") ||
            lower.contains("qr") ||
            lower.contains("kad") ||
            lower.contains("条码") ||
            lower.contains("二维码") ||
            lower.contains("扫码") ||
            lower.contains("掃碼")
    }

    private fun isDocumentIntent(lower: String): Boolean {
        return lower.contains("document") ||
            lower.contains("paper") ||
            lower.contains("letter") ||
            lower.contains("receipt") ||
            lower.contains("menu") ||
            lower.contains("book") ||
            lower.contains("note") ||
            lower.contains("surat") ||
            lower.contains("resit") ||
            lower.contains("menu") ||
            lower.contains("buku") ||
            lower.contains("nota") ||
            lower.contains("文件") ||
            lower.contains("信") ||
            lower.contains("收据") ||
            lower.contains("菜单")
    }

    private fun isLabelIntent(lower: String): Boolean {
        return lower.contains("label") ||
            lower.contains("tag") ||
            lower.contains("title") ||
            lower.contains("caption") ||
            lower.contains("heading") ||
            lower.contains("nama") ||
            lower.contains("caption") ||
            lower.contains("label") ||
            lower.contains("标签") ||
            lower.contains("名称") ||
            lower.contains("标题") ||
            lower.contains("说明")
    }

    private fun isSceneOrObjectIntent(lower: String): Boolean {
        return lower.contains("what is this") ||
            lower.contains("apa ini") ||
            lower.contains("ni apa") ||
            lower.contains("ini apa") ||
            lower.contains("这是什么") ||
            lower.contains("这个是什么") ||
            lower.contains("前面是什么") ||
            lower.contains("apa benda") ||
            lower.contains("barang apa") ||
            lower.contains("benda apa")
    }

    private fun isDescribeIntent(lower: String): Boolean {
        return lower.contains("what do you see") ||
            lower.contains("apa yang anda nampak") ||
            lower.contains("nampak apa") ||
            lower.contains("describe") ||
            lower.contains("ceritakan") ||
            lower.contains("描") ||
            lower.contains("描述")
    }

    private fun isKnowledgeIntent(lower: String): Boolean {
        return lower.contains("what is") ||
            lower.contains("apa itu") ||
            lower.contains("meaning") ||
            lower.contains("maksud") ||
            lower.contains("kenapa") ||
            lower.contains("why") ||
            lower.contains("bagaimana") ||
            lower.contains("how") ||
            lower.contains("siapa") ||
            lower.contains("who") ||
            lower.contains("bila") ||
            lower.contains("when") ||
            lower.contains("di mana") ||
            lower.contains("where") ||
            lower.contains("是什么") ||
            lower.contains("什么意思") ||
            lower.contains("为什么") ||
            lower.contains("怎么") ||
            lower.contains("如何")
    }

    private fun bestAvailableOcr(context: Context, frame: Bitmap?): OcrResult? {
        if (frame == null || frame.isRecycled) return null
        // In V1 this is a decision helper only. Real OCR runs later in the
        // snapshot trigger through the controller pipeline.
        return OcrResult(
            text = null,
            confidence = 0f,
            barcode = null,
        )
    }

    private fun wouldPreferVlmForHumor(context: Context, query: String?, ocrText: String): Boolean {
        // V1: keep it simple. We do not try to detect jokes.
        return false
    }

    private fun firstSentenceForTts(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val terminators = charArrayOf('.', '!', '?', ';', '\n', '。', '！', '？', '；')
        val idx = terminators.map { text.indexOf(it) }.filter { it >= 0 }                .reduceOrNull { a, b -> kotlin.math.min(a, b) }
        return if (idx != null) text.substring(0, idx + 1) else text
    }

    // ── constants ───────────────────────────────────────────────────────────

    /** Minimum OCR confidence to speak the result immediately. */
    const val MIN_SPEAK_NOW_CONFIDENCE = 0.72f

    /** Task-type strings for telemetry. */
    const val TASK_OCR_FIRST = "ocr_first"
    const val TASK_SCENE_DIRECT = "scene_direct"
    const val TASK_TEXT_ONLY = "text_only"

    // ── small payload types for OCR pipeline ───────────────────────────────

    data class OcrFirstPayload(
        val text: String?,
        val confidence: Float,
        val barcode: BarcodeResult?,
        val intent: TaskIntent,
        val speakNow: Boolean,
        val vlmWillRefine: Boolean,
        val firstSentence: String?,
    )

    data class OcrResult(
        val text: String?,
        val confidence: Float,
        val barcode: BarcodeResult?,
    )
}

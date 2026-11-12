package com.vyze.app.performance

/**
 * Query-intent classification + telemetry task buckets for Vyze queries.
 *
 * This object USED to be the V1 OCR-first routing layer (decide() +
 * runOcrFirstPipeline() + RoutePlan/RouteDecision payloads). Whole-app
 * audit P2 #41/#51 (2026-11-09): decide()'s OCR-first path could never
 * speak (bestAvailableOcr returned empty text at confidence 0) and
 * NEITHER entrypoint had a caller in any source set — the controller
 * runs its own OCR pipeline. The dead ~300 lines were deleted, not
 * fixed. What survives is the live part: the pure [classifyIntent]
 * classifier (called by VyzeCoreController, JVM-pinned) and the TASK_*
 * telemetry buckets.
 */
object VyzePerformanceRouter {

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

    // ── intent classification (the live API) ─────────────────────────────

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

    // ── constants ───────────────────────────────────────────────────────────

    /** Task-type strings for telemetry. */
    const val TASK_OCR_FIRST = "ocr_first"
    const val TASK_SCENE_DIRECT = "scene_direct"
    const val TASK_TEXT_ONLY = "text_only"
}

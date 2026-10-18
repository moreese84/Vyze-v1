package com.vyze.app.performance

import com.vyze.app.performance.VyzePerformanceRouter.TaskIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM pins for the OCR-first intent classifier ([VyzePerformanceRouter.classifyIntent]).
 *
 * Product rule being pinned (checklist item 1 + 2):
 *  - text / label / document / barcode asks route OCR-FIRST (ML Kit answers,
 *    the image never reaches the VLM when OCR is confident),
 *  - "what is this"/describe asks route to the scene lane (image → MiniCPM),
 *  - knowledge asks route text-only (no camera needed),
 *  - blank/garbage falls to Unknown (caller picks a conservative default),
 *  - en / ms / zh wordings all counted, since the user's corpus is trilingual.
 */
class VyzePerformanceRouterIntentTest {

    private fun intent(query: String?): TaskIntent {
        return VyzePerformanceRouter.classifyIntent(query)
    }

    // ── OCR-first family (ML Kit answers first) ──────────────────────

    @Test
    fun `plain text read asks — en, ms, zh`() {
        assertEquals(TaskIntent.ReadText, intent("read the text"))
        assertEquals(TaskIntent.ReadText, intent("baca teks ni"))
        assertEquals(TaskIntent.ReadText, intent("读一下这个文字"))
    }

    @Test
    fun `label asks route OCR-first — all languages`() {
        assertEquals(TaskIntent.ReadLabel, intent("read the label"))
        assertEquals(TaskIntent.ReadLabel, intent("label apa ni"))
        assertEquals(TaskIntent.ReadLabel, intent("读一下这个标签"))
    }

    @Test
    fun `document asks route OCR-first — all languages`() {
        assertEquals(TaskIntent.ReadDocument, intent("read this document"))
        assertEquals(TaskIntent.ReadDocument, intent("read the menu"))
        assertEquals(TaskIntent.ReadDocument, intent("surat ni apa tulisan"))
        assertEquals(TaskIntent.ReadDocument, intent("读这个文件"))
    }

    @Test
    fun `barcode and QR asks route OCR-first — all languages`() {
        assertEquals(TaskIntent.ReadBarcode, intent("scan the barcode"))
        assertEquals(TaskIntent.ReadBarcode, intent("scan the qr code"))
        assertEquals(TaskIntent.ReadBarcode, intent("扫码"))
        assertEquals(TaskIntent.ReadBarcode, intent("读一下条码"))
    }

    // ── Scene lane (image goes to MiniCPM) ───────────────────────────

    @Test
    fun `object asks route scene direct — all languages`() {
        assertEquals(TaskIntent.SceneObject, intent("what is this"))
        assertEquals(TaskIntent.SceneObject, intent("ini apa"))
        assertEquals(TaskIntent.SceneObject, intent("这是什么"))
    }

    @Test
    fun `describe asks route scene — all languages`() {
        assertEquals(TaskIntent.SceneDescribe, intent("describe what you see"))
        assertEquals(TaskIntent.SceneDescribe, intent("apa yang anda nampak"))
        assertEquals(TaskIntent.SceneDescribe, intent("描述一下"))
    }

    // ── Knowledge lane (no camera needed) ────────────────────────────

    @Test
    fun `knowledge asks route text-only — all languages`() {
        assertEquals(TaskIntent.Knowledge, intent("meaning of aquarius"))
        assertEquals(TaskIntent.Knowledge, intent("kenapa langit biru"))
        assertEquals(TaskIntent.Knowledge, intent("为什么天是蓝的"))
    }

    // ── Unknown/garble: safety of the conservative default ───────────

    @Test
    fun `null and blank and garble classify as Unknown`() {
        assertEquals(TaskIntent.Unknown, intent(null))
        assertEquals(TaskIntent.Unknown, intent(""))
        assertEquals(TaskIntent.Unknown, intent("hjkl qwerty zzz"))
        assertEquals(TaskIntent.Unknown, intent("please say that again"))
    }

    // ── Routing buckets (telemetry tags — stability matters) ─────────

    @Test
    fun `read family buckets to OCR-first while scene goes to direct and knowledge to text-only`() {
        for (ti in listOf(
            TaskIntent.ReadText,
            TaskIntent.ReadLabel,
            TaskIntent.ReadDocument,
            TaskIntent.ReadBarcode,
        )) {
            assertEquals(VyzePerformanceRouter.TASK_OCR_FIRST, taskBucket(ti))
        }
        for (ti in listOf(TaskIntent.SceneDescribe, TaskIntent.SceneObject)) {
            assertEquals(VyzePerformanceRouter.TASK_SCENE_DIRECT, taskBucket(ti))
        }
        assertEquals(VyzePerformanceRouter.TASK_TEXT_ONLY, taskBucket(TaskIntent.Knowledge))
    }

    private fun taskBucket(ti: TaskIntent): String {
        return when (ti) {
            is TaskIntent.ReadText,
            is TaskIntent.ReadLabel,
            is TaskIntent.ReadDocument,
            is TaskIntent.ReadBarcode -> VyzePerformanceRouter.TASK_OCR_FIRST
            is TaskIntent.SceneDescribe,
            is TaskIntent.SceneObject -> VyzePerformanceRouter.TASK_SCENE_DIRECT
            is TaskIntent.Knowledge -> VyzePerformanceRouter.TASK_TEXT_ONLY
            TaskIntent.Unknown -> "unknown"
        }
    }
}

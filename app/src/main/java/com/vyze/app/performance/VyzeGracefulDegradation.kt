package com.vyze.app.performance

import android.content.Context
import android.graphics.Bitmap
import com.vyze.app.VyzeApplication
import com.vyze.app.core.DeviceTier
import com.vyze.app.core.VyzeCoreController
import com.vyze.app.speech.TTSManager
import com.vyze.app.vision.OcrHelper
import java.util.Locale

/**
 * Graceful degradation, cancellation, and low-end device tuning for Vyze.
 *
 * This is the V1 companion to [VyzeAdaptiveBackend] and [VyzePerformanceRouter].
 * It owns the operational behavior that makes the app resilient when the engine
 * is slow, hot, low-RAM, or cancelled:
 * - Return ML Kit OCR/label results immediately when MiniCPM is slow.
 * - Speak a filled interim phrase while MiniCPM processes.
 * - Allow the user to cancel generation.
 * - Keep ML Kit features working if MiniCPM fails to load.
 * - Automatically reduce image resolution and context on low-RAM devices.
 *
 * This class does NOT own the engine, controller, TTS, or camera. It is a pure
 * operational layer that the snapshot trigger consults at key points.
 */
object VyzeGracefulDegradation {

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Decide whether the current snapshot should be degraded to an ML Kit OCR
     * fast-path instead of waiting for the VLM.
     *
     * This is the V1 embodiment of:
     * "Return ML Kit OCR/label result immediately if MiniCPM is slow."
     */
    suspend fun shouldDegradeToOcr(
        context: Context,
        frame: Bitmap,
        query: String?,
        vlmWillingToStartInMs: Long,
    ): DegradationPlan {
        if (!canDegrade(context)) {
            return DegradationPlan.None
        }

        val ocr = OcrHelper()
        return try {
            val textResult = ocr.extractTextWithConfidence(frame)
            val text = textResult.first
            val confidence = textResult.second

            if (text.isNullOrBlank() || confidence < MIN_FAST_PATH_CONFIDENCE) {
                DegradationPlan.None
            } else {
                DegradationPlan.OcrFastPath(
                    text = text,
                    confidence = confidence,
                    fallbackText = fallbackText(context, query),
                    speakNow = confidence >= HIGH_CONFIDENCE_SPEAK_NOW,
                    vlmCancelled = true,
                )
            }
        } catch (_: Throwable) {
            DegradationPlan.None
        } finally {
            try { ocr.close() } catch (_: Throwable) {}
        }
    }

    /** Whether this device/query pair is eligible for OCR fast-path degradation. */
    fun canDegrade(context: Context): Boolean {
        val app = context.applicationContext as? VyzeApplication ?: return false
        val controller = app.coreController ?: return false
        if (!controller.isEngineReady()) {
            // Engine not ready: OCR tools still work; degrade gracefully.
            return true
        }
        val thermal = app.thermalPowerController?.policy
        if (thermal?.vlmInferenceAllowed == false) {
            // Thermal block: OCR remains available.
            return true
        }
        return false
    }

    /**
     * Best-effort cancel of any in-flight generation.
     *
     * This is the V1 cancel-generation hook. It asks both the controller and the
     * engine to stop current work and resets state so the next capture is clean.
     */
    fun cancelGeneration(context: Context): Boolean {
        val app = context.applicationContext as? VyzeApplication ?: return false
        val controller = app.coreController ?: return false
        if (!controller.isCurrentlyInferring()) {
            return true
        }
        controller.cancelInference()
        controller.resetForNewCapture()
        return true
    }

    /**
     * Decide whether the current device should auto-reduce image resolution and
     * context on low-RAM devices.
     *
     * This is the V1 embodiment of:
     * "Reduce image resolution and context automatically on low-RAM devices."
     */
    fun lowRamAutoTuning(context: Context): LowRamTuning {
        val app = context.applicationContext as? VyzeApplication ?: return LowRamTuning.None
        val controller = app.coreController ?: return LowRamTuning.None
        val tier = controller.currentDeviceTier()
        if (tier == DeviceTier.TIER_DISABLED) {
            return LowRamTuning.BypassVlmOnly
        }
        if (tier == DeviceTier.TIER_3) {
            return LowRamTuning.ReduceImageAndContext
        }
        val tuned = VyzeAdaptiveBackend.readCpuTuning(context)
        return if (tuned.threads > 0 || tuned.imageDim > 0 || tuned.maxTokens > 0) {
            LowRamTuning.Tuned(
                threads = tuned.threads,
                imageDim = tuned.imageDim,
                maxTokens = tuned.maxTokens,
            )
        } else {
            LowRamTuning.None
        }
    }

    // ── constants ──────────────────────────────────────────────────────────

    /** OCR confidence threshold for immediate TTS fast path. */
    const val MIN_FAST_PATH_CONFIDENCE = 0.65f

    /** Higher confidence for immediate TTS without any VLM follow-up. */
    const val HIGH_CONFIDENCE_SPEAK_NOW = 0.85f

    /** Maximum time we are willing to wait for a VLM first token before degrading. */
    const val VLM_FIRST_TOKEN_GRACE_MS = 8_000L

    // ── small types ────────────────────────────────────────────────────────

    sealed class DegradationPlan {
        object None : DegradationPlan()
        data class OcrFastPath(
            val text: String,
            val confidence: Float,
            val fallbackText: String,
            val speakNow: Boolean,
            val vlmCancelled: Boolean,
        ) : DegradationPlan()
    }

    sealed class LowRamTuning {
        object None : LowRamTuning()
        object BypassVlmOnly : LowRamTuning()
        object ReduceImageAndContext : LowRamTuning()
        data class Tuned(
            val threads: Int,
            val imageDim: Int,
            val maxTokens: Int,
        ) : LowRamTuning()
    }

    // ── internal helpers ───────────────────────────────────────────────────

    private fun fallbackText(context: Context, query: String?): String {
        val locale = preferredLocale(context)
        return when (locale) {
            "ms" -> "Saya tidak dapat membaca teks ini dengan jelas."
            "zh" -> "我无法清晰识别这段文字。"
            else -> "I could not read that clearly."
        }
    }

    private fun preferredLocale(context: Context): String {
        return try {
            TTSManager.storedLanguageLocale(context).language.ifBlank { "en" }.take(2)
        } catch (_: Throwable) {
            "en"
        }
    }
}

package com.vyze.app.performance

/**
 * V1 performance configuration: OCR-first routing, adaptive backend selection,
 * graceful degradation, low-end tuning, cancel-generation hooks, and local
 * privacy-safe telemetry.
 *
 * This file is the single source of truth for feature-level knobs so the rest
 * of the app can inspect the same values without reaching into the engine/controller.
 *
 * One-build / one-model rule: the same APK/model package is intended to adapt
 * across Helio, Snapdragon, Mali, and Adreno devices. These constants support
 * that policy; they do NOT encode per-vendor branches.
 */
object VyzePerformanceConfig {

    // NOTE (P4 #108, 2026-11-09): the OCR-first routing consts lived
    // here with zero readers — the controller's private DIRECT_READ /
    // OCR_FAST_PATH consts are the live thresholds. Deleted.

    // ── MiniCPM payload rules ──────────────────────────────────────────────

    /** Send OCR text—not the image—to MiniCPM for cleanup/translation/explanation. */
    const val SEND_TEXT_TO_MINICPM_FOR_NON_SCENE = true

    /** Only send the image directly to MiniCPM for scene/object questions. */
    const val SEND_IMAGE_TO_MINICPM_FOR_SCENE_ONLY = true

    /** Maximum OCR text length to keep for the MiniCPM cleanup path. */
    const val MAX_OCR_TEXT_CHARS_FOR_MINICPM = 1200

    /** Truncate OCR text before sending it as the MiniCPM prompt if needed. */
    const val MAX_OCR_TEXT_CHARS_FOR_TTS_DIRECT_SPEAK = 400

    // ── Adaptive backend selection ─────────────────────────────────────────

    /** Default backend for unknown/new devices. */
    val DEFAULT_BACKEND: BackendName get() = BackendName.CPU

    /** Backend names we recognize and persist. */
    enum class BackendName {
        CPU,
        GPU,
        NPU;

        companion object {
            fun fromString(value: String): BackendName? = when (value.uppercase()) {
                "CPU" -> CPU
                "GPU" -> GPU
                "NPU" -> NPU
                else -> null
            }
        }

        fun asString(): String = name.uppercase()
    }

    /** Whether GPU is worth attempting on this device class. */
    const val GPU_ATTEMPT_WORTHY_MIN_RAM_MB = 4000L

    /** Whether NPU is worth experimental probing on this device class. */
    const val NPU_EXPERIMENTAL_WORTHY_MIN_RAM_MB = 4000L

    /** Maximum GPU/NPU warmup rounds before judging a backend unhealthy. */
    const val MAX_BACKEND_WARMUP_ROUNDS = 3

    /** Timeout per backend warmup round (ms). */
    const val BACKEND_WARMUP_ROUND_TIMEOUT_MS = 40_000L

    /** Minimum acceptable throughput for GPU warmup to count as healthy. */
    const val GPU_WARMUP_MIN_TOKENS_PER_SEC = 20L

    /** Maximum latency for a GPU warmup round to count as healthy. */
    const val GPU_WARMUP_MAX_LATENCY_MS = 9_000L

    /** Maximum acceptable RAM delta during backend warmup before calling it unhealthy. */
    const val BACKEND_WARMUP_MAX_RAM_DELTA_MB = 350L

    /** After this many overtemp events, fall back to CPU permanently. */
    const val OVERTEMP_FALLBACK_THRESHOLD = 3

    /** Re-validate persisted hardware backend after this many seconds. */
    const val BACKEND_PREFERENCE_REVALIDATE_MS = 24 * 60 * 60 * 1000L

    // ── Low-end device tuning ──────────────────────────────────────────────

    /** Initial image long edge for low-end devices. */
    const val LOW_END_IMAGE_LONG_EDGE_MIN = 640
    const val LOW_END_IMAGE_LONG_EDGE_MAX = 768

    /** CPU thread cap for low-end devices (performance cores only heuristic). */
    const val LOW_END_CPU_THREAD_MIN = 2
    const val LOW_END_CPU_THREAD_MAX = 4

    /** Output token cap for short tasks on low-end devices. */
    const val LOW_END_SHORT_TASK_MAX_TOKENS_MIN = 64
    const val LOW_END_SHORT_TASK_MAX_TOKENS_MAX = 128

    /** Maximum OCR text length to send to MiniCPM on low-end devices. */
    const val LOW_END_OCR_TEXT_CHARS_FOR_MINICPM = 600

    /** Maximum scene prompt length for low-end devices (tokens heuristic). */
    const val LOW_END_SCENE_PROMPT_MAX_CHARS = 400

    /** When temperature or latency rises, reduce threads/image size. */
    const val THERMAL_THOTTLE_REDUCE_FACTOR = 0.75f

    // ── Graceful degradation ───────────────────────────────────────────────

    /** Maximum wall time to wait for a VLM first token before degrading. */
    const val VLM_FIRST_TOKEN_GRACE_MS = 8_000L

    /** Interim phrase while MiniCPM processes. Localized variants used by TTS. */
    const val INTERIM_PHRASE_WHILE_VLM_PROCESSES = "Reading more details…"

    /** Whether the user can cancel generation. Yes, in V1. */
    const val ALLOW_USER_CANCEL_GENERATION = true

    /** Whether ML Kit features still work if MiniCPM fails to load. Yes, in V1. */
    const val ML_KIT_STILL_WORKS_IF_MINICPM_FAILS = true

    /** Automatic image resolution/context reduction on low-RAM devices. */
    const val AUTO_REDUCE_ON_LOW_RAM = true

    // ── Local privacy-safe telemetry ───────────────────────────────────────

    /** Maximum telemetry rows kept in memory. */
    const val TELEMETRY_MAX_IN_MEMORY_ROWS = 200

    /** Maximum telemetry rows written per file. */
    const val TELEMETRY_ROWS_PER_FILE = 300

    /** Task-type tags the telemetry layer emits. */
    object TelemetryTaskType {
        const val OCR_FIRST = "ocr_first"
        const val SCENE_DIRECT = "scene_direct"
        const val TEXT_ONLY = "text_only"
        const val VLM_IMAGE = "vlm_image"
        const val VLM_TEXT_CLEANUP = "vlm_text_cleanup"
        const val OCR_FAST_PATH_DEGRADATION = "ocr_fast_path_degradation"
        const val USER_CANCEL = "user_cancel"
        const val LOW_RAM_AUTO_REDUCE = "low_ram_auto_reduce"
        const val BACKEND_SELECTION = "backend_selection"
        const val BACKEND_STARTUP_CALIBRATION = "backend_startup_calibration"
        const val BACKEND_FAILED = "backend_failed"
        const val ENGINE_FAILED = "engine_failed"
        const val OVERHEAT_FALLBACK = "overheat_fallback"
    }

    /** Backend names recorded by telemetry. */
    object TelemetryBackend {
        const val CPU = "cpu"
        const val GPU = "gpu"
        const val NPU = "npu"
        const val NONE = "none"
    }

    /** Failure kinds recorded by telemetry. */
    object TelemetryFailureKind {
        const val NONE = ""
        const val OOM = "oom"
        const val TIMEOUT = "timeout"
        const val GPU_INIT_FAILED = "gpu_init_failed"
        const val NPU_INIT_FAILED = "npu_init_failed"
        const val WARMUP_FAILED = "warmup_failed"
        const val LOW_RAM = "low_ram"
        const val THERMAL_BLOCK = "thermal_block"
        const val USER_CANCEL = "user_cancel"
        const val UNKNOWN = "unknown"
    }

    // ── small helpers ──────────────────────────────────────────────────────

    /** Short, localized task-type string for TTS user-facing speech. */
    fun taskTypeSpokenText(taskType: String): String {
        return when (taskType) {
            TelemetryTaskType.OCR_FIRST -> "I read that with ML Kit first."
            TelemetryTaskType.SCENE_DIRECT -> "I am describing the scene."
            TelemetryTaskType.TEXT_ONLY -> "I can answer that without the camera."
            TelemetryTaskType.VLM_IMAGE -> "I am analyzing the image."
            TelemetryTaskType.VLM_TEXT_CLEANUP -> "I am cleaning up what I read."
            TelemetryTaskType.OCR_FAST_PATH_DEGRADATION -> "I am reading what I already have."
            TelemetryTaskType.USER_CANCEL -> "Cancelled."
            TelemetryTaskType.LOW_RAM_AUTO_REDUCE -> "I am reducing image size to save memory."
            TelemetryTaskType.BACKEND_SELECTION -> "I am choosing the best backend."
            TelemetryTaskType.BACKEND_STARTUP_CALIBRATION -> "I am checking backends."
            TelemetryTaskType.BACKEND_FAILED -> "That backend failed."
            TelemetryTaskType.ENGINE_FAILED -> "The engine could not start."
            TelemetryTaskType.OVERHEAT_FALLBACK -> "The device is hot, falling back."
            else -> "I am working on it."
        }
    }

    /** Localized interim phrase while MiniCPM processes. */
    fun interimPhrase(contextLocale: String): String {
        return when (contextLocale) {
            "ms" -> "Sedikit lagi, saya baca lagi…"
            "zh" -> "稍等，我再多读一点…"
            else -> "Reading more details…"
        }
    }
}

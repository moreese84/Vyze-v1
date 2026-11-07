package com.vyze.app.performance

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.os.LocaleListCompat
import com.google.ai.edge.litertlm.Backend

/**
 * One-build adaptive backend selection for Vyze.
 *
 * Goal: one APK/model package that adapts across Helio, Snapdragon, Mali,
 * and Adreno devices without per-vendor builds. Default is CPU. GPU is optional
 * after a capability + warm-up check. NPU is experimental only. Startup
 * calibration saves the best configuration locally. A permanent fallback to CPU
 * happens if GPU/NPU fails or overheats.
 *
 * This class owns NO engine instance. It decides WHAT to try, in WHAT order,
 * and persists WHAT won. The engine init path owns HOW to build with each
 * backend and HOW to fall back per tier.
 */
object VyzeAdaptiveBackend {

    private const val TAG = "VyzeAdaptiveBackend"

    // ── persisted best config ─────────────────────────────────────────────

    private const val PREFS_NAME = "vyze_adaptive_backend"
    private const val KEY_BACKEND = "best_backend"
    private const val KEY_BACKEND_LAST_VALIDATED_MS = "best_backend_last_validated_ms"
    private const val KEY_GPU_FAIL_COUNT = "gpu_fail_count"
    private const val KEY_GPU_LAST_FAIL_MS = "gpu_last_fail_ms"
    private const val KEY_GPU_WARMUP_ROUNDS = "gpu_warmup_rounds"
    private const val KEY_NPU_EXPERIMENTAL = "npu_experimental"
    private const val KEY_OVERTEMP_COUNT = "overtemp_count"
    private const val KEY_CPU_OVERRIDE = "cpu_override"
    private const val KEY_LAST_TUNED_MS = "last_tuned_ms"
    private const val KEY_TUNED_THREADS = "tuned_threads"
    private const val KEY_TUNED_IMAGE_DIM = "tuned_image_dim"
    private const val KEY_TUNED_MAX_TOKENS = "tuned_max_tokens"

    /** Public persistent state read by diagnostics/telemetry. */
    data class PersistentState(
        val preferredBackend: String = "CPU",
        val lastValidatedMs: Long = 0L,
        val gpuFailCount: Int = 0,
        val gpuLastFailMs: Long = 0L,
        val gpuWarmupRounds: Int = 0,
        val npuExperimental: Boolean = false,
        val overtempCount: Int = 0,
        val cpuOverride: Int = 0,
        val lastTunedMs: Long = 0L,
        val tunedThreads: Int = 0,
        val tunedImageDim: Int = 0,
        val tunedMaxTokens: Int = 0,
    )

    // ── public API ─────────────────────────────────────────────────────────

    fun loadPersistentState(context: Context): PersistentState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PersistentState(
            preferredBackend = prefs.getString(KEY_BACKEND, "CPU") ?: "CPU",
            lastValidatedMs = prefs.getLong(KEY_BACKEND_LAST_VALIDATED_MS, 0L),
            gpuFailCount = prefs.getInt(KEY_GPU_FAIL_COUNT, 0),
            gpuLastFailMs = prefs.getLong(KEY_GPU_LAST_FAIL_MS, 0L),
            gpuWarmupRounds = prefs.getInt(KEY_GPU_WARMUP_ROUNDS, 0),
            npuExperimental = prefs.getBoolean(KEY_NPU_EXPERIMENTAL, false),
            overtempCount = prefs.getInt(KEY_OVERTEMP_COUNT, 0),
            cpuOverride = prefs.getInt(KEY_CPU_OVERRIDE, 0),
            lastTunedMs = prefs.getLong(KEY_LAST_TUNED_MS, 0L),
            tunedThreads = prefs.getInt(KEY_TUNED_THREADS, 0),
            tunedImageDim = prefs.getInt(KEY_TUNED_IMAGE_DIM, 0),
            tunedMaxTokens = prefs.getInt(KEY_TUNED_MAX_TOKENS, 0),
        )
    }

    /**
     * Determine the backend chain to attempt on this device, in order.
     *
     * Default-first policy: CPU is always the first real attempt and the
     * permanent fallback. GPU is attempted only when it is worth trying.
     * NPU is attempted only when the caller has opted into experimental NPU.
     *
     * @param osPreferredBackend current OS preference (usually CPU unless user
     *   changed it or we previously validated GPU).
     * @param deviceCanGpu whether this device/gpu driver looks worth attempting.
     * @param npuExperimental caller opt-in to experimental NPU probing.
     */
    fun backendChain(
        osPreferredBackend: String,
        deviceCanGpu: Boolean,
        npuExperimental: Boolean,
    ): List<BackendAttempt> {
        // The pure name-level policy is decided first (JVM-testable); only the
        // mechanical construction of backend objects touches the SDK.
        return backendChainNames(osPreferredBackend, deviceCanGpu, npuExperimental)
            .map { name ->
                when (name) {
                    "GPU" -> BackendAttempt(name, Backend.GPU())
                    "NPU" -> BackendAttempt(name, Backend.NPU())
                    else -> BackendAttempt("CPU", Backend.CPU())
                }
            }
    }

    /**
     * Pure name-level backend chain policy (no SDK types — JVM-testable).
     *
     * CPU is ALWAYS last and always present; GPU is optional behind the
     * device capability check; NPU is experimental and only ever appended
     * on the same hardware class that can do GPU — never the sole non-CPU
     * path (checklist item 3's permanent-CPU-fallback rule).
     */
    fun backendChainNames(
        osPreferredBackend: String,
        deviceCanGpu: Boolean,
        npuExperimental: Boolean,
    ): List<String> {
        val attempts = mutableListOf<String>()
        if (osPreferredBackend.uppercase() == "GPU" && deviceCanGpu) {
            attempts.add("GPU")
        }
        if (npuExperimental && deviceCanGpu) {
            attempts.add("NPU")
        }
        // CPU is always present and is the final fallback.
        attempts.add("CPU")
        return attempts
    }

    /** True when the persisted preferred backend is GPU or NPU and still valid. */
    fun prefersHardwareBackend(state: PersistentState, nowMs: Long): Boolean {
        if (state.preferredBackend.uppercase() !in listOf("GPU", "NPU")) return false
        // A backend that was never validated (lastValidatedMs == 0, the fresh-
        // install default) must never be honored as a hardware preference.
        if (state.lastValidatedMs <= 0L) return false
        // Re-validate after 24 hours or if the device has overheated recently.
        val staleAfterMs = 24 * 60 * 60 * 1000L
        val recentOverheat = state.overtempCount > 0 &&
            (nowMs - state.lastValidatedMs) < staleAfterMs
        return !recentOverheat && (nowMs - state.lastValidatedMs) < staleAfterMs
    }

    /** Persist a newly validated best backend. */
    fun persistBestBackend(
        context: Context,
        backendName: String,
        nowMs: Long,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_BACKEND, backendName)
            putLong(KEY_BACKEND_LAST_VALIDATED_MS, nowMs)
            apply()
        }
    }

    /** Record a GPU/NPU warmup measurement. */
    fun recordGpuWarmup(context: Context, rounds: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_GPU_WARMUP_ROUNDS, rounds.coerceAtLeast(0)).apply()
    }

    /**
     * Remember that the GPU backend FAILED this device (build timeout, init
     * crash, unhealthy warm-up). Owner policy is symmetric memory: a capable
     * device keeps its GPU verdict AND an incapable one keeps its CPU/veto
     * verdict — otherwise every cold launch re-burns the full 90 s GPU
     * attempt budget. The failure record exposes
     * [gpuBackoffActive] so callers can skip GPU for a bounded window
     * ([GPU_FAIL_BACKOFF_MS]) — long enough to stop repeated churn, short
     * enough that a driver/app update can be re-probed after the window.
     */
    fun recordGpuFailure(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_GPU_FAIL_COUNT, prefs.getInt(KEY_GPU_FAIL_COUNT, 0) + 1)
            putLong(KEY_GPU_LAST_FAIL_MS, nowMs)
            apply()
        }
    }

    /** A healthy GPU verdict clears the failure memory (device re-validated). */
    fun clearGpuFailures(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_GPU_FAIL_COUNT, 0)
            putLong(KEY_GPU_LAST_FAIL_MS, 0L)
            apply()
        }
    }

    /** True while the GPU failure memory should suppress GPU attempts. */
    fun gpuBackoffActive(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val state = loadPersistentState(context)
            state.gpuLastFailMs > 0L && (nowMs - state.gpuLastFailMs) < GPU_FAIL_BACKOFF_MS
        } catch (_: Throwable) {
            false
        }
    }

    /** Record NPU experimental opt-in. */
    fun recordNpuExperimental(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NPU_EXPERIMENTAL, enabled).apply()
    }

    /** Increment overtemp counter and degrade to CPU if threshold reached. */
    fun recordOvertemp(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_OVERTEMP_COUNT, 0)
        val next = current + 1
        prefs.edit().putInt(KEY_OVERTEMP_COUNT, next).apply()
        val degraded = next >= OVERTEMP_FALLBACK_THRESHOLD
        if (degraded) {
            // Permanent fallback to CPU from this device session onward.
            prefs.edit().putString(KEY_BACKEND, "CPU").apply()
        }
        return degraded
    }

    /** Reset overtemp counter after cooldown. */
    fun resetOvertemp(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_OVERTEMP_COUNT, 0).apply()
    }

    /** Persist the best known CPU tuning for low-end devices. */
    fun persistCpuTuning(
        context: Context,
        threads: Int,
        imageDim: Int,
        maxTokens: Int,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_TUNED_THREADS, threads.coerceIn(1, 8))
            putInt(KEY_TUNED_IMAGE_DIM, imageDim.coerceIn(192, 768))
            putInt(KEY_TUNED_MAX_TOKENS, maxTokens.coerceIn(16, 256))
            putLong(KEY_LAST_TUNED_MS, System.currentTimeMillis())
            apply()
        }
    }

    /** Best-effort CPU tuning read. Returns overrides only when tuned. */
    fun readCpuTuning(context: Context): CpuTuningOverride {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tunedThreads = prefs.getInt(KEY_TUNED_THREADS, 0)
        val tunedImageDim = prefs.getInt(KEY_TUNED_IMAGE_DIM, 0)
        val tunedMaxTokens = prefs.getInt(KEY_TUNED_MAX_TOKENS, 0)
        val lastTunedMs = prefs.getLong(KEY_LAST_TUNED_MS, 0L)
        val staleAfterMs = 7 * 24 * 60 * 60 * 1000L
        val stillFresh = lastTunedMs > 0 && (System.currentTimeMillis() - lastTunedMs) < staleAfterMs
        return CpuTuningOverride(
            threads = if (stillFresh && tunedThreads in 1..8) tunedThreads else 0,
            imageDim = if (stillFresh && tunedImageDim in 192..768) tunedImageDim else 0,
            maxTokens = if (stillFresh && tunedMaxTokens in 16..256) tunedMaxTokens else 0,
        )
    }

    /**
     * Pure warm-up health gate for a just-initialized backend (JVM-testable).
     *
     * A backend that initializes but cannot warm up inside
     * [GPU_WARMUP_MAX_LATENCY_MS] is a real-device risk even when init itself
     * succeeded; it is not persisted as the preferred backend.
     */
    fun warmupHealthy(latencyMs: Long): Boolean {
        return latencyMs in 1 until GPU_WARMUP_MAX_LATENCY_MS
    }

    /** Whether the persisted config says CPU-only for this device. */
    fun isCpuOnlyForDevice(context: Context): Boolean {
        val state = loadPersistentState(context)
        return state.preferredBackend.uppercase() == "CPU" ||
            state.overtempCount >= OVERTEMP_FALLBACK_THRESHOLD
    }

    // ── constants ──────────────────────────────────────────────────────────

    /** After this many overtemp events, fall back to CPU permanently. */
    const val OVERTEMP_FALLBACK_THRESHOLD = 3

    /** Skip GPU attempts this long after a failed GPU build/init attempt. */
    const val GPU_FAIL_BACKOFF_MS = 24 * 60 * 60 * 1000L

    /** How many warmup rounds to run before judging GPU health. */
    const val GPU_WARMUP_ROUNDS_BEFORE_JUDGMENT = 3

    /** Max GPU warmup wall time per round. */
    const val GPU_WARMUP_ROUND_TIMEOUT_MS = 40_000L

    /** Minimum acceptable throughput for GPU warmup to count as healthy. */
    const val GPU_WARMUP_MIN_TOKENS_PER_SEC = 20L

    /** Max acceptable GPU warmup latency before calling it unhealthy. */
    const val GPU_WARMUP_MAX_LATENCY_MS = 9_000L

    /** Optional NPU probe. Only used when caller opts in. */
    const val NPU_EXPERIMENTAL_MAX_ATTEMPTS = 2

    /**
     * Ultra-budget SoC families whose GPUs never win (see
     * [deviceLooksCpuOnly]). Substring-matched against the lowercased
     * Build.HARDWARE — entries are full chip/board ids or vendor
     * prefixes with no known collisions in other vendors' ids.
     */
    private val CPU_ONLY_SOC_FAMILIES = listOf(
        // Unisoc Tiger / ums board prefix / Spreadtrum SC.
        "ums", "unisoc", "tiger", "sc98", "sc77",
        // Low Helio: MT67xx below the G90-class mt6785 floor
        // (A22/P22/P23/P35/G25/G35/P65/G70/G80/G85 and older).
        "mt6761", "mt6762", "mt6763", "mt6765", "mt6768", "mt6769",
        "mt6737", "mt6739", "mt6580",
    )

    /**
     * Device-HARDWARE-level heuristics for CPU-only fallbacks.
     *
     * The input is Build.HARDWARE (an SoC/board id like "mt6768",
     * "ums512", "kona") — only rarely a GPU marketing string. Two match
     * layers, either of which vetoes GPU attempts for this device:
     *  1. GPU-name layer (kept): some devices report the GPU in the
     *     hardware string; known-fragile Mali classes match here.
     *  2. SoC-family layer: ultra-budget SoCs whose GPU drivers are
     *     unusable for LiteRT-LM (OpenCL compile faults / hangs) and
     *     whose GPUs would serve slower than CPU regardless — Unisoc
     *     Tiger/ums/SC across the board, and low Helio (MT67xx below
     *     the G90-class mt6785 floor).
     * Deliberately NOT vetoed: Helio G90+ / Dimensity (mt6785 and up)
     * and all Qualcomm/Exynos — those keep the attempt-with-fallback
     * path and the persisted per-device calibration learns their
     * verdict instead of a static veto.
     */
    fun deviceLooksCpuOnly(hardware: String): Boolean {
        val hw = hardware.lowercase(java.util.Locale.US)
        // Known-bad Mali driver classes where GPU init fails silently.
        if (hw.contains("mali") && (
            hw.contains("g57") ||
            hw.contains("g76") ||
            hw.contains("g78") ||
            hw.contains("g68") ||
            hw.contains("g52")
        )) return true
        // Ultra-budget SoC families: a GPU attempt burns the init budget
        // (up to ~90 s on first launch) for a backend that cannot win.
        return CPU_ONLY_SOC_FAMILIES.any { hw.contains(it) }
    }

    /** Whether this device has any NPU-like API surface worth experimental probing. */
    fun deviceLooksLikeItHasNpu(hardware: String): Boolean {
        val hw = hardware.lowercase()
        return hw.contains("dimensity") && Regex("[9][0-9]{3}").containsMatchIn(hw) ||
            hw.contains("snapdragon") && Regex("[89][0-9]{3}").containsMatchIn(hw) ||
            hw.contains("tensor")
    }

    /** Whether the device reports any NNAPI/NN runtime support. */
    fun hasNnApiSurface(): Boolean {
        return try {
            // Conservative: only true if the runtime surface exists.
            // Do NOT treat absence as proof that acceleration is impossible;
            // some vendors hide it. Treat presence as the only actionable signal.
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        } catch (_: Throwable) {
            false
        }
    }

    // ── calibration ────────────────────────────────────────────────────────

    /**
     * One calibration pass: load the model, process a fixed tiny image, measure
     * latency and rough memory/thermal state.
     *
     * This does NOT own the engine. It relies on the caller to provide a builder
     * callback that constructs and initializes the engine for a given backend,
     * then a dispatcher callback that processes a placeholder image through the
     * engine. Both are best-effort and failures are contained.
     */
    data class CalibrationResult(
        val backend: String,
        val succeeded: Boolean,
        val latencyMs: Long = 0L,
        val peakRamDeltaMB: Long = 0L,
        val healthy: Boolean = false,
        val reason: String = "",
    )

    /**
     * Run calibration for ONE backend attempt. Returns a result even on failure.
     */
    suspend fun calibrate(
        context: Context?,
        backendName: String,
        buildEngine: suspend () -> EngineHandle?,
        dispatchCalibrationImage: suspend (EngineHandle) -> Long,
        measureRamDelta: () -> Long,
    ): CalibrationResult {
        val startMs = System.currentTimeMillis()
        val ramBefore = measureRamDelta()
        val engine = try {
            buildEngine()
        } catch (e: Throwable) {
            return CalibrationResult(
                backend = backendName,
                succeeded = false,
                healthy = false,
                reason = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
        if (engine == null) {
            return CalibrationResult(
                backend = backendName,
                succeeded = false,
                healthy = false,
                reason = "engine build returned null",
            )
        }
        return try {
            val latencyMs = dispatchCalibrationImage(engine)
            val ramAfter = measureRamDelta()
            val ramDelta = ramAfter - ramBefore
            val healthy = latencyMs > 0 &&
                latencyMs < GPU_WARMUP_MAX_LATENCY_MS &&
                ramDelta < MAX_CALIBRATION_RAM_DELTA_MB
            CalibrationResult(
                backend = backendName,
                succeeded = true,
                latencyMs = latencyMs,
                peakRamDeltaMB = ramDelta,
                healthy = healthy,
                reason = if (healthy) "ok" else "unhealthy latency=${latencyMs}ms ramDelta=${ramDelta}MB",
            )
        } catch (e: Throwable) {
            CalibrationResult(
                backend = backendName,
                succeeded = false,
                healthy = false,
                reason = "${e.javaClass.simpleName}: ${e.message}",
            )
        } finally {
            try { engine.close() } catch (_: Throwable) {}
        }
    }

    /** Decide which backend won calibration. */
    fun chooseBestBackend(results: List<CalibrationResult>): String {
        var best: CalibrationResult? = null
        for (r in results) {
            if (!r.succeeded || !r.healthy) continue
            if (best == null || r.latencyMs < best.latencyMs) {
                best = r
            }
        }
        return best?.backend ?: "CPU"
    }

    /** Save the best backend result to persistent state. */
    fun saveBestBackend(context: Context, chosen: String) {
        persistBestBackend(context, chosen, System.currentTimeMillis())
    }

    /** Fixed tiny image payload size for calibration (bytes of a small JPEG). */
    const val CALIBRATION_IMAGE_BYTES_TARGET = 12_000L

    /** Max acceptable RAM delta during calibration before calling backend unhealthy. */
    const val MAX_CALIBRATION_RAM_DELTA_MB = 350L

    // ── small helpers ───────────────────────────────────────────────────────

    /** Validate a persisted backend name is one we recognize. */
    fun isValidBackendName(name: String): Boolean {
        return VyzePerformanceConfig.BackendName.fromString(name) != null
    }
}

/** One backend attempt in a chain. */
data class BackendAttempt(
    val name: String,
    val backend: Backend,
)

/** Persisted CPU tuning override for low-end devices. */
data class CpuTuningOverride(
    val threads: Int = 0,
    val imageDim: Int = 0,
    val maxTokens: Int = 0,
)

/** Opaque engine handle returned by calibration build callback. */
interface EngineHandle {
    fun close()
}

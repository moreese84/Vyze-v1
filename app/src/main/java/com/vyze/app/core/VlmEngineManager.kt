package com.vyze.app.core
import com.vyze.app.performance.VyzeAdaptiveBackend
import com.vyze.app.util.CrashLogFile

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.vyze.app.memory.MemoryRepository
import com.vyze.app.memory.SimilarInteraction
import java.io.ByteArrayOutputStream
import com.vyze.app.device.AudioCapture
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device VLM engine wrapper using Google's LiteRT-LM framework.
 *
 * ## Model
 * SWAPPABLE BRAIN (see [BrainModel]): MiniCPM-V-4.7-1B (int8vis, 1.70 GB) is the
 * ONLY production brain (owner decision 2026-10-16, single-model rule: no phone
 * carries two brains). gemma-4-E2B-it.litertlm (2.59 GB) is debug-corridor A/B
 * only — production never auto-loads it. Both load through the same LiteRT-LM
 * engine below — per-brain differences (turn format, backend plan, image feed
 * floor, audio capability, experimental flags) resolve through [BrainModel],
 * never hardcoded here.
 *
 * ## Hardware Acceleration — TIERED ENGINE INITIALIZATION STRATEGY
 * The device is classified ONCE at startup into a hardware tier from
 * ActivityManager.MemoryInfo (total RAM) and GPU-class SoC heuristics
 * (Build.HARDWARE):
 *
 *  - **Tier 1** (high-end: >= ~8 GB RAM + flagship GPU): default `Backend.GPU()`
 *    with the full context window and Multi-Token Prediction (MTP —
 *    speculative decoding) enabled.
 *  - **Tier 2** (mid-range: ~6-8 GB RAM, or mid-range GPU / Mali drivers):
 *    attempt `Backend.GPU()` inside a try-catch with fallback to
 *    `Backend.CPU()` (4 threads). Reduced max context tokens. MTP disabled
 *    (explicitly on the CPU fallback).
 *  - **Tier 3** (lower-end: < ~6 GB RAM): initialize DIRECTLY on
 *    `Backend.CPU()` with a reduced context budget (2048 max tokens).
 *  - **Tier 0** (memory-constrained: < ~4 GB RAM): VLM generation is bypassed
 *    entirely. A friendly fallback message is surfaced via [onError] (spoken
 *    as TTS by the controller) while fast-path tools (OCR, haptics, light
 *    check, color analysis) remain fully operational.
 *
 * ### Why: the "m0 was canceled" class of failures
 * On mid-range MediaTek chipsets the GPU backend (OpenCL/Vulkan) or its
 * memory allocation can fail SILENTLY inside the native layer; the pending
 * init coroutine is then cancelled and the failure propagated as an uncaught
 * Job cancellation. This class contains every engine-creation step:
 *  - each backend attempt is wrapped in try-catch (GPU → CPU chain per tier),
 *  - C++/JNI driver errors, UnsatisfiedLinkError and OutOfMemoryError are
 *    classified and logged instead of propagating,
 *  - a timed build guard bounds each attempt (a hung native constructor can
 *    no longer stall initialization forever),
 *  - a VLM init failure NEVER cancels the parent CoroutineScope and never
 *    breaks non-VLM features (standalone OCR, Light Check, Color Analysis).
 *
 * ## Prompt Format
 * Uses Gemma 4's turn format:
 * `<start_of_turn>user [System Prompt + User/Image Context]<end_of_turn><start_of_turn>model` — the system directive is folded into the user turn (Gemma defines no system role)
 * Image patch tokens are bound natively by the LiteRT-LM engine when
 * passing the Bitmap — no literal [IMAGE_TOKEN] placeholder needed.
 *
 * ## Memory Management
 * Incoming Bitmap frames are downscaled proportionally when exceeding the
 * TIER-ADJUSTED target dimension. Gemma 4 handles dynamic aspect ratios
 * natively — no rigid center-cropping applied. All scaled bitmaps are
 * explicitly recycled after inference to prevent memory leaks. On lower
 * tiers the engine GCs / trims memory BEFORE allocating KV-cache tensors.
 *
 * ## Engine Warm-up
 * A dummy text-only message is run through the engine immediately after
 * initialization to pre-compile GPU kernels (or warm the CPU decoder) and
 * minimize first-inference latency.
 *
 * ## API
 * Use [analyzeImage] to send a camera frame + text prompt and get a response.
 * Uses the callback-based MessageCallback to avoid SendChannel crashes.
 */
class VlmEngineManager(
    private val context: Context,
    private val memoryRepository: MemoryRepository? = null
) : Closeable {

    private var engine: Engine? = null

    @Volatile
    private var activeBackend: String = "NONE"

    @Volatile
    private var isInitialized = false

    /** Whether this device is classified as low-RAM by Android. */
    private val isLowRamDevice: Boolean by lazy {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (e: Throwable) { false }
    }

    // ── Dynamic Hardware Detection (Tiered Initialization) ─────────

    /**
     * The device hardware profile — detected ONCE (lazy) at engine startup.
     * Drives the tiered backend plan, context budget, image dimension cap,
     * memory-pressure thresholds and MTP gating for this session.
     */
    private val deviceProfile: DeviceProfile by lazy { detectDeviceProfile() }

    /**
     * The brain bound to the CURRENT engine lifecycle. Set once in [initialize]
     * (production default per [BrainModel.PROD_DEFAULT]; debug
     * override via [brainOverride]) and kept across [resetSession] so a
     * mid-session reinit can never hot-swap brains. Every inference seam
     * (turn format, feed dimensions, audio capability, experimental flags)
     * reads from here.
     */
    @Volatile
    private var activeBrain: BrainModel? = null

    /** Brain for inference-time seams; prod default until init resolves the file. */
    private fun brainForTurn(): BrainModel = activeBrain ?: BrainModel.PROD_DEFAULT

    /**
     * Choose the brain for THIS engine lifecycle:
     *  1. Debug-forced ([brainOverride] — BrainToggleReceiver): return it and
     *     let a missing file fail LOUDLY (forced means corridor A/B, not
     *     fallback).
     *  2. Otherwise: PROD_DEFAULT — the ONLY production brain. A missing file
     *     is a loud not-found error naming the file the fleet needs;
     *     there is deliberately NO auto-fallback to a second model.
     */
    private fun resolveBrainForInit(): BrainModel {
        brainOverride?.let { forced ->
            CrashLogFile.log(TAG, "Brain FORCED by debug selector: ${forced.displayName} (missing file = loud failure)")
            return forced
        }
        CrashLogFile.log(TAG, "Brain resolved: ${BrainModel.PROD_DEFAULT.displayName} (production default)")
        return BrainModel.PROD_DEFAULT
    }

    /** True when [brain]'s file is present in a resolved location with a sane size. */
    private fun modelFileExistsFor(brain: BrainModel): Boolean {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null) {
            val f = File(downloadsDir, brain.fileName)
            if (f.exists() && f.length() > MIN_MODEL_SIZE) return true
        }
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val f = File(externalDir, brain.fileName)
            if (f.exists() && f.length() > MIN_MODEL_SIZE) return true
        }
        return false
    }

    /**
     * Friendly fallback message (spoken as TTS by the controller) when VLM
     * generation is bypassed (Tier 0) — fast-path tools remain operational.
     */
    @Volatile
    private var gracefulFallbackMessage: String? = null

    /**
     * Consecutive failed initialize() attempts. Past [MAX_INIT_FAILURES] the
     * engine stops retrying and degrades gracefully — a friendly message goes
     * out and non-VLM features keep working. Reset by [close].
     */
    @Volatile
    private var initFailureCount = 0

    /**
     * Holder for an engine build that outlived its time budget. The native
     * Engine constructor + initialize() cannot be interrupted — when a build
     * times out we park its result here so the NEXT attempt can drain-wait
     * for it and close it properly instead of leaking it (or racing a second
     * native init against it on a memory-constrained device).
     */
    private class EngineBuildResult {
        @Volatile var engine: Engine? = null
        @Volatile var error: Throwable? = null
    }

    @Volatile
    private var pendingBuild: EngineBuildResult? = null

    /**
     * Available device-level memory in MB.
     * Uses ActivityManager.MemoryInfo which reports total available RAM
     * (including native memory where LiteRT-LM loads), not just Java heap.
     */
    private fun availableHeapMB(): Long {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                return memInfo.availMem / (1024 * 1024)
            }
        } catch (_: Throwable) {}
        // Fallback: Java heap only (less accurate)
        val runtime = Runtime.getRuntime()
        return (runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())) / (1024 * 1024)
    }

    /**
     * TOTAL device RAM in MB (ActivityManager.MemoryInfo.totalMem — includes
     * the native heap where LiteRT-LM loads model weights and KV-cache).
     */
    private fun totalRamMB(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val total = memInfo.totalMem / (1024L * 1024L)
                if (total > 0) total else fallbackTotalRamMB()
            } else {
                fallbackTotalRamMB()
            }
        } catch (_: Throwable) {
            fallbackTotalRamMB()
        }
    }

    /** Last-resort total-RAM probe via /proc/meminfo (0 = unknown). */
    private fun fallbackTotalRamMB(): Long = try {
        val firstLine = File("/proc/meminfo").readLines().firstOrNull() ?: return 0L
        Regex("(\\d+)").find(firstLine)?.groupValues?.get(1)?.toLongOrNull()?.div(1024L) ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /**
     * Detect the hardware profile and classify the device into a tier.
     * Never throws — on any detection failure the device falls back to the
     * safest tier that still carries a CPU fallback chain (Tier 2), never to
     * a state that would silently disable VLM on a capable phone.
     */
    private fun detectDeviceProfile(): DeviceProfile {
        val totalRamMb = try { totalRamMB() } catch (_: Throwable) { 0L }
        val hardware = try { Build.HARDWARE ?: "unknown" } catch (_: Throwable) { "unknown" }
        val flagship = try { isFlagshipGpu(hardware) } catch (_: Throwable) { false }
        val midGpu = try { isMidRangeGpu(hardware) } catch (_: Throwable) { false }
        val gles = try { detectGlesVersion() } catch (_: Throwable) { "GLES unknown" }

        val tier = classifyTier(totalRamMb, flagship, midGpu, isLowRamDevice)
        val profile = DeviceProfile(tier, totalRamMb, hardware, flagship, midGpu, gles)

        Log.i(TAG, "Device tier detected: ${profileSummary(profile)}")
        CrashLogFile.log(TAG, "=== DEVICE TIER ===")
        CrashLogFile.log(TAG, "Tier: ${tier.label}")
        CrashLogFile.log(TAG, "Total RAM: ${totalRamMb}MB (reported) | hw=$hardware | flagshipGPU=$flagship | midGPU=$midGpu | $gles")
        CrashLogFile.log(TAG, "Low-RAM flag (Android): $isLowRamDevice")
        return profile
    }

    /**
     * Flagship-GPU heuristic over [Build.HARDWARE]. Covers the SoC families
     * whose OpenCL/Vulkan drivers are known-good for LiteRT-LM GPU delegates:
     * Qualcomm Snapdragon 8-series, MediaTek Dimensity 8000/9000 series and
     * Google Tensor.
     */
    private fun isFlagshipGpu(hardware: String): Boolean {
        val hw = hardware.lowercase(Locale.US)
        val flagshipFamilies = listOf(
            // Qualcomm Snapdragon 8-series SoC identifiers
            "sm8", "sdm8", "msmn8", "qcs8", "msmnile", "kona", "lahaina",
            "taro", "kalama", "pineapple", "sun",
            // MediaTek Dimensity 8000/9000 series (mt6899 = Dimensity 8400/
            // 8400-Ultimate — Infinix GT 50 Pro class; verified 2026-10-10
            // that this SoC was falling through to Tier 2 and running
            // throttled budgets it does not need)
            "mt6983", "mt6985", "mt6989", "mt6893", "mt6895", "mt6896",
            "mt6991", "mt6899", "mt6879", "mt6886",
            // Google Tensor
            "tensor", "gs101", "gs201", "zuma", "zumapro"
        )
        if (flagshipFamilies.any { hw.contains(it) }) return true
        if (hw.contains("snapdragon") && Regex("8[0-9]{2,3}").containsMatchIn(hw)) return true
        if (hw.contains("dimensity") && Regex("[89][0-9]{3}").containsMatchIn(hw)) return true
        return false
    }

    /**
     * Mid-range-GPU heuristic — in practice the Mali-driver MediaTek Helio /
     * Dimensity 6000/7000 families and Qualcomm 6/7-series, where GPU init
     * failures ("m0 was canceled", OpenCL kernel-compile faults) are common.
     */
    private fun isMidRangeGpu(hardware: String): Boolean {
        val hw = hardware.lowercase(Locale.US)
        val midFamilies = listOf(
            // MediaTek Helio + Dimensity 6000/7000 (Mali drivers)
            "mt6785", "mt6789", "mt6833", "mt6853", "mt6855", "mt6873",
            "mt6875", "mt6877", "mt6883", "mt6885", "helio",
            // Qualcomm 6/7-series
            "sm6", "sm7", "sdm6", "sdm7", "qcm6", "qcs6", "bengal", "atoll", "lito", "holi"
        )
        if (midFamilies.any { hw.contains(it) }) return true
        if (hw.contains("dimensity") && Regex("[67][0-9]{3}").containsMatchIn(hw)) return true
        return false
    }

    /** GLES version reported by the device configuration (diagnostic only). */
    private fun detectGlesVersion(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = am?.deviceConfigurationInfo
            if (info != null) "GLES ${info.glEsVersion}" else "GLES unknown"
        } catch (_: Throwable) {
            "GLES unknown"
        }
    }

    private fun profileSummary(p: DeviceProfile): String =
        "${p.tier.label} | totalRAM=${p.totalRamMB}MB | hw=${p.hardware} | " +
            "flagshipGPU=${p.flagshipGpu} midGPU=${p.midRangeGpu} | ${p.glesVersion}"

    // Callbacks for UI updates
    var onTokenGenerated: ((String, String) -> Unit)? = null  // (token, sessionId)
    var onComplete: ((String, String) -> Unit)? = null         // (response, sessionId)
    var onError: ((String, String) -> Unit)? = null            // (error, sessionId)
    var onModelCopyProgress: ((copied: Long, total: Long) -> Unit)? = null
    var onStepProgress: ((Int, String) -> Unit)? = null

    /**
     * The active user speech locale, mirrored from VyzeCoreController on
     * every setUserLocale(). Default US English until the first locale
     * detection lands (matching the controller's default).
     */
    @Volatile
    var activeUserLocale: Locale = Locale.US
        private set

    /** Called by VyzeCoreController.setUserLocale() — keeps the engine's
     *  directive assembly in sync with the active speech language. */
    fun setUserLocale(locale: Locale) {
        activeUserLocale = locale
    }

    /**
     * Language-bound second-person clause for the system directive, in the
     * ACTIVE language (LANGUAGE-MIRRORING FIX): the persona constant is
     * deliberately English-neutral, and these spatial rules are appended per
     * turn so a 2B model never sees conflicting English persona examples
     * while producing Malay/Chinese output. English full rule; Malay and
     * Chinese translations carry the same binding in their own script;
     * unknown languages fall back to English wording (harmless — the
     * [OUTPUT LANGUAGE] / REMEMBER wrappers in DynamicPromptBuilder remain
     * the language authority on every turn).
     */
    private fun perspectiveClauseFor(locale: Locale): String = when (locale.language) {
        "ms" -> PERSPECTIVE_MS
        "zh" -> PERSPECTIVE_ZH
        else -> PERSPECTIVE_EN
    }

    /**
     * Latch for the currently active inference. Promoted to instance field
     * so [interrupt] can signal it without destroying the engine.
     *
     * Set before [CountDownLatch.await] in [analyzeImage], cleared after
     * the await returns. Only one inference runs at a time (blocking
     * CountDownLatch), so a single reference is safe.
     */
    @Volatile
    private var activeLatch: CountDownLatch? = null

    /**
     * Set to true by [interrupt] to signal that the current inference was
     * cancelled externally. Checked after latch.await() to decide whether
     * to close the Conversation and fire callbacks.
     */
    @Volatile
    private var wasInterrupted = false

    /**
     * Conversation whose native generation is currently running. Exposed to
     * [interrupt] so it can call [Conversation.cancelProcess] — releasing only
     * the Java-side latch leaves the native thread generating to the end, which
     * keeps the engine busy and makes the NEXT sendMessageAsync fail with
     * "Failed to start nativeSendMessageAsync".
     */
    @Volatile
    private var activeConversation: Conversation? = null

    /**
     * Serializes native generation. LiteRT-LM runs ONE generation at a time —
     * two overlapping [runConversation] calls make the engine reject the second
     * sendMessageAsync. Guarded by [generationMutex]; the bounded drain in
     * [runConversation] additionally waits for an abandoned native thread.
     */
    private val generationMutex = Mutex()

    /**
     * Serializes ENGINE CREATION / reinit. Only one native engine build may
     * run at a time — overlapping Engine(engineConfig) + initialize() calls
     * double the memory footprint on exactly the constrained devices where
     * allocation fails, and two concurrent native inits race for the GPU
     * driver. All creation paths ([initialize], [resetSession]) funnel
     * through [createEngineGuarded] under this mutex.
     */
    private val engineInitMutex = Mutex()

    /**
     * Completes when the CURRENT (possibly abandoned) native generation has
     * fully ended. Starts completed — the engine is idle at boot. Replaced by
     * each new run; the terminal onDone/onError callback of that run completes
     * its own deferred, so a following inference can drain-wait on it.
     */
    private var generationFinished = CompletableDeferred<Unit>().apply { complete(Unit) }

    // ── Storage Permission Check ──────────────────────────────────

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }
    }

    fun buildStorageSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    // ── Initialization (Tiered Engine Initialization Strategy) ────

    /**
     * Initialize the Gemma 4 E2B engine using the TIERED BACKEND STRATEGY.
     *
     * The device is classified once by [detectDeviceProfile] and the backend
     * chain follows the tier:
     *
     *  | Tier | Device                                  | Backend plan                          | Context | MTP |
     *  |------|------------------------------------------|---------------------------------------|---------|-----|
     *  | 1    | >= ~8GB RAM + flagship GPU               | GPU                                   | 4096    | on  |
     *  | 2    | ~6-8GB RAM or mid-range GPU/Mali         | GPU → CPU (4 threads)                 | 3072    | off |
     *  | 3    | < ~6GB RAM                               | CPU (4 threads) directly              | 2048    | off |
     *  | 0    | OS low-RAM flag, or < ~4GB reported RAM  | none — VLM bypassed, TTS fallback msg | —       | —   |
     *
     * Every engine-creation failure (JNI/C++ driver errors, OOM, GPU-init
     * cancellations like "m0 was canceled") is caught HERE and either retried
     * on the next backend in the chain or counted for graceful degradation —
     * it can never cancel the parent CoroutineScope or break non-VLM
     * features (standalone OCR, Light Check, Color Analysis).
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        CrashLogFile.log(TAG, "=== INIT START (tiered) ===")

        // ── Tier 0: VLM bypassed entirely (< ~4GB RAM) ───────────────
        // Keep fast-path tools (OCR, haptics, light check, color analysis)
        // fully operational — they never touch the VLM engine. Surface a
        // friendly message through onError; the controller speaks it via TTS.
        if (deviceProfile.tier == DeviceTier.TIER_DISABLED) {
            gracefulFallbackMessage = VLM_BYPASS_MESSAGE
            CrashLogFile.log(
                TAG,
                "Tier 0 device (totalRAM=${deviceProfile.totalRamMB}MB) — " +
                    "VLM generation bypassed; fast-path tools stay active"
            )
            onStepProgress?.invoke(100, "Fast tools ready")
            onError?.invoke(gracefulFallbackMessage!!, "")
            return@withContext false
        }

        // ── Circuit breaker: too many prior failures → degrade gracefully ──
        if (initFailureCount >= MAX_INIT_FAILURES) {
            val msg = VLM_UNAVAILABLE_MESSAGE
            gracefulFallbackMessage = msg
            CrashLogFile.log(
                TAG,
                "Init skipped after $initFailureCount failures — degrading gracefully; " +
                    "non-VLM features stay active"
            )
            onError?.invoke(msg, "")
            return@withContext false
        }

        // ── Pre-flight RAM Check ──────────────────────────────────
        // Gemma 4 E2B's ~2.59 GB model mmap + KV-cache + image encoding peak
        // far above what a memory-constrained device sustains. Reject early
        // instead of dying mid-init with an uncatchable NATIVE OOM (a Java
        // try-catch cannot stop a native allocator abort). The bar is
        // TIER-AWARE — see [requiredFreeRamMB]: the CPU-direct tier keeps
        // weights, KV-cache and activations all in system RAM.
        // Trigger GC first to reclaim idle memory before measuring.
        System.gc()
        Thread.sleep(100)
        val freeMB = availableHeapMB()
        val requiredMB = requiredFreeRamMB(deviceProfile.tier, isLowRamDevice)
        if (freeMB < requiredMB) {
            // A deterministic condition, not a transient backend fault — the
            // device will not find hundreds of MB in the next seconds. The
            // technical numbers go to LOGS ONLY; the user hears the friendly
            // fallback on the FIRST attempt (the old path spoke raw
            // "Insufficient RAM: …" strings to a blind user) and the failure
            // counts toward the circuit breaker so retries stop churning.
            // Fast-path tools (OCR, light, color) stay fully operational.
            initFailureCount++
            gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            val detail = "Pre-flight RAM check failed: ${freeMB}MB free, need ${requiredMB}MB " +
                "[tier=${deviceProfile.tier.label}, lowRam=$isLowRamDevice] — $VLM_UNAVAILABLE_MESSAGE"
            Log.e(TAG, detail)
            CrashLogFile.logError(TAG, detail)
            onStepProgress?.invoke(100, "Fast tools ready")
            onError?.invoke(VLM_UNAVAILABLE_MESSAGE, "")
            return@withContext false
        }
        Log.i(TAG, "RAM check passed: ${freeMB}MB free (lowRam=$isLowRamDevice, required=${requiredMB}MB)")
        CrashLogFile.log(TAG, "RAM: ${freeMB}MB free, lowRam=$isLowRamDevice")

        try {
            // Step -1: Drop the unrestorable MASTER program caches BEFORE any
            // build. Every GPU attempt appends ~246 MB of unusable copies
            // (restore fails with DATA_LOSS on every cold launch — upstream
            // litert-lm/ml-drift bug) and three launches grew the file
            // 993 MB → 1.5 GB on this device. Deleting bounds storage to one
            // build generation; the small working caches (vision encoder /
            // mtp drafter / xnnpack) are kept.
            cleanupUnrestorableProgramCaches()

            // Step 0: Ensure native library is loaded
            CrashLogFile.log(TAG, "Step 0: Loading native library")
            ensureNativeLibLoaded()

            // Step 1: Resolve brain + model file. Single brain — MiniCPM is
            // the only production brain (owner decision); its missing file is
            // a LOUD not-found error, never a fallback to a second model.
            // Debug selector can force a different brain for corridor A/B.
            onStepProgress?.invoke(10, "Resolving model file...")
            CrashLogFile.log(TAG, "Step 1: Resolving model file")

            val brain = resolveBrainForInit()
            activeBrain = brain
            Log.i(TAG, "Starting brain init [${brain.displayName}, tier=${deviceProfile.tier.label}]...")

            val modelFile = resolveModelFile(brain)
            if (modelFile == null) {
                val errorMsg = buildModelNotFoundError(brain)
                Log.e(TAG, errorMsg)
                CrashLogFile.logError(TAG, errorMsg)
                onError?.invoke(errorMsg, "")
                return@withContext false
            }

            Log.i(TAG, "Model resolved: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            CrashLogFile.log(TAG, "Brain: ${brain.displayName} | Model: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)}MB)")

            // Step 2: Walk the tier's backend plan (GPU → CPU per tier).
            // NPU is skipped — the Gemma 4 E2B generic model does not ship with
            // TF_LITE_PREFILL_DECODE for NPU, so NPU init always fails and
            // wastes 5-10s on mid-tier Dimensity/Snapdragon devices.
            // ── ADAPTIVE BACKEND POLICY (persisted calibration) ──────
            // Checklist item 3: read what this exact device/store learned
            // last time. A persisted overtemp-gated CPU-only verdict (or a
            // stale GPU preference) skips GPU attempts entirely — the
            // one build adapts; nothing is hard-coded per vendor.
            // The persisted gate now lives INSIDE backendPlanFor so the
            // resetSession path follows the same verdict as the cold path.
            val tier = deviceProfile.tier
            var plan = backendPlanFor(brain, tier)
            CrashLogFile.log(TAG, "Backend plan [${brain.displayName}, $tier]: ${plan.joinToString(" → ") { it.backendName }}")

            // ── CPU-FIRST / BACKGROUND GPU SWAP ───────────────────────
            // When the device memory remembers GPU (calibrated, fresh, no
            // backoff), the blocking GPU attempt (≈90 s OpenCL compile on
            // MT6899) is DEFERRED: CPU builds first (~10 s) and serves
            // queries immediately; a background job builds the GPU engine
            // with the same crash-guards, warms it, records the adaptive
            // verdict, and the engines swap at the first quiet instant.
            // UNCALIBRATED devices keep the synchronous GPU→CPU calibration
            // trial — verdicts must be learned before they can be deferred.
            val deferredGpu = plan.firstOrNull { it.backendName.equals("GPU", true) } != null &&
                rememberedBackendPrefersGpu()
            if (deferredGpu) {
                CrashLogFile.log(
                    TAG,
                    "Adaptive CPU-first: remembered GPU verdict — CPU starts NOW, " +
                        "GPU engine builds in the background and swaps in"
                )
            }

            for ((index, step) in plan.withIndex()) {
                if (deferredGpu && step.backendName.equals("GPU", true)) {
                    // Deferred, not skipped: the verdict + swap happen in
                    // [launchBackgroundGpuBuild] once CPU serves.
                    continue
                }
                onStepProgress?.invoke(30 + index * 10, progressMessageFor(step))
                val ok = createEngineGuarded(brain, modelFile, step, tier)
                if (ok) {
                    // Warm-up — pre-compile GPU kernels / warm the CPU decoder
                    onStepProgress?.invoke(75, "Sharpening my focus (this takes a moment)...")
                    CrashLogFile.log(TAG, "Step 3: Engine warm-up (dummy inference) [${step.backendName}]...")
                    val warmupLatencyMs = warmUp()

                    // ── STARTUP CALIBRATION VERDICT ───────────────────
                    // Checklist item 3: latency of the fixed warm-up pass +
                    // RAM delta decides whether THIS backend is persisted as
                    // this device's best config.
                    // OWNER POLICY (2026-10-17, supersedes the v1
                    // "unhealthy-but-initialized still serves this session"):
                    // an UNHEALTHY GPU backend does NOT serve the session — a
                    // blind user must not spend 16 s per answer (measured
                    // 16375ms GPU vs 2073ms CPU on this X6891) because the
                    // engine technically built. Unhealthy GPU = failed step:
                    // record the device memory, tear down the sick engine,
                    // and let CPU serve (also freeing the engine's VRAM).
                    var acceptStep = true
                    try {
                        val ramMB = runCatching { availableHeapMB() }.getOrDefault(0L)
                        val healthy = VyzeAdaptiveBackend.warmupHealthy(warmupLatencyMs)
                        when {
                            step.backendName.equals("GPU", true) && healthy -> {
                                // GPU passed: persist it as this device's best config.
                                VyzeAdaptiveBackend.saveBestBackend(context, "GPU")
                                // Clear the failure memory — the device just
                                // re-validated GPU (device may have updated its
                                // driver after the backoff window allowed a retry).
                                VyzeAdaptiveBackend.clearGpuFailures(context)
                                VyzeAdaptiveBackend.recordGpuWarmup(
                                    context,
                                    VyzeAdaptiveBackend.GPU_WARMUP_ROUNDS_BEFORE_JUDGMENT,
                                )
                            }
                            step.backendName.equals("GPU", true) -> {
                                // Built but unhealthy: serves NO session and
                                // never becomes the persisted preference.
                                // Track this as a device-level GPU memory.
                                acceptStep = false
                                try {
                                    VyzeAdaptiveBackend.recordGpuFailure(context)
                                } catch (_: Throwable) { /* advisory */ }
                                CrashLogFile.log(
                                    TAG,
                                    "Backend GPU: warm-up unhealthy (${warmupLatencyMs}ms) — NOT persisted; falling through to next backend",
                                )
                            }
                            else -> {
                                // CPU won (or was the only attempt). CPU is the
                                // persisted DEFAULT — nothing to overwrite; a
                                // persisted GPU preference would have affected
                                // the next init's re-validation, not this CPU win.
                            }
                        }
                        CrashLogFile.log(TAG, "Calibration verdict: backend=${step.backendName}, warmup=${warmupLatencyMs}ms, healthy=$healthy, freeRam=${ramMB}MB")
                        com.vyze.app.performance.VyzeLocalTelemetry.record(
                            context = context,
                            taskType = com.vyze.app.performance.VyzePerformanceConfig.TelemetryTaskType.BACKEND_SELECTION,
                            backend = step.backendName.lowercase(),
                            latencyMs = warmupLatencyMs,
                            success = healthy,
                        )
                    } catch (_: Throwable) { /* calibration persistence is advisory */ }

                    if (!acceptStep) {
                        // Tear down the sick engine BEFORE the next backend
                        // attempts — GPU allocates significant system RAM for
                        // its KV cache (measured freeRam 1511MB while built).
                        try {
                            engine?.close()
                        } catch (e: Throwable) {
                            Log.w(TAG, "unhealthy-step close error: ${e.message}")
                        }
                        engine = null
                        isInitialized = false
                        continue
                    }

                    onStepProgress?.invoke(95, "Finalizing...")
                    CrashLogFile.log(TAG, "=== INIT SUCCESS [${step.backendName}, $tier] ===")
                    Log.i(TAG, "${brain.displayName} loaded successfully [backend=${step.backendName}, tier=$tier]")
                    if (deferredGpu) {
                        // CPU now serves. Start the background GPU build +
                        // quiet-point swap (owner option 1).
                        launchBackgroundGpuBuild(brain, modelFile, tier)
                    }
                    return@withContext true
                }
                if (step.backendName.equals("GPU", true)) {
                    // SYMMETRIC MEMORY (owner policy): a capable device keeps
                    // its GPU verdict; an incapable one must keep its CPU
                    // verdict too — otherwise every cold launch re-burns the
                    // full GPU attempt budget (measured 92 s timeout on this
                    // X6891). Record the failure; the plan gate skips GPU
                    // for the backoff window (24 h).
                    try {
                        VyzeAdaptiveBackend.recordGpuFailure(context)
                        CrashLogFile.log(
                            TAG,
                            "Adaptive policy: GPU failure recorded — GPU attempts skipped for " +
                                "${VyzeAdaptiveBackend.GPU_FAIL_BACKOFF_MS / (60 * 60 * 1000)}h"
                        )
                    } catch (_: Throwable) { /* failure memory is advisory */ }
                }
                CrashLogFile.log(TAG, "Backend ${step.backendName} failed — moving to next in plan")
            }

            // All backends in the tier's plan failed — count the failure.
            // FIRST failure already speaks the FRIENDLY message (whitepaper
            // §: "the old path spoke raw 'Insufficient RAM: …' strings to a
            // blind user" — same discipline applies to every init failure:
            // exception text is a logs-only detail, never a screen/TTS
            // payload). The circuit breaker (initFailureCount) then stops
            // retry churn at MAX_INIT_FAILURES.
            initFailureCount++
            isInitialized = false
            val degrade = initFailureCount >= MAX_INIT_FAILURES
            gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            Log.e(TAG, "VLM init failed: all backends for $tier exhausted." +
                if (degrade) " (circuit breaker OPEN)" else " (attempt $initFailureCount/$MAX_INIT_FAILURES)")
            CrashLogFile.logError(TAG, "VLM init failed: all backends for $tier exhausted (attempt $initFailureCount/$MAX_INIT_FAILURES)")
            onError?.invoke(VLM_UNAVAILABLE_MESSAGE, "")
            false

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Disambiguate TRUE caller cancellation (app teardown / session
            // switch) from an ENGINE failure disguised as a cancellation.
            //
            // Why: litertlm-android:0.16.1 runs parts of engine init through
            // its own internal coroutines (see the SendChannel.close note in
            // build.gradle.kts). On some mid-range MediaTek chipsets
            // (Helio G88 / Mali-G57 class, e.g. Infinix Hot 40i) a silent
            // GPU-init failure surfaces as
            // CancellationException("StandaloneCoroutine was cancelled")
            // instead of a proper JNI/OOM error. Treating every
            // CancellationException as caller-cancellation let that failure
            // escape the guarded backend chain entirely: no CPU fallback, no
            // circuit breaker, no friendly message — a dead loading screen
            // showing raw exception text.
            //
            // Discriminator: the COROUTINE'S own context. If this job is
            // still active, nobody cancelled us from outside — the
            // cancellation came from the engine side and IS an engine
            // failure. Route it into the normal failure path (circuit-breaker
            // count + graceful degradation message) instead of rethrowing.
            if (!currentCoroutineContext().isActive) {
                CrashLogFile.log(TAG, "Init cancelled by caller — not an engine failure")
                throw e
            }
            CrashLogFile.logError(
                TAG,
                "Init failed with a DISGUISED cancellation (caller still active): " +
                    "${e.javaClass.simpleName}: ${e.message} — treating as engine failure"
            )
            initFailureCount++
            isInitialized = false
            val degrade = initFailureCount >= MAX_INIT_FAILURES
            gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            Log.e(TAG, "VLM init failed: ${e.javaClass.simpleName}: ${e.message}" +
                if (degrade) " (circuit breaker OPEN)" else " (attempt $initFailureCount/$MAX_INIT_FAILURES)")
            CrashLogFile.logError(TAG, "VLM init failed: ${e.javaClass.simpleName}: ${e.message} (attempt $initFailureCount/$MAX_INIT_FAILURES)", e)
            onError?.invoke(VLM_UNAVAILABLE_MESSAGE, "")
            false
        } catch (e: Throwable) {
            // Catches Errors too (OutOfMemoryError, UnsatisfiedLinkError from
            // native init) — a VLM init failure must never cancel the parent
            // CoroutineScope or break non-VLM features.
            initFailureCount++
            isInitialized = false
            val degrade = initFailureCount >= MAX_INIT_FAILURES
            gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            Log.e(TAG, "VLM init failed: ${e.javaClass.simpleName}: ${e.message}" +
                if (degrade) " (circuit breaker OPEN)" else " (attempt $initFailureCount/$MAX_INIT_FAILURES)")
            CrashLogFile.logError(TAG, "VLM init failed: ${e.javaClass.simpleName}: ${e.message} (attempt $initFailureCount/$MAX_INIT_FAILURES)", e)
            onError?.invoke(VLM_UNAVAILABLE_MESSAGE, "")
            false
        }
    }

    // ── Tiered Backend Planning ───────────────────────────────────

    /** One backend attempt in a tier's initialization plan. */
    private data class TierBackendPlan(
        val backendName: String,
        val backend: Backend,
        val contextTokens: Int?
    )

    /**
     * The backend chain for a brain+tier:
     *  - Gemma Tier 1: GPU only (flagship — certified drivers; a failure here
     *    is treated as a real fault, not a tiering issue).
     *  - Gemma Tier 2: GPU with catch-fallback to CPU (4 threads) — the
     *    mid-range Mali/OpenCL driver belt.
     *  - Gemma Tier 3: CPU directly — never waste 5-10s on a GPU init that
     *    will fail on low-end chipsets.
     *  - MiniCPM (ALL tiers): CPU DIRECTLY, single step — HF's own device
     *    testing showed the GPU decoder engine of this bundle fails to BUILD
     *    on 8-GB-class Mali phones (Pixel 8a, memory safety line during
     *    signature init). A tier-1 GPU attempt would either waste up to a
     *    90 s failed-init budget on Tensor/Mali flagships or risk the same
     *    "m0 was canceled" class of silent hangs. CPU-only v1; revisit
     *    per-tier GPU for Adreno only after corridor data (swap plan §6).
     */
    private fun backendPlanFor(brain: BrainModel, tier: DeviceTier): List<TierBackendPlan> {
        if (tier == DeviceTier.TIER_DISABLED) return emptyList()
        if (brain.cpuOnly) {
            return listOf(
                TierBackendPlan("CPU", cpuBackendForDevice(), contextTokensFor(brain, tier))
            )
        }
        // ── ADAPTIVE PLAN GATE ──────────────────────────────────
        // The plan is device-adaptive, per the owner's policy: GPU when the
        // phone proves capable, CPU when it doesn't, and the verdict is
        // persisted either way (see VyzeAdaptiveBackend: stale after 24 h,
        // overtemp fallback). GPU steps are dropped when:
        //   a) hardware is a known-bad GPU driver class (Mali hangs → never
        //      waste the warm-up rounds there), or
        //   b) the persisted calibration for THIS device said CPU-only.
        // Previously the persisted filter ran only on the cold-init path (a
        // resetSession reinit could resurrect a GPU attempt the calibration
        // had already rejected).
        // Device-adaptive verdict (first-run caveat fixed): a brand-new
        // device has NO persisted calibration record, and loadPersistentState
        // defaults preferredBackend="CPU" when the prefs file is absent. The
        // original isCpuOnlyForDevice() therefore treated "never calibrated"
        // as "proven incapable" — this plan gate dropped GPU for every
        // Tier-1 device on its first launch and the trial never ran. The
        // verdict is only respected once a calibration pass actually
        // persisted it (lastValidatedMs > 0). "Nothing learned" = try GPU.
        // HW a) stands for the known-bad driver list.
        // Gate conditions live in the SHARED [adaptiveGpuGate] so the plan
        // builder and the CPU-first/background-swap probe cannot diverge.
        val (gpuDisallowed, gpuBlockWhy) = adaptiveGpuGate()
        if (gpuDisallowed) {
            CrashLogFile.log(TAG, "Adaptive plan: GPU skipped — $gpuBlockWhy")
        }
        val gpuFirstPlan = when (tier) {
            DeviceTier.TIER_1 -> listOf(
                TierBackendPlan("GPU", Backend.GPU(), contextTokensFor(brain, tier)),
                TierBackendPlan("CPU", cpuBackendForDevice(), contextTokensFor(brain, tier))
            )
            DeviceTier.TIER_2 -> listOf(
                TierBackendPlan("GPU", Backend.GPU(), contextTokensFor(brain, tier)),
                TierBackendPlan("CPU", cpuBackendForDevice(), contextTokensFor(brain, tier))
            )
            // Tier 3: below the adaptive line — CPU only, as before.
            else -> listOf(
                TierBackendPlan("CPU", cpuBackendForDevice(), contextTokensFor(brain, tier))
            )
        }
        return if (gpuDisallowed) gpuFirstPlan.filter { it.backendName != "GPU" }
               else gpuFirstPlan
    }

    /**
     * SHARED adaptive GPU gate, consumed by BOTH call sites that must never
     * diverge in their memory semantics:
     *   1. [backendPlanFor] — which backend steps the plan carries.
     *   2. [rememberedBackendPrefersGpu] — whether the CPU-first path is
     *      allowed to defer the blocking GPU attempt into a background build.
     * Returns (disallowed?, reason-why-disallowed) so a single policy edit
     * affects the plan, the deferral and its log line together.
     *
     * "Nothing learned yet" (never calibrated) deliberately reads as
     * ALLOWED — see the plan-gate comment above.
     */
    private fun adaptiveGpuGate(): Pair<Boolean, String> = try {
        val state = VyzeAdaptiveBackend.loadPersistentState(context)
        val calibrated = state.lastValidatedMs > 0L
        when {
            VyzeAdaptiveBackend.deviceLooksCpuOnly(deviceProfile.hardware) ->
                true to "known-bad GPU driver (hardware heuristic)"
            state.overtempCount >= VyzeAdaptiveBackend.OVERTEMP_FALLBACK_THRESHOLD ->
                true to "overtemp CPU fallback active"
            !calibrated -> false to ""
            state.preferredBackend.uppercase() == "CPU" ->
                true to "calibrated device memory (CPU verdict)"
            VyzeAdaptiveBackend.gpuBackoffActive(context) ->
                true to "calibrated device memory (24h GPU-failure backoff)"
            else -> false to ""
        }
    } catch (_: Throwable) {
        // Policy reads are advisory and the GPU attempt itself is
        // crash-guarded — on any read failure, let the trial happen.
        false to ""
    }

    /**
     * True when THIS device's persisted calibration currently says GPU
     * (validated verdict, no failure backoff, no overtemp fallback, not a
     * known-bad driver). The CPU-first/background-swap path defers ONLY the
     * blocking GPU attempt on a remembered-GPU device — an uncalibrated or
     * CPU-verdict device keeps the synchronous behavior.
     */
    private fun rememberedBackendPrefersGpu(): Boolean = try {
        val state = VyzeAdaptiveBackend.loadPersistentState(context)
        val (disallowed, _) = adaptiveGpuGate()
        state.lastValidatedMs > 0L &&
            state.preferredBackend.uppercase() == "GPU" &&
            !disallowed
    } catch (_: Throwable) {
        false
    }

    /**
     * Deletes the unrestorable MASTER program-cache files before any build.
     *
     * Why (upstream litert-lm 0.18.0 / ml-drift bug, reproduced 3× on this
     * TIER_1 MT6899): the OpenCL serialize→deserialize round-trip fails on
     * EVERY cold start for the heavy subgraphs — logcat,
     *   delegate_kernel.cc:875 "Deserialization failed: DATA_LOSS"
     * — while each attempt still APPENDS ~246 MB of unusable copies
     * (one cache file grew 993→1,239→1,485 MB in three launches). The only
     * entries that restore are two ~4.7 MB ones; the master cache is pure
     * write-only churn. Deleting it bounds storage to roughly one build
     * generation without losing the residency behavior of a runtime that
     * never restores anyway.
     *
     * KEPT (they restore cleanly and never grew): *.vision_encoder_* and
     * *.mtp_drafter_* program caches + all xnnpack weight caches.
     */
    private fun cleanupUnrestorableProgramCaches() {
        try {
            val dir = context.cacheDir ?: return
            var freedBytes = 0L
            dir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.endsWith("_mldrift_program_cache.bin") &&
                    !name.contains(".vision_encoder_") &&
                    !name.contains(".mtp_drafter_")
                ) {
                    freedBytes += f.length()
                    if (!f.delete()) Log.w(TAG, "program-cache cleanup: could not delete $name")
                }
            }
            if (freedBytes > 0) {
                CrashLogFile.log(
                    TAG,
                    "GPU program-cache cleanup: freed ${freedBytes / (1024 * 1024)}MB " +
                        "(unrestorable master cache — upstream DATA_LOSS bug)"
                )
            }
        } catch (e: Throwable) {
            // Advisory — a failed cleanup never blocks initialization.
            Log.w(TAG, "program-cache cleanup skipped: ${e.message}")
        }
    }

    /** Tier context window clamped by the brain's bundle KV budget. */
    private fun contextTokensFor(brain: BrainModel, tier: DeviceTier): Int {
        val tierTokens = when (tier) {
            DeviceTier.TIER_1 -> CONTEXT_TOKENS_TIER1
            DeviceTier.TIER_2 -> CONTEXT_TOKENS_TIER2
            else -> CONTEXT_TOKENS_TIER3
        }
        return minOf(tierTokens, brain.contextTokenCap)
    }

    /** CPU backend with the tier's thread budget (4 threads, capped by cores). */
    private fun cpuBackendForDevice(): Backend {
        val threads = cpuThreadCount()
        CrashLogFile.log(TAG, "CPU backend: $threads threads")
        return Backend.CPU(threadCount = threads)
    }

    private fun cpuThreadCount(): Int {
        val cores = try {
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        } catch (_: Throwable) {
            4
        }
        // DEBUG-ONLY override (set via CpuThreadsToggleReceiver, debug builds
        // only): lets a device session A/B unlock the thread-scaling answer
        // for BOTH prefill and decode without an APK rebuild — spending a
        // cold restart per value. Release builds never set it.
        cpuThreadCountOverride?.let { requested ->
            val clamped = requested.coerceIn(1, cores)
            CrashLogFile.log(
                TAG,
                "CPU backend thread override: requested=$requested clamped=$clamped (cores=$cores, default would be " +
                    "${minOf(CPU_FALLBACK_THREADS, maxOf(2, cores / 2))})"
            )
            return clamped
        }
        return minOf(CPU_FALLBACK_THREADS, maxOf(2, cores / 2))
    }

    private fun progressMessageFor(step: TierBackendPlan): String = when (step.backendName) {
        "GPU" -> "Opening my eyes..."
        else -> "Warming up my thinking engine..."
    }

    /**
     * Tier-scoped EXPERIMENTAL flags (LiteRT-LM 0.16.1 static registry).
     *
     *  - MTP-class acceleration (speculative decoding / multi-token
     *    prediction): Tier 1 ONLY — flagship GPUs have the compute headroom
     *    for the extra parallel token prediction. Explicitly reset to the
     *    default (off) on every other tier, and on every CPU fallback.
     *  - Visual token budget: capped on Tier 2/3 so a single camera frame
     *    cannot blow the reduced context window.
     *
     * The flags are STATIC — all of them are assigned on EVERY attempt so a
     * previous attempt's flags can never leak into the next one.
     */
    @OptIn(ExperimentalApi::class)
    private fun applyTierExperimentalFlags(brain: BrainModel, tier: DeviceTier) {
        try {
            if (brain == BrainModel.MINICPM_V47_1B) {
                // MiniCPM bundle (fast_vlm + Qwen3.5 hybrid decoder): vision
                // tokens are FIXED at 64 by the bundle contract — a
                // visualTokenBudget flag is meaningless at best and
                // out-of-contract at worst; MTP/speculative decoding was not
                // built into this conversion. Engine defaults, always.
                // NOTE: keyed on brain IDENTITY, not brain.cpuOnly — that
                // flag now controls the ADAPTIVE backend chain only, while
                // the bundle's generation contract is per-conversion and
                // cannot change behavior from a backend swap.
                ExperimentalFlags.enableSpeculativeDecoding = null
                ExperimentalFlags.visualTokenBudget = null
                CrashLogFile.log(
                    TAG,
                    "Experimental flags [brain=${brain.displayName}]: fixed-bundle defaults (no MTP, fixed 64-token vision)"
                )
                return
            }
            ExperimentalFlags.enableSpeculativeDecoding = when (tier) {
                DeviceTier.TIER_1 -> true
                else -> null  // MTP off — reset to engine default
            }
            ExperimentalFlags.visualTokenBudget = when (tier) {
                DeviceTier.TIER_2 -> VISUAL_TOKEN_BUDGET_TIER2
                DeviceTier.TIER_3 -> VISUAL_TOKEN_BUDGET_TIER3
                else -> null   // Tier 1: engine default (full budget)
            }
            CrashLogFile.log(
                TAG,
                "Experimental flags [brain=${brain.displayName}, tier=$tier]: mtp=${tier == DeviceTier.TIER_1}, " +
                    "visualBudget=${ExperimentalFlags.visualTokenBudget ?: "default"}"
            )
        } catch (e: Throwable) {
            // Older runtime without these flags — non-fatal.
            Log.w(TAG, "ExperimentalFlags unavailable: ${e.message}")
        }
    }

    /**
     * Create + initialize the engine for ONE backend attempt, fully guarded:
     *
     *  - serialized by [engineInitMutex] (one native build at a time),
     *  - GC + trim BEFORE the native side allocates KV-cache tensors,
     *  - hard time budget per attempt (a hung native constructor can no
     *    longer stall initialization forever),
     *  - ALL Throwables (JNI/C++ driver errors, OutOfMemoryError,
     *    UnsatisfiedLinkError, cancelled GPU init) are caught and classified
     *    — nothing propagates as an uncaught Job cancellation,
     *  - external caller cancellation is rethrown untouched.
     *
     * @return true when the engine is ready on this backend.
     */
    private suspend fun createEngineGuarded(
        brain: BrainModel,
        modelFile: File,
        step: TierBackendPlan,
        tier: DeviceTier
    ): Boolean {
        // WIRING WRAPPER: the guarded build lives in [buildEngineForBackend]
        // (mutex / timers / crash-guards identical, so one code path owns the
        // build discipline). The CPU-first path also uses that builder —
        // WITHOUT the wiring — to hold a background GPU engine.
        val eng = buildEngineForBackend(brain, modelFile, step, tier)
        return if (eng != null) {
            onStepProgress?.invoke(45, "Getting my vision ready...")
            CrashLogFile.log(TAG, "Engine created + initialized [${step.backendName}]")
            engine = eng
            activeBackend = step.backendName
            isInitialized = true
            true
        } else {
            false
        }
    }

    /**
     * Create + initialize an engine for ONE backend attempt WITHOUT wiring it
     * as the active serving engine. Every guard of the blocking path applies:
     *
     *  - serialized by [engineInitMutex] (one native build at a time),
     *  - GC + trim BEFORE the native side allocates KV-cache tensors,
     *  - hard time budget per attempt (a hung native constructor can no
     *    longer stall initialization forever),
     *  - ALL Throwables (JNI/C++ driver errors, OOM, GPU-init
     *    cancellations) are caught and classified — nothing propagates as
     *    an uncaught Job cancellation,
     *  - external caller cancellation is rethrown untouched.
     *
     * @return the ready engine, or null on failure (build error, timeout).
     * @see createEngineGuarded for the wiring behavior.
     * @see launchBackgroundGpuBuild for the CPU-first consumer.
     */
    private suspend fun buildEngineForBackend(
        brain: BrainModel,
        modelFile: File,
        step: TierBackendPlan,
        tier: DeviceTier,
        timeoutOverrideMs: Long? = null
    ): Engine? {
        return engineInitMutex.withLock {
            CrashLogFile.log(
                TAG,
                "Trying ${step.backendName} backend [${brain.displayName}] (tier=$tier, contextTokens=${step.contextTokens})..."
            )
            onStepProgress?.invoke(35, "Preparing my vision...")
            lastEngineBuildTimedOut = false

            // Drop whatever we can BEFORE the native side allocates KV-cache
            // tensors — on lower-tier hardware every reclaimed MB counts.
            reclaimMemoryBeforeTensorAllocation(aggressive = tier != DeviceTier.TIER_1)

            val timeoutMs = timeoutOverrideMs
                ?: if (step.backend is Backend.CPU) CPU_ENGINE_CREATE_TIMEOUT_MS
                else ENGINE_CREATE_TIMEOUT_MS

            try {
                applyTierExperimentalFlags(brain, tier)

                val eng = buildEngineTimed(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = step.backend,
                        // VISION SEPARATION (2026-10-09, Infinix Hot 60i / Mali):
                        // on Mali-G57 class GPUs the vision-encoder path HANGS
                        // indefinitely at the ORIGINAL heavy settings (384px
                        // input, 320-token budget) — first image inference
                        // produced no token in 90 s. GPU-vision RETRY EXPERIMENT
                        // (2026-10-10): the same GPU never got a chance at the
                        // NEW light settings (controller feeds Tier 2 at 192px,
                        // visualBudget 256) — ~75% fewer visual tokens than the
                        // hang conditions. If GPU vision still hangs at this
                        // weight the image watchdog kills the query and the CPU
                        // vision path below (existing fallback chain) serves the
                        // answer instead; if it responds, CPU prefill latency
                        // (~68 s) drops to GPU speed. Revert this line to
                        // `Backend.CPU()` unconditionally if the experiment
                        // shows a hang-then-timeout pattern.
                        // MIXED-BACKEND EXECUTOR (2026-10-18, litert-community
                        // MiniCPM-V-4.7-1B model card): the card's own per-
                        // backends table shows the image path is FASTER with
                        // vision kept on CPU while the decoder rides the GPU
                        // (iPhone 18 Pro Metal: vision-Metal TTFT 4.641 s vs
                        // Metal decoder + CPU vision TTFT 2.481 s). On our
                        // Mali the all-GPU config measures 7.36-7.39 s image
                        // prefill; the bundle's CPU vision path is the
                        // canonical fast one (Pixel 8a _int8vis: 2.2-3.0 s
                        // first token). RESOLVED 2026-10-18 A/B: mixed
                        // (vision CPU) prefill 3.7-4.3 s vs all-GPU 7.36-7.39 s
                        // on the GT 50 Pro — the pin WAS the measured winner.
                        // VISION-BACKEND POLICY (2026-10-19, tier + GPU-family
                        // adaptive): the single CPU pin applied to every phone;
                        // [companionMiniCpmVisionBackendName] now keys the
                        // choice on the device's own profile — Tier 1 + Adreno
                        // (the model card's measured-fastest family) attempts
                        // GPU vision, every Mali / lower tier keeps the
                        // measured CPU path. DEBUG corridor override via
                        // [debugGpuVisionOverride] (VisionGpuToggleReceiver).
                        visionBackend = run {
                            val (name, why) = companionMiniCpmVisionBackendName(
                                isMiniCpm = brain == BrainModel.PROD_DEFAULT,
                                stepIsGpu = step.backend !is Backend.CPU,
                                tier = tier,
                                hardware = try { Build.HARDWARE ?: "" } catch (_: Throwable) { "" },
                                debugOverride = debugGpuVisionOverride
                            )
                            CrashLogFile.log(TAG, "Vision backend resolved: $name — $why")
                            if (name == "GPU") Backend.GPU() else Backend.CPU()
                        },
                        // EXECUTOR SEPARATION (M0.2, 2026-10-16): only attach
                        // executors the ACTIVE bundle actually contains. The
                        // MiniCPM int8vis bundle has NO audio tower — hardcoding
                        // audioBackend made the runtime look up
                        // tf_lite_audio_encoder_hw on EVERY Conversation creation
                        // and every inference died with NOT_FOUND (0.16.1 AND
                        // 0.18.0; version was never the bug). Per litert-lm
                        // Config.kt: "If null, audio executor will not be
                        // initialized" — so vision-only brains skip audio.
                        audioBackend = if (brain.supportsAudioInput) Backend.CPU() else null,
                        maxNumTokens = step.contextTokens,
                        cacheDir = context.cacheDir.path
                    ),
                    timeoutMs
                )

                if (eng == null) {
                    lastEngineBuildTimedOut = true
                    CrashLogFile.logError(
                        TAG,
                        "${step.backendName} build exceeded ${timeoutMs / 1000}s budget — treating as failure"
                    )
                    return@withLock null
                }
                lastEngineBuildTimedOut = false

                // Success — the CALLER decides whether this engine becomes
                // the active serving engine ([createEngineGuarded]) or stays
                // parked for a background swap ([launchBackgroundGpuBuild]).
                eng

            } catch (e: kotlinx.coroutines.CancellationException) {
                // External cancellation (caller scope) — rethrow untouched;
                // never swallow and never count as an engine failure.
                CrashLogFile.log(TAG, "${step.backendName} build cancelled by caller")
                throw e
            } catch (e: Throwable) {
                // JNI / C++ driver errors, OutOfMemoryError, UnsatisfiedLinkError,
                // "m0 was canceled"-style silent GPU failures — contained here.
                CrashLogFile.logError(
                    TAG,
                    "${step.backendName} init failed: ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                Log.e(TAG, "${step.backendName} init failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Build + initialize an [Engine] under a hard time budget.
     *
     * The native constructor and initialize() cannot be interrupted, so the
     * build runs on the dedicated single-thread init lane while the caller
     * polls. On completion within budget the engine is returned; on timeout
     * the result holder is parked in [pendingBuild] so the NEXT attempt can
     * drain-wait for the (uninterruptible) build and close it properly
     * instead of leaking it or racing a second native init against it.
     */
    private suspend fun buildEngineTimed(engineConfig: EngineConfig, timeoutMs: Long): Engine? {
        // Drain + close any previous timed-out build still materializing.
        awaitAndClosePendingBuild(PENDING_BUILD_DRAIN_MS)

        val result = EngineBuildResult()
        val deadline = System.currentTimeMillis() + timeoutMs

        scope.launch(engineInitDispatcher) {
            try {
                val e = Engine(engineConfig)
                e.initialize()
                result.engine = e
            } catch (t: Throwable) {
                result.error = t
            }
        }

        while (result.engine == null && result.error == null &&
            System.currentTimeMillis() < deadline
        ) {
            delay(100)
        }

        result.engine?.let {
            pendingBuild = null
            return it
        }
        result.error?.let { throw it }

        // Timed out — the native build may STILL complete later (it cannot be
        // interrupted). Park the holder for the next attempt to drain + close.
        pendingBuild = result
        CrashLogFile.log(TAG, "Engine build timed out after ${timeoutMs / 1000}s — parked for drain")
        return null
    }

    // ── CPU-FIRST / BACKGROUND GPU SWAP (owner option 1, 2026-10-18) ─────
    //
    // OWNER PROBLEM: a remembered-GPU device paid the full ~87 s OpenCL GPU
    // build on EVERY cold launch before the UI could take a query — and the
    // compiled-program cache could not help (restore fails every cold start
    // for the heavy subgraphs: delegate_kernel.cc:875 DATA_LOSS — upstream
    // litert-lm 0.18.0 / ml-drift bug) while still appending ~246 MB per
    // attempt. "Remember" existed in the adaptive memory but not in the
    // latency.
    //
    // DESIGN: on a remembered-GPU device the CPU engine builds first (~10 s)
    // and serves queries immediately; the GPU engine builds in the BACKGROUND
    // with the identical crash-guards/budgets, gets its text warm-up BEFORE
    // the wiring, records the adaptive verdict exactly like the blocking
    // path did, and the engines swap at the first quiet instant (no
    // generation in flight). A build failure/timeout or unhealthy warm-up
    // records the same 24 h GPU-failure memory the blocking path used —
    // verdicts stay symmetric in both directions.

    /** Engine built in the background, awaiting the quiet-point swap. */
    @Volatile
    private var pendingGpuEngine: Engine? = null

    /** Serializes background GPU builds — one native GPU build at a time. */
    private val backgroundGpuBuildActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Set by [buildEngineForBackend] when the JUST-FINISHED attempt ended by
     * EXCEEDING the build budget (true) vs. by exception/init-throw (false).
     * Serialized under [engineInitMutex] (both paths hold it), so the
     * background-build caller can read it right after the attempt returns.
     * Timeout is a down-clock/suspect-slow signal — never recorded as a GPU
     * failure (see launchBackgroundGpuBuild).
     */
    @Volatile
    private var lastEngineBuildTimedOut = false

    /** Idle-swap polling cadence and overall patience. */
    private val swapPollIntervalMs = 2_000L
    private val swapMaxWaitMs = 180_000L

    /**
     * Build budget for the BACKGROUND GPU engine. The 90 s blocking budget
     * exists to protect the INIT UX; nothing waits on the background build,
     * and Android can cache-freeze the process mid-build (observed: a build
     * declared "failed" 7 min after start because freeze time consumed the
     * deadline) — a background build gets a generous budget instead.
     */
    // 600 s (raised from 300 s, 2026-10-19): with the CPU wake lock held the
    // compile KEEPS RUNNING after screen-off — and the vendor firmware
    // down-clocks cores when the display is asleep, so the same OpenCL compile
    // that takes ~1-2 min with the screen on took >5 min under the lock. A
    // 300 s budget then turned a healthy-but-slow build into a 24 h GPU ban.
    // 10 min is the largest plausible budget; the wake lock ceiling below
    // must stay comfortably above it.
    private val backgroundEngineCreateTimeoutMs = 600_000L

    /**
     * Highest ceiling the CPU wake lock below is ever held for rearming the
     * GPU build. The lock protects the ~1-4 min OpenCL compile + warm-up
     * against suspend/hibernation; the value is a bound, not a target — the
     * lock is released the moment the build ends either way.
     */
    private val backgroundGpuBuildWakeLockMs = 660_000L

    /**
     * Build the GPU engine IN THE BACKGROUND for a remembered-GPU device
     * whose CPU engine is already serving. Verdict discipline mirrors the
     * blocking path: build-fail or unhealthy-warmup → [recordGpuFailure]
     * (24 h backoff, plan drops GPU); healthy → [saveBestBackend("GPU")]
     * + failure memory cleared. On health, the engine is parked and the
     * swap is scheduled — never wired while a generation is running.
     */
    private fun launchBackgroundGpuBuild(brain: BrainModel, modelFile: File, tier: DeviceTier) {
        if (!backgroundGpuBuildActive.compareAndSet(false, true)) {
            CrashLogFile.log(TAG, "Background GPU build already running — not spawning another")
            return
        }
        // CPU WAKE LOCK (2026-10-19): the vendor hibernation froze the process
        // when the screen went off — three sessions in a row the build stalled
        // mid-OpenCL-compile and the process then died (lmkd, 2.5 GB RSS) or
        // sat frozen until the next session, so the GPU swap NEVER landed and
        // the device was stuck serving every query on the CPU engine (the
        // 12-13 s prefill era, both text and image lanes). A partial wake lock
        // lets the compile keep the CPU across screen-off for the build
        // window; released in the finally below on every exit path.
        val wakeLock = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vyze:engineGpuBuild")
        }.getOrNull()
        if (wakeLock != null) {
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(backgroundGpuBuildWakeLockMs)
            CrashLogFile.log(
                TAG,
                "Background GPU build: CPU wake lock held (ceiling ${backgroundGpuBuildWakeLockMs / 1000}s — released on build end)"
            )
        }
        scope.launch {
            try {
                if (sessionResetInProgress) {
                    // A session reset owns the engine lifecycle right now;
                    // its own loops spawn their own background build.
                    return@launch
                }
                val step = TierBackendPlan("GPU", Backend.GPU(), contextTokensFor(brain, tier))
                CrashLogFile.log(TAG, "Background GPU build: starting (queries keep serving on CPU meanwhile)...")

                val gpuEngine = buildEngineForBackend(
                    brain,
                    modelFile,
                    step,
                    tier,
                    timeoutOverrideMs = backgroundEngineCreateTimeoutMs
                )
                if (gpuEngine == null) {
                    // Timeout ≠ fault (2026-10-19): a build that merely ran
                    // past the budget (slow under down-clocked cores with the
                    // wake lock holding it) is NOT a broken GPU — recording it
                    // as one banned GPU attempts for the full backoff window
                    // and pinned the device onto the slow CPU era. A timed-out
                    // background build defers instead: no ban, next launch
                    // re-attempts. Real exceptions (init throw above, recorded
                    // per step in the blocking path) still earn the ban.
                    if (lastEngineBuildTimedOut) {
                        CrashLogFile.log(
                            TAG,
                            "Adaptive policy: background GPU build timed out at budget — deferred, NO failure recorded; next launch re-attempts"
                        )
                    } else {
                        // Symmetric memory with the blocking path: a GPU build
                        // failure keeps this device remembering CPU-only for the
                        // backoff window instead of re-burning ~90 s next launch.
                        try {
                            VyzeAdaptiveBackend.recordGpuFailure(context)
                            CrashLogFile.log(
                                TAG,
                                "Adaptive policy: background GPU failure recorded — GPU attempts skipped for " +
                                    "${VyzeAdaptiveBackend.GPU_FAIL_BACKOFF_MS / (60 * 60 * 1000)}h"
                            )
                        } catch (_: Throwable) { /* failure memory is advisory */ }
                    }
                    return@launch
                }

                // Warm up the GPU engine BEFORE it becomes the serving
                // engine — the first swapped-in query must not pay warm-up.
                // TWO text-only rounds, judged on the SECOND: while the CPU
                // engine serves concurrently, the first round pays lazy
                // kernel state + resource contention the blocking path
                // never saw (measured 11.3 s contended vs 5.8-6.9 s idle —
                // over the 9 s healthy cap by the noise alone). The second
                // round is the steady state the session would ride.
                val warmupFirstMs = warmUp(gpuEngine)
                val warmupLatencyMs = warmUp(gpuEngine)
                CrashLogFile.log(
                    TAG,
                    "Background GPU warm-up: contended=${warmupFirstMs}ms steady=${warmupLatencyMs}ms"
                )
                val healthy = try {
                    VyzeAdaptiveBackend.warmupHealthy(warmupLatencyMs)
                } catch (_: Throwable) { warmupLatencyMs > 0L }
                if (!healthy) {
                    // Owner policy (same as blocking): built-but-sick GPU
                    // never serves and never becomes the preference.
                    try { VyzeAdaptiveBackend.recordGpuFailure(context) } catch (_: Throwable) { }
                    try { gpuEngine.close() } catch (e: Throwable) { Log.w(TAG, "unhealthy parked GPU close error: ${e.message}") }
                    CrashLogFile.log(
                        TAG,
                        "Adaptive policy: background GPU warm-up unhealthy (${warmupLatencyMs}ms) — engine discarded, staying on CPU"
                    )
                    return@launch
                }

                try {
                    VyzeAdaptiveBackend.saveBestBackend(context, "GPU")
                    VyzeAdaptiveBackend.clearGpuFailures(context)
                    VyzeAdaptiveBackend.recordGpuWarmup(
                        context,
                        VyzeAdaptiveBackend.GPU_WARMUP_ROUNDS_BEFORE_JUDGMENT,
                    )
                } catch (_: Throwable) { /* calibration persistence is advisory */ }
                runCatching {
                    com.vyze.app.performance.VyzeLocalTelemetry.record(
                        context = context,
                        taskType = com.vyze.app.performance.VyzePerformanceConfig.TelemetryTaskType.BACKEND_SELECTION,
                        backend = "gpu",
                        latencyMs = warmupLatencyMs,
                        success = true,
                    )
                }
                CrashLogFile.log(TAG, "Calibration verdict: backend=GPU(background), warmup=${warmupLatencyMs}ms, healthy=true")

                pendingGpuEngine = gpuEngine
                CrashLogFile.log(TAG, "Background GPU build ready (${warmupLatencyMs}ms warm-up) — swap armed")
                swapGpuEngineWhenIdle()

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Scope teardown (app close) — the timed-out native build is
                // drained by the next attempt or lost with the process.
                throw e
            } catch (e: Throwable) {
                // Advisory: the CPU engine keeps serving whatever happens here.
                CrashLogFile.log(TAG, "Background GPU build error: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Background GPU build error", e)
            } finally {
                backgroundGpuBuildActive.set(false)
                if (wakeLock != null) {
                    runCatching { if (wakeLock.isHeld) wakeLock.release() }
                        .onFailure { CrashLogFile.log(TAG, "Background GPU build: wake lock release failed: ${it.message}") }
                }
            }
        }
    }

    /**
     * Swap [pendingGpuEngine] in at the first QUIET instant. "Quiet" means
     * the generation mutex is free — every conversation lives inside that
     * mutex, so acquiring it here guarantees no generation is running or
     * starting, and the swap can never race an inference. Polls at
     * [swapPollIntervalMs] for up to [swapMaxWaitMs]; on give-up the parked
     * engine is closed (its KV-cache RAM/VRAM must not leak for the
     * process lifetime) and the GPU advantage is re-earned next launch.
     */
    private fun swapGpuEngineWhenIdle() {
        scope.launch(engineInitDispatcher) {
            val deadline = System.currentTimeMillis() + swapMaxWaitMs
            while (pendingGpuEngine != null &&
                engine != null && isInitialized &&
                System.currentTimeMillis() < deadline
            ) {
                if (generationMutex.tryLock()) {
                    try {
                        val gpuEngine = pendingGpuEngine
                        if (gpuEngine == null) return@launch  // swapped elsewhere
                        if (engine == null || !isInitialized) return@launch  // torn down mid-wait

                        // Conversations belong to their engine — the CPU one
                        // dies with its engine. Drop the reference BEFORE the
                        // close so an interrupt() can never cancelProcess() a
                        // dead engine's conversation.
                        activeConversation = null
                        val cpuEngine = engine
                        engine = gpuEngine
                        activeBackend = "GPU"
                        pendingGpuEngine = null

                        // GPU vision kernels compile lazily on the first real
                        // image — re-arm the one-time grace budget the fresh
                        // engine needs.
                        firstImageInferencePending = true

                        CrashLogFile.log(
                            TAG,
                            "=== ENGINE SWAP: serving now on GPU (CPU engine closing) ==="
                        )
                        try { cpuEngine?.close() } catch (e: Throwable) { Log.w(TAG, "CPU engine close error: ${e.message}") }
                    } finally {
                        generationMutex.unlock()
                    }
                    CrashLogFile.log(TAG, "Background GPU swap complete — queries now ride GPU speed for this session")
                    return@launch
                }
                delay(swapPollIntervalMs)
            }

            // Give-up path: engine torn down (close/reset) or no quiet instant.
            val leftover = pendingGpuEngine
            pendingGpuEngine = null
            if (leftover != null) {
                try { leftover.close() } catch (e: Throwable) { Log.w(TAG, "parked GPU engine close error: ${e.message}") }
                CrashLogFile.log(TAG, "Background GPU swap: no quiet instant in ${swapMaxWaitMs / 1000}s — parked engine discarded")
            }
        }
    }

    /**
     * Wait (bounded) for a previously timed-out engine build to materialize,
     * then close it. The native side of that build cannot be interrupted —
     * this guarantees it is drained and released before another backend
     * attempt starts, instead of two native inits fighting for RAM/GPU.
     */
    private suspend fun awaitAndClosePendingBuild(maxWaitMs: Long) {
        val pending = pendingBuild ?: return
        pendingBuild = null

        val deadline = System.currentTimeMillis() + maxWaitMs
        while (pending.engine == null && pending.error == null &&
            System.currentTimeMillis() < deadline
        ) {
            delay(100)
        }

        pending.engine?.let { zombie ->
            CrashLogFile.log(TAG, "Closing timed-out engine build (late completion)")
            try {
                zombie.close()
            } catch (_: Throwable) {}
        }
        pending.engine = null
        pending.error = null
    }

    /**
     * Reclaim memory BEFORE the native side allocates KV-cache tensors —
     * required on lower-tier hardware where allocation is the #1 init/inference
     * killer. [aggressive] (used at engine creation on Tier 2/3) also asks the
     * framework to trim its own caches.
     */
    private fun reclaimMemoryBeforeTensorAllocation(aggressive: Boolean) {
        try {
            repeat(if (aggressive) 3 else 1) { System.gc() }
            System.runFinalization()
            if (aggressive) trimAppMemoryIfPossible()
            val free = availableHeapMB()
            CrashLogFile.log(
                TAG,
                "Memory reclaimed before tensor allocation: ${free}MB free (aggressive=$aggressive)"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Memory reclaim failed: ${e.message}")
        }
    }

    /** Best-effort framework-level trim of this app's own caches. */
    private fun trimAppMemoryIfPossible() {
        try {
            (context as? ComponentCallbacks2)
                ?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        } catch (_: Throwable) {}
    }

    /** Message used by inference paths when the engine is not ready. */
    private fun notReadyMessage(): String =
        gracefulFallbackMessage
            ?: if (initFailureCount >= MAX_INIT_FAILURES) VLM_UNAVAILABLE_MESSAGE
            else "AI model not ready"

    // ── Tier-aware parameter clamps ───────────────────────────────

    /** Per-turn output cap for the device tier (never above the context budget). */
    private fun clampMaxTokens(requested: Int): Int {
        val tierCap = when (deviceProfile.tier) {
            DeviceTier.TIER_1 -> MAX_TOKENS_TIER_CAP_TIER1
            DeviceTier.TIER_2 -> MAX_TOKENS_TIER_CAP_TIER2
            else -> MAX_TOKENS_TIER_CAP_TIER3
        }
        val contextCeiling = when (deviceProfile.tier) {
            DeviceTier.TIER_1 -> CONTEXT_TOKENS_TIER1
            DeviceTier.TIER_2 -> CONTEXT_TOKENS_TIER2
            else -> CONTEXT_TOKENS_TIER3
        } - CONTEXT_HEADROOM_TOKENS
        val clamped = requested.coerceAtMost(tierCap).coerceAtMost(contextCeiling)
        if (clamped != requested) {
            Log.i(TAG, "maxTokens clamped $requested → $clamped (tier=${deviceProfile.tier})")
        }
        return clamped
    }

    /**
     * Image pre-processing dimension clamp for the ACTIVE brain.
     *
     * Gemma: tier caps (192-256 px scene feeds were the CPU-vision latency
     * lever; vision-token count scales with frame size).
     * MiniCPM: NO downsampling — instead a FLOOR at the bundle's own 448×448
     * contract. The runtime resizes whatever it receives to 448 px, so our
     * old 192/256 px frames would be lossily UPSCALED by it. Every lane
     * (scene / tap / OCR) feeds ≥448 px so the bundle sees native-scale
     * pixels; prefill cost is bounded by the fixed 64-token vision budget.
     */
    private fun clampTargetDimension(requested: Int): Int {
        val brain = brainForTurn()
        if (brain.minFeedDimension > 0) {
            return maxOf(requested, brain.minFeedDimension)
        }
        return when (deviceProfile.tier) {
            DeviceTier.TIER_1 -> requested
            DeviceTier.TIER_2 -> minOf(requested, IMAGE_DIMENSION_TIER2)
            else -> minOf(requested, IMAGE_DIMENSION_TIER3)
        }
    }

    private fun buildModelNotFoundError(brain: BrainModel): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val downloadsFile = downloadsDir?.let { File(it, brain.fileName) }
        val externalDir = context.getExternalFilesDir(null)
        val externalFile = externalDir?.let { File(it, brain.fileName) }

        val sb = StringBuilder()
        sb.appendLine("${brain.fileName} not found.")
        sb.appendLine()
        sb.appendLine("Locations checked:")

        if (downloadsFile != null) {
            sb.appendLine("  1. ${downloadsFile.absolutePath}")
            sb.appendLine("     exists=${downloadsFile.exists()}, size=${downloadsFile.length()} bytes")
        } else {
            sb.appendLine("  1. /storage/emulated/0/Download/${brain.fileName}  (Downloads dir unavailable)")
        }

        if (externalFile != null) {
            sb.appendLine("  2. ${externalFile.absolutePath}")
            sb.appendLine("     exists=${externalFile.exists()}, size=${externalFile.length()} bytes")
        } else {
            sb.appendLine("  2. <app-scoped external dir>/${brain.fileName}  (dir unavailable)")
        }

        sb.appendLine()
        sb.appendLine("Push via ADB:")
        sb.appendLine("  adb push ${brain.fileName} /storage/emulated/0/Download/")

        return sb.toString().trimEnd()
    }

    // ── Image Analysis (Main API) ─────────────────────────────────

    /**
     * Analyze a camera frame with a text prompt.
     *
     * Uses Gemma 4's turn format:
     * `<start_of_turn>user [System Prompt + User/Image Context]<end_of_turn><start_of_turn>model` — the system directive is folded into the user turn (Gemma defines no system role)
     * Image patch tokens are bound natively by the engine via Content.ImageBytes.
     *
     * @param bitmap          Camera frame — will be downscaled proportionally before inference
     * @param prompt          User query describing what to analyze
     * @param memoryContext   Optional context string injected below the baseline instruction
     * @param targetDimension Target bitmap dimension (scales proportionally, no center-crop);
     *                        clamped to the device tier's image budget
     * @return The complete model response, or null on error
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
        memoryContext: String? = null,
        similarInteractions: List<SimilarInteraction> = emptyList(),
        sessionId: String = "",
        targetDimension: Int = MAX_INPUT_DIMENSION,
        maxTokens: Int = MAX_TOKENS
    ): String? = withContext(Dispatchers.Default) {
        val eng = engine
        if (eng == null || !isInitialized) {
            val msg = notReadyMessage()
            Log.e(TAG, "VLM not initialized — cannot run inference: $msg")
            onError?.invoke(msg, sessionId)
            return@withContext null
        }

        var scaledBitmap: Bitmap? = null
        try {
            val startTime = System.currentTimeMillis()
            var tPreprocess = 0L
            var tJpeg = 0L
            var tPayload = 0L
            var tLane = 0L
            var tPrefill = 0L
            var firstTokenAt = 0L
            CrashLogFile.log(TAG, "=== ANALYZE IMAGE [PROFILED][brain=${brainForTurn().displayName}] ===")
            CrashLogFile.log(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}")

            // 1. Preprocess bitmap — proportional scale to the TIER-ADJUSTED
            //    target dimension (no center-crop; vision-token memory on
            //    lower tiers is bounded by the smaller dimension).
            val effectiveTarget = clampTargetDimension(targetDimension)
            var stageStart = System.currentTimeMillis()
            scaledBitmap = preprocessBitmap(bitmap, effectiveTarget)
            tPreprocess = System.currentTimeMillis() - stageStart
            CrashLogFile.log(
                TAG,
                "Preprocessed: ${scaledBitmap.width}x${scaledBitmap.height} " +
                    "(target=$effectiveTarget, tier=${deviceProfile.tier})"
            )

            // 2. Encode image to JPEG bytes
            stageStart = System.currentTimeMillis()
            val imageBytes = bitmapToJpegBytes(scaledBitmap)
            tJpeg = System.currentTimeMillis() - stageStart
            CrashLogFile.log(TAG, "JPEG: ${imageBytes.size} bytes")

            // 3. Build the user payload in the ACTIVE brain's turn format.
            //    Gemma: role-tagged single user turn. MiniCPM: plain content
            //    (the bundle's ChatML template renders the roles itself).
            //    User rules come from DynamicPromptBuilder inside the user payload.
            stageStart = System.currentTimeMillis()
            val userPayload = buildUserPayload(prompt, memoryContext)
            val formattedPrompt = buildTurnPrompt(
                brainForTurn(),
                userPayload,
                SYSTEM_DIRECTIVE + perspectiveClauseFor(activeUserLocale)
            )
            tPayload = System.currentTimeMillis() - stageStart
            // PROMPT-SIZE INSTRUMENTATION (2026-10-18): prefill is 100% of
            // user-perceived latency and scales ~linearly with prompt tokens
            // (~3.5 chars/token on this 2B model). Log the size on every
            // inference so any prompt change is an A/B measurement, not a
            // guess. Baseline before prompt slimming: ~3,300 chars on the
            // continuous image lane (GPU prefill 8.0-8.1 s steady).
            CrashLogFile.log(TAG, "Formatted prompt (${formattedPrompt.length} chars): ${formattedPrompt.take(120)}...")

            // 4. Build multimodal contents — formatted prompt text + clamped bitmap.
            //    The native engine binds image patch tokens from Content.ImageBytes
            //    directly; no literal [IMAGE_TOKEN] placeholder is needed or allowed.
            val contents = Contents.of(
                Content.Text(formattedPrompt),
                Content.ImageBytes(imageBytes)
            )

            // 5. Shared inference core — conversation lifecycle, streaming, and
            //    interrupt-safe close all live in runConversation (one place for
            //    the image, text-only, and audio paths).
            // PROFILING: the first onTokenGenerated arrival marks the end of
            // PREFILL (image encode + prompt ingest + vision tower) and the
            // start of DECODE — the boundary we have never been able to see
            // from outside. tLane covers the mutex/drain wait inside
            // runConversation; [PREFILL-T0] arrives from the callback below.
            stageStart = System.currentTimeMillis()
            val firstTokenFlag = booleanArrayOf(false)
            val outerCallback = onTokenGenerated
            onTokenGenerated = { token, sid ->
                if (!firstTokenFlag[0] && sid == sessionId) {
                    firstTokenFlag[0] = true
                    firstTokenAt = System.currentTimeMillis()
                    tPrefill = firstTokenAt - (startTime + tPreprocess + tJpeg + tPayload + tLane)
                    CrashLogFile.log(
                        TAG,
                        "[PIPELINE-BREAKDOWN] threads=${cpuThreadCount()} " +
                            "preprocess=${tPreprocess}ms jpeg=${tJpeg}ms " +
                            "payload=${tPayload}ms lane-wait=${tLane}ms " +
                            "PREFILL=${tPrefill}ms DECODE=${firstTokenAt - startTime - tPrefill - tLane}ms-so-far"
                    )
                }
                outerCallback?.invoke(token, sid)
            }
            val response = runConversation(contents, maxTokens, sessionId)
            tLane = System.currentTimeMillis() - stageStart -
                (if (firstTokenFlag[0]) (System.currentTimeMillis() - firstTokenAt) else 0)
            val elapsed = System.currentTimeMillis() - startTime
            // DECODE duration: wall time from the FIRST streamed token to the
            // completed response. The old template printed a hardcoded
            // "EOF" placeholder here regardless of outcome — misleading,
            // because "EOF" reads like a decode-terminated-anomaly instead
            // of a label. Also: when NO token ever streamed (full response
            // delivered as one terminal message), the first-token callback
            // never fired and tPrefill stays 0 — report that honestly.
            val decodeMs =
                if (firstTokenFlag[0]) elapsed - (firstTokenAt - startTime) else 0L
            val decodeNote = if (firstTokenFlag[0]) "" else " (no streamed token — single-message response)"
            CrashLogFile.log(
                TAG,
                "[ANALYZE-BREAKDOWN] total=${elapsed}ms preprocess=${tPreprocess}ms " +
                    "jpeg=${tJpeg}ms payload=${tPayload}ms lane-wait=${tLane}ms " +
                    "prefill(firstToken)=${tPrefill}ms decode=${decodeMs}ms${decodeNote} " +
                    "prompt=${formattedPrompt.length}chars"
            )
            CrashLogFile.log(TAG, "analyzeImage total: ${elapsed}ms")
            response

        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled inference must end SILENTLY — never surface it as an
            // "Inference error" (the new generation-drain in runConversation is
            // a real suspension point, so job cancellation can now land here).
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "Inference failed", e)
            onError?.invoke("Inference error: ${e.message}", sessionId)
            null
        } finally {
            // Explicitly recycle the scaled bitmap to free memory after inference
            try {
                if (scaledBitmap != null && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                    CrashLogFile.log(TAG, "Scaled bitmap recycled")
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Analyze a TEXT-ONLY prompt — no image, no audio. Used for general
     * knowledge questions ("what is paracetamol used for?") where a camera
     * frame adds nothing. Text-only inference is faster and cheaper than
     * image inference (no vision tokens) and reuses the same shared
     * conversation core as [analyzeImage].
     *
     * @param prompt    The full formatted instruction (DynamicPromptBuilder output)
     * @param sessionId Session gating ID for callbacks
     * @param maxTokens Output token cap for the answer (tier-clamped)
     * @return The complete model response, or null on error
     */
    suspend fun analyzeText(
        prompt: String,
        sessionId: String = "",
        maxTokens: Int = TEXT_ONLY_MAX_TOKENS
    ): String? = withContext(Dispatchers.Default) {
        CrashLogFile.log(TAG, "=== ANALYZE TEXT (no image) [brain=${brainForTurn().displayName}] ===")
        val formattedPrompt = buildTurnPrompt(
            brainForTurn(),
            prompt,
            TEXT_ONLY_SYSTEM_DIRECTIVE + perspectiveClauseFor(activeUserLocale)
        )
        runConversation(
            contents = Contents.of(Content.Text(formattedPrompt)),
            maxTokens = maxTokens,
            sessionId = sessionId
        )
    }

    /**
     * Transcribe speech audio using the model's NATIVE audio encoder
     * (Gemma 4 E2B audio input). Fully offline — no Google services,
     * no network. This is Vyze's rescue path for noisy rooms where
     * Android's SpeechRecognizer fails or hears ambient chatter: capture
     * the user's speech with AudioRecord and feed it straight to the model.
     *
     * LiteRT-LM routes Content.AudioBytes through a miniaudio DECODER that
     * requires a headered container (WAV). Headerless raw PCM fails decoder
     * init with "Failed to initialize miniaudio decoder, error code: -10"
     * (MA_INVALID_FILE) before the audio encoder ever runs — so the PCM is
     * wrapped in a 44-byte WAV header here ([wrapPcmInWav]).
     *
     * Gemma audio spec: mono 16 kHz float32 PCM, samples in [-1, 1]. Audio
     * is charged at 25 tokens per second against the context window (max
     * 30s clip).
     *
     * @param audioBytes Raw 16 kHz mono float32 PCM audio (NO header — from
     *                   [AudioCapture.recordSpeech])
     * @param prompt     ASR instruction ("Transcribe the following speech...")
     * @param sessionId  Session gating ID for callbacks
     * @param maxTokens  Output cap — transcriptions are short
     * @return The transcription, or null on error
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        prompt: String,
        sessionId: String = "",
        maxTokens: Int = ASR_MAX_TOKENS
    ): String? = withContext(Dispatchers.Default) {
        val brain = brainForTurn()
        if (!brain.supportsAudioInput) {
            // MiniCPM has NO audio tower in the bundle: the raw-audio rescue
            // lane is dead (swap plan §4). Degrade EXPLICITLY — the notice is
            // spoken through the callers' error path; a silent null would be
            // a dead lane with no feedback. Native ASR (SpeechRecognizer)
            // remains the voice-input command path either way.
            CrashLogFile.log(
                TAG,
                "TranscribeAudio unavailable on ${brain.displayName} (no audio tower) — degraded-mode notice fired"
            )
            onError?.invoke(AUDIO_RESCUE_DEGRADED_MESSAGE, sessionId)
            return@withContext null
        }
        val wav = wrapPcmInWav(audioBytes)
        CrashLogFile.log(TAG, "=== TRANSCRIBE AUDIO (${audioBytes.size} PCM bytes -> ${wav.size} WAV bytes) ===")
        val formattedPrompt = buildTurnPrompt(brainForTurn(), prompt, "")
        runConversation(
            contents = Contents.of(
                Content.AudioBytes(wav),
                Content.Text(formattedPrompt)
            ),
            maxTokens = maxTokens,
            sessionId = sessionId
        )
    }

    /**
     * Wrap raw 16 kHz mono float32 PCM bytes in a minimal 44-byte WAV (RIFF)
     * header so LiteRT-LM's miniaudio decoder accepts them.
     *
     * WAV accepts IEEE-float encoding (format tag 3), so the captured float
     * samples are written as-is — no conversion, just 4 header bytes per
     * field. A trailing odd byte is impossible (4-byte samples), but the
     * data-size field is still clamped to the actual payload.
     */
    private fun wrapPcmInWav(pcm: ByteArray): ByteArray {
        val sampleRate = AudioCapture.SAMPLE_RATE_HZ
        val numChannels = 1
        val bytesPerSample = 4  // float32
        val blockAlign = numChannels * bytesPerSample
        val byteRate = sampleRate * blockAlign
        val dataSize = pcm.size

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))        // 0-3
        header.putInt(36 + dataSize)                              // 4-7  RIFF chunk size
        header.put("WAVE".toByteArray(Charsets.US_ASCII))         // 8-11
        header.put("fmt ".toByteArray(Charsets.US_ASCII))         // 12-15
        header.putInt(16)                                         // 16-19 fmt chunk size (PCM)
        header.putShort(3.toShort())                              // 20-21 format tag 3 = IEEE float
        header.putShort(numChannels.toShort())                    // 22-23
        header.putInt(sampleRate)                                 // 24-27
        header.putInt(byteRate)                                   // 28-31
        header.putShort(blockAlign.toShort())                     // 32-33
        header.putShort((bytesPerSample * 8).toShort())           // 34-35 bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))         // 36-39
        header.putInt(dataSize)                                   // 40-43

        return header.array() + pcm
    }

    /**
     * Shared inference core for every content type (image, text, audio).
     *
     * Owns the Conversation lifecycle: creation, the callback-based streaming
     * send, the timeout wait, the memory-pressure check, and the CRITICAL
     * interrupt-safe close rules. Keeping this in ONE place means the
     * SIGSEGV guard around Conversation.close() can never drift between the
     * image / text / audio paths.
     *
     * @param contents  Multimodal contents to send
     * @param maxTokens Output token cap for this conversation
     * @param sessionId Session gating ID for callbacks
     * @return The complete trimmed response, or null on error / interrupt
     */
    /**
     * Runs [runConversationInLane] on the dedicated single-thread inference
     * lane (Q3 hardening): engine work never competes with preprocessing,
     * OCR dispatch or DB reads for shared Default/IO threads, and under
     * MODERATE+ thermal policy bitmap preprocessing is routed to this same
     * lane (see [onInferenceLane]) so nothing parallel competes with the
     * GPU for CPU time. Serialization itself is still enforced by
     * [generationMutex]; the lane makes it structural as well.
     */
    private suspend fun runConversation(
        contents: Contents,
        maxTokens: Int,
        sessionId: String
    ): String? = inferenceLane.lane {
        runConversationInLane(contents, maxTokens, sessionId)
    }

    private suspend fun runConversationInLane(
        contents: Contents,
        maxTokens: Int,
        sessionId: String
    ): String? {
        val eng = engine
        if (eng == null || !isInitialized) {
            val msg = notReadyMessage()
            Log.e(TAG, "VLM not initialized — cannot run inference: $msg")
            onError?.invoke(msg, sessionId)
            return null
        }

        // ── Serialize native generation ─────────────────────────────
        // LiteRT-LM runs ONE native generation at a time. Two overlapping
        // runConversation calls (a follow-up query while the previous answer
        // still streams, a model-ASR rescue during a snapshot) make the engine
        // reject the second sendMessageAsync with "Failed to start
        // nativeSendMessageAsync: UNKNOWN:ERROR:". The mutex serializes our
        // side, and the bounded drain additionally waits for a PREVIOUS
        // generation that interrupt() abandoned — its native thread keeps
        // generating until cancelProcess() stops it, and the engine stays busy
        // until that finishes.
        return generationMutex.withLock {
            withTimeoutOrNull(GENERATION_DRAIN_TIMEOUT_MS) { generationFinished.await() }

            // ── Memory Safety (lower tiers) ─────────────────────────
            // Reclaim what we can BEFORE the engine allocates fresh KV-cache
            // tensors for this turn. On Tier 2/3 idle-cache pressure is the
            // #1 inference killer; a GC (+ trim on Tier 3) beforehand is
            // cheap insurance against mid-generation OOM.
            if (deviceProfile.tier != DeviceTier.TIER_1) {
                reclaimMemoryBeforeTensorAllocation(
                    aggressive = deviceProfile.tier == DeviceTier.TIER_3
                )
            }

            val turnFinished = CompletableDeferred<Unit>()
            generationFinished = turnFinished

            try {
                runConversationLocked(contents, maxTokens, sessionId, turnFinished)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job cancelled while waiting on the drain (or during setup) —
                // propagate so the inference ends silently, and leave
                // turnFinished incomplete: if a native generation did start it
                // will complete it via its terminal callback.
                throw e
            } catch (e: Throwable) {
                // Setup / exception path — no native generation started for this
                // turn, so never leave the next inference waiting on it.
                turnFinished.complete(Unit)
                Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
                CrashLogFile.logError(TAG, "Inference failed", e)
                onError?.invoke("Inference error: ${e.message}", sessionId)
                null
            }
        }
    }

    /**
     * Locked body of [runConversation] — runs under [generationMutex] with the
     * previous generation already drained. [turnFinished] completes when the
     * NATIVE side of this turn reports done (onDone / onError), so a following
     * inference knows the engine is truly free — even when this turn was
     * abandoned by [interrupt] and its close() was skipped.
     */
    private suspend fun runConversationLocked(
        contents: Contents,
        maxTokens: Int,
        sessionId: String,
        turnFinished: CompletableDeferred<Unit>
    ): String? {
        val eng = engine
        if (eng == null || !isInitialized) {
            val msg = notReadyMessage()
            Log.e(TAG, "VLM not initialized — cannot run inference: $msg")
            onError?.invoke(msg, sessionId)
            return null
        }
        val result: String? = try {
            val conversationConfig = ConversationConfig(
                maxOutputToken = clampMaxTokens(maxTokens),
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                )
            )

            // Create conversation — managed manually (NOT via use{}) so we can
            // prevent Conversation.close() when interrupted. Closing while the
            // native thread is still running causes SIGSEGV in liblitertlm_jni.so.
            val conversation = eng.createConversation(conversationConfig)
            activeConversation = conversation  // Expose to interrupt() for cancelProcess()
            wasInterrupted = false  // Reset flag before starting new inference

            try {
                // Send via callback-based API (avoids SendChannel crash)
                val responseBuilder = StringBuilder()
                val latch = CountDownLatch(1)
                activeLatch = latch  // Expose to interrupt() for cancellation
                var inferenceError: String? = null

                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        try {
                            val text = message.toString()
                            if (text.isNotEmpty()) {
                                responseBuilder.append(text)
                                onTokenGenerated?.invoke(text, sessionId)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "onMessage error: ${e.message}")
                        }
                    }

                    override fun onDone() {
                        Log.d(TAG, "sendMessageAsync onDone")
                        turnFinished.complete(Unit)  // Native generation fully ended — engine free
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "sendMessageAsync onError: ${throwable.message}", throwable)
                        inferenceError = throwable.message
                        turnFinished.complete(Unit)  // Native generation fully ended — engine free
                        latch.countDown()
                    }
                }

                conversation.sendMessageAsync(contents, callback)

                // Wait for completion (max 180s — Gemma 3n E2B int4 at 3.66GB)
                // On low-RAM / Tier 3 devices, use a shorter timeout to fail
                // fast instead of hanging during an OOM recovery that may
                // never complete.
                val timeoutSec =
                    if (isLowRamDevice || deviceProfile.tier == DeviceTier.TIER_3) {
                        LOW_RAM_INFERENCE_TIMEOUT_SEC
                    } else {
                        INFERENCE_TIMEOUT_SEC
                    }
                val completed = latch.await(timeoutSec, TimeUnit.SECONDS)

                // ── Memory pressure check (tier-aware) ─────────────
                // If available heap drops below the tier's threshold during
                // inference, log a warning. The watchdog timer in
                // VyzeCoreController handles the force-reset if the engine
                // hangs due to OOM.
                val remainingMB = availableHeapMB()
                val pressureThreshold = when (deviceProfile.tier) {
                    DeviceTier.TIER_1 -> LOW_RAM_THRESHOLD_MB
                    DeviceTier.TIER_2 -> LOW_RAM_THRESHOLD_MB_TIER2
                    else -> LOW_RAM_THRESHOLD_MB_TIER3
                }
                if (remainingMB < pressureThreshold) {
                    Log.w(TAG, "Memory pressure during inference: ${remainingMB}MB free — may OOM")
                    CrashLogFile.log(TAG, "LOW MEMORY WARNING: ${remainingMB}MB free during inference")
                }
                activeLatch = null  // Clear — interrupt() can no longer signal this inference

                // Check if interrupt() released the latch externally. If so, the native
                // thread may still be running — do NOT close the Conversation (causes
                // SIGSEGV) and do NOT fire callbacks (stale session).
                if (wasInterrupted) {
                    Log.i(TAG, "Inference interrupted externally — discarding stale result")
                    return null
                }

                if (!completed) {
                    Log.w(TAG, "Inference timed out after ${timeoutSec}s")
                    inferenceError = "Inference timed out"
                    // Stop the native generation so the engine frees up for the
                    // next query (same mechanism as interrupt()).
                    try {
                        conversation.cancelProcess()
                    } catch (e: Throwable) {
                        Log.w(TAG, "cancelProcess on timeout error: ${e.message}")
                    }
                }

                val fullResponse = responseBuilder.toString().trim()

                if (inferenceError != null && fullResponse.isEmpty()) {
                    Log.e(TAG, "Inference failed: $inferenceError")
                    CrashLogFile.logError(TAG, "Inference failed: $inferenceError")
                    onError?.invoke("Inference error: $inferenceError", sessionId)
                    return null
                }

                Log.i(TAG, "Inference complete: ${fullResponse.length} chars [backend=$activeBackend]")
                CrashLogFile.log(TAG, "Response: ${fullResponse.take(200)}...")

                onComplete?.invoke(fullResponse, sessionId)
                CrashLogFile.exportToDownloads(context)

                fullResponse
            } finally {
                activeConversation = null
                // CRITICAL: Only close the Conversation if it was NOT interrupted.
                // When interrupted, the native thread is still running inside
                // sendMessageAsync → onDone. Closing the Conversation here frees
                // the JNI pointer while the native thread references it → SIGSEGV.
                // interrupt() calls cancelProcess(), so the native thread stops and
                // fires onDone/onError shortly — which completes turnFinished and
                // lets the NEXT inference's drain proceed.
                if (!wasInterrupted) {
                    try {
                        conversation.close()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Conversation close error: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "Skipping conversation.close() — native thread still running")
                }
            }
        } catch (e: Throwable) {
            // A synchronous sendMessageAsync failure means no native generation
            // started for this turn — release the next inference immediately.
            turnFinished.complete(Unit)
            Log.e(TAG, "Inference failed: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "Inference failed", e)
            onError?.invoke("Inference error: ${e.message}", sessionId)
            null
        }
        return result
    }

    // ── Gemma 4 Prompt Formatting ─────────────────────────────────

    /**
     * Build the prompt using the SDK-compatible turn format.
     * The system directive is prepended to the user content.
     * The turn markers use <start_of_turn>/<end_of_turn> which the
     * LiteRT-LM SDK v0.16.1 parses correctly.
     *
     * Image patch tokens are bound natively by the LiteRT-LM engine when
     * Content.ImageBytes is included in the same Contents — no literal
     * `[IMAGE_TOKEN]` string is inserted into the text payload.
     *
     * CONVERSATION DELIMITER CONTRACT (anti-echo fix): optional
     * [conversationTurns] are folded into the SAME single user turn — never
     * as extra model turns, because this Conversation is one-shot per query
     * — and each side is EXPLICITLY role-labeled so the model can always
     * tell user input from its own prior output. The user's CURRENT query
     * stays the LAST line of the user turn, immediately before
     * `<end_of_turn><start_of_turn>model`, so generation begins at the role
     * boundary with the model's own words — never with a replay of the
     * user's question (the follow-up echo bug came from an unlabeled
     * history blob whose last "User:" line was mistaken for the output
     * prefix). `internal` so JVM tests can pin the exact byte format.
     */
    internal fun buildGemmaTurnPrompt(
        userContent: String,
        systemPrompt: String = "",
        conversationTurns: List<Pair<String, String>> = emptyList()
    ): String = companionBuildGemmaTurnPrompt(userContent, systemPrompt, conversationTurns)

    // ── Prompt Assembly ───────────────────────────────────────────

    /**
     * Build the user payload from the query + optional memory context.
     * The system prompt is injected separately via buildGemmaTurnPrompt().
     */
    private fun buildUserPayload(query: String, memoryContext: String?): String {
        val sb = StringBuilder()
        sb.append(query)

        if (!memoryContext.isNullOrBlank()) {
            sb.appendLine()
            sb.append("Context: $memoryContext")
        }

        return sb.toString().trimEnd()
    }

    // ── Adaptive Intelligence Context ─────────────────────────────
    // Similar interactions are injected via DynamicPromptBuilder only.
    // No duplicate injection path here to prevent prompt token bloat.

    // ── Bitmap Preprocessing ──────────────────────────────────────

    /**
     * Preprocess a camera bitmap for VLM input.
     *
     * Gemma 4 handles dynamic aspect ratios natively via its vision token
     * budget. We only downscale if the bitmap exceeds the (tier-clamped)
     * target dimension, preserving the original aspect ratio — no
     * center-cropping.
     *
     * @param source         Raw camera bitmap
     * @param targetDimension Maximum dimension (width or height) allowed
     * @return A NEW bitmap (possibly smaller, same aspect ratio). Caller must recycle.
     */
    private fun preprocessBitmap(source: Bitmap, targetDimension: Int = MAX_INPUT_DIMENSION): Bitmap {
        val w = source.width
        val h = source.height
        val maxDim = maxOf(w, h)

        // Only downscale if the bitmap exceeds target — preserve original aspect ratio
        return if (maxDim > targetDimension) {
            val scale = targetDimension.toFloat() / maxDim
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, newW, newH, true)
        } else {
            // Bitmap is already within target — return as-is (no copy needed)
            source
        }
    }

    /**
     * Convert a Bitmap to JPEG bytes for the LiteRT-LM API.
     * Pre-allocates 8KB buffer to avoid array copy re-allocations.
     */
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(8192)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    // ── Engine Warm-up ──────────────────────────────────────────

    /**
     * Run a dummy text-only message through the engine to pre-compile
     * OpenCL/Vulkan GPU kernels (or warm the CPU decoder on CPU tiers).
     * This eliminates the first-inference cold-start penalty so the real
     * user query benefits from already-warmed delegates.
     */
    /**
     * Text-only engine warm-up. Returns the measured latency in ms —
     * 0 when the engine handle is absent or warm-up failed (non-fatal).
     *
     * Calibration contract (checklist item 3): the returned latency + the
     *   surrounding RAM snapshot feed [VyzeAdaptiveBackend] verdicts.
     * Deliberately NO fixed image here — see the SIGSEGV warning below.
     */
    private suspend fun warmUp(warmEngine: Engine? = null): Long = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        // Target defaults to the ACTIVE serving engine; the background GPU
        // build warms its own engine BEFORE the wiring/swap so the first
        // query running after the swap doesn't pay the warm-up latency.
        val eng = warmEngine ?: engine ?: return@withContext 0L
        try {
            // TEXT-ONLY warm-up — do NOT send images here.
            // Sending Content.ImageBytes before the vision encoder is fully
            // initialized causes a SIGSEGV (null pointer in liblitertlm_jni.so).
            // The text decoder and GPU kernels are warmed up by this text-only
            // message; the vision encoder warms up on the first real inference.
            val conversationConfig = ConversationConfig(
                maxOutputToken = clampMaxTokens(MAX_TOKENS),
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE
                )
            )

            eng.createConversation(conversationConfig).use { conversation ->
                val contents = Contents.of(
                    Content.Text("hello")
                )
                val latch = CountDownLatch(1)
                conversation.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: Message) { /* no-op */ }
                    override fun onDone() { latch.countDown() }
                    override fun onError(throwable: Throwable) {
                        Log.w(TAG, "warmUp onError: ${throwable.message}")
                        latch.countDown()
                    }
                })
                latch.await(WARMUP_TIMEOUT_SEC, TimeUnit.SECONDS)
            }

            val elapsed = System.currentTimeMillis() - startMs
            Log.i(TAG, "Engine warm-up completed (text-only) [backend=$activeBackend, ${elapsed}ms]")
            return@withContext elapsed
        } catch (e: Throwable) {
            Log.w(TAG, "Engine warm-up failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
            return@withContext 0L
        }
    }

    // ── Engine State ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && engine != null
    fun getActiveBackend(): String = activeBackend

    /** True when VLM generation is usable — always false on Tier 0 devices. */
    fun isVlmAvailable(): Boolean = isReady()

    /** True when the device is Tier 0 (VLM bypassed; fast-path tools remain). */
    fun isVlmBypassed(): Boolean = deviceProfile.tier == DeviceTier.TIER_DISABLED

    /**
     * First-token watchdog budget for THIS device (ms).
     *
     * Why it exists: the controller's watchdog re-arms on every RECEIVED
     * token, but prefill produces no tokens — on lower-tier SoCs the vision
     * encoder + prompt prefill of an image query can legitimately take
     * longer than a flagship's whole first sentence. A single fixed 15 s
     * budget executed a healthy mid-prefill query on an Infinix (Helio G-series)
     * as "Inference timed out" (observed 2026-10-09: watchdog fired at 15 s
     * while prefill was still running → "Sorry, I did not get an answer").
     *
     * Tiered first-token grace — must stay well below the engine latch
     * (INFERENCE_TIMEOUT_SEC = 180 s):
     *   - Tier 1 (flagship, GPU, MTP): 15 s — unchanged, tuned here.
     *   - Tier 2 (mid-range): 25 s — slower GPU or CPU fallback.
     *   - Tier 3 (lower-end, CPU-direct): 40 s — CPU image prefill dominates.
     *   - Tier 0: irrelevant (no VLM), returns the Tier-3 value harmlessly.
     */
    fun firstTokenWatchdogBudgetMs(): Long {
        val tierBudget = when (deviceProfile.tier) {
            DeviceTier.TIER_1 -> 15_000L
            DeviceTier.TIER_2 -> 25_000L
            else -> 40_000L
        }
        // CPU-SERVING AWARENESS (2026-10-18): while a remembered-GPU device
        // rides CPU-before-swap, first-token latency is XNNPack speed, not
        // GPU speed — the tier figures were tuned ON the GPU path. Take the
        // Tier-3 figure as the CPU floor so a healthy CPU prefill is never
        // executed as a hang.
        return if (servingOnCpu()) maxOf(tierBudget, 40_000L) else tierBudget
    }

    /**
     * One-time grace for the FIRST image inference after engine creation.
     * The vision encoder's OpenCL/Vulkan kernels compile lazily on the first
     * real image (init warm-up is deliberately text-only after the SIGSEGV
     * incident), and on Mali-class GPUs that compile alone can exceed a
     * minute. Observed 2026-10-09 on Infinix Hot 60i: the tier budget (25 s)
     * killed the first-ever image query while vision-kernel compile/prefill
     * was still running — the engine latch would have waited 180 s.
     */
    @Volatile
    private var firstImageInferencePending = true

    /**
     * Watchdog budget for a query about to start. [isImageQuery] queries on a
     * fresh engine get the one-time [FIRST_IMAGE_INFERENCE_BUDGET_MS] grace;
     * everything else uses the tier budget. Must stay below the engine
     * inference latch (180 s / 60 s low-RAM).
     *
     * CPU-vision image queries (Tier 2/3, see the visionBackend separation in
     * createEngineGuarded) get [CPU_VISION_IMAGE_BUDGET_MS] for EVERY image
     * query, not just the first: observed on the Hot 60i (2026-10-09) the
     * first CPU-vision query answered correctly in 74 s, but a follow-up was
     * killed at the 25 s tier budget — CPU image prefill on Helio-class SoCs
     * legitimately exceeds GPU-tuned budgets every time. The progress-aware
     * re-arm still protects long ANSWERS; this budget only bounds the wait
     * for the FIRST token.
     */
    fun watchdogBudgetMs(isImageQuery: Boolean): Long {
        if (!isImageQuery) return firstTokenWatchdogBudgetMs()
        // CPU-SERVING AWARENESS (2026-10-18): every image query on an
        // actively-serving CPU engine gets the extended CPU budget — on the
        // 60i-class device CPU image prefill alone measures 14-17 s, which
        // both the GPU-tuned Tier-1 15 s budget and the one-time grace
        // (armed but already CONSUMED by earlier CPU-era images) fail to
        // cover. Watchdog fired mid-prefill twice in one CPU-first session
        // (VyzeCoreController: "Snapshot trigger FAILED: JobCancellation-
        // Exception: StandaloneCoroutine was cancelled"). GPU budgets only
        // apply once GPU actually serves.
        if (servingOnCpu()) return CPU_VISION_IMAGE_BUDGET_MS
        return if (deviceProfile.tier == DeviceTier.TIER_1) {
            if (firstImageInferencePending) FIRST_IMAGE_INFERENCE_BUDGET_MS else firstTokenWatchdogBudgetMs()
        } else {
            CPU_VISION_IMAGE_BUDGET_MS
        }
    }

    /** Whether the ACTIVE engine is the CPU one (never true once a GPU swap lands). */
    private fun servingOnCpu(): Boolean = activeBackend == "CPU"

    /** Consume the one-time first-image grace (call when the image watchdog arms). */
    fun markImageInferenceStarted() {
        firstImageInferencePending = false
    }

    /** Whether the currently loaded brain supports audio input (has audio encoder). */
    fun brainSupportsAudioInput(): Boolean = (activeBrain ?: BrainModel.PROD_DEFAULT).supportsAudioInput

    /** Human-readable device tier (for status lines / telemetry). */
    fun getDeviceTierName(): String = deviceProfile.tier.label

    /**
     * Raw tier value for callers that branch on it (tier-aware budgets,
     * dimension presets). Never a UI string — use [getDeviceTierName] for that.
     */
    internal fun currentTier(): DeviceTier = deviceProfile.tier

    /** Full hardware profile summary (for logs / diagnostics screens). */
    fun getDeviceProfileSummary(): String = profileSummary(deviceProfile)

    /**
     * Check if the production brain's model file exists on disk (without
     * initializing the engine). Used to avoid announcing 'downloading' when
     * the MiniCPM bundle is already present.
     */
    fun isModelOnDisk(): Boolean {
        return modelFileExistsFor(BrainModel.PROD_DEFAULT)
    }

    // ── Native Interruption ──────────────────────────────────────

    /**
     * Immediately release the blocking [CountDownLatch.await] in [analyzeImage]
     * so the calling coroutine resumes without waiting for the full inference.
     *
     * **Engine stays alive:** Unlike [resetSession], this does NOT close the
     * underlying Engine. The next [analyzeImage] call executes immediately
     * without model reload latency.
     *
     * **Stale response safety:** The caller (VyzeCoreController) gates callbacks
     * via sessionId == activeSessionId — any tokens or onComplete from the
     * interrupted inference are discarded by the caller.
     */
    fun interrupt() {
        val latch = activeLatch
        if (latch != null) {
            Log.i(TAG, "interrupt: releasing active inference latch")
            wasInterrupted = true   // Signal analyzeImage to skip close + callbacks
            // Stop the NATIVE generation too. Releasing only the Java latch
            // leaves the native thread generating to completion, so the engine
            // stays busy and rejects the NEXT sendMessageAsync with
            // "Failed to start nativeSendMessageAsync" — every quick follow-up
            // after an interrupt would fail. cancelProcess() is the LiteRT-LM
            // API for this and is safe to call from any thread.
            val conversation = activeConversation
            if (conversation != null) {
                try {
                    Log.d(TAG, "interrupt: cancelling native process")
                    conversation.cancelProcess()
                } catch (e: Throwable) {
                    Log.w(TAG, "interrupt: cancelProcess error: ${e.message}")
                }
            }
            latch.countDown()       // Unblocks await() — coroutine resumes immediately
            activeLatch = null
        } else {
            Log.d(TAG, "interrupt: no active latch (no inference running)")
        }
    }

    // ── Session Reset ────────────────────────────────────────────

    /**
     * Lightweight session reset — clears the Engine's native KV-cache by
     * closing and reinitializing the engine. This purges any stale attention
     * embeddings that persist across inferences within the same Engine instance.
     *
     * Cost: Model reload (~2-4s on GPU). Only call on lifecycle transitions
     * (e.g., app returning from background) — NOT on every inference.
     *
     * Each [analyzeImage] call already creates a fresh Conversation via
     * [Engine.createConversation], so conversation-level history is already
     * isolated. This method addresses Engine-level KV-cache accumulation.
     *
     * Reinitialization uses the SAME tiered backend chain as [initialize]
     * (GPU → CPU per device tier) so the optimal backend is always selected
     * and a mid-reset GPU driver failure can never cancel the caller's job
     * or take down non-VLM features.
     */
    fun resetSession() {
        if (!isInitialized || engine == null) return
        if (sessionResetInProgress) {
            Log.i(TAG, "resetSession: already in progress — ignoring duplicate reset")
            return
        }
        Log.i(TAG, "resetSession: clearing Engine KV-cache via reinit")
        // VISIBILITY (blind-first): while this reinit runs, the engine is DEAD
        // but callers used to still see "ready" — every tap during the ~20+
        // second blackout hit a dead engine and failed async with
        // "AI model not ready" (silent for a blind user). isSessionResetting()
        // makes VlmEngineManager.isEngineReady() return false so the tap
        // pipeline speaks the "brain is still waking up" notice instead.
        sessionResetInProgress = true
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "resetSession close error: ${e.message}")
        }
        engine = null
        isInitialized = false

        // Reinitialize on a background thread — all failures are contained
        // inside this job (SupervisorJob scope: a failure here can never
        // cancel the parent scope or any other feature's work).
        scope.launch(Dispatchers.IO) {
            try {
                if (deviceProfile.tier == DeviceTier.TIER_DISABLED) {
                    Log.i(TAG, "resetSession: Tier 0 device — VLM stays bypassed")
                    return@launch
                }
                // Keep the SAME brain this session started with — a reset
                // never hot-swaps brains mid-conversation.
                val brain = activeBrain ?: BrainModel.PROD_DEFAULT
                val modelFile = resolveModelFile(brain)
                if (modelFile == null) {
                    Log.e(TAG, "resetSession: ${brain.fileName} not found — VLM stays offline")
                    sessionResetInProgress = false
                    return@launch
                }

                val tier = deviceProfile.tier
                // Same CPU-first contract as the cold path: a remembered-GPU
                // device re-enters service on CPU immediately; the GPU engine
                // rebuilds in the background and swaps in when idle.
                val rememberedGpu = rememberedBackendPrefersGpu()
                for (step in backendPlanFor(brain, tier)) {
                    if (rememberedGpu && step.backendName.equals("GPU", true)) {
                        CrashLogFile.log(TAG, "resetSession: GPU deferred to background build")
                        continue
                    }
                    if (createEngineGuarded(brain, modelFile, step, tier)) {
                        Log.i(TAG, "resetSession: reinitialized on ${step.backendName} [tier=$tier]")
                        warmUp()
                        if (rememberedGpu && step.backendName.equals("CPU", true)) {
                            launchBackgroundGpuBuild(brain, modelFile, tier)
                        }
                        return@launch
                    }
                    Log.e(TAG, "resetSession: ${step.backendName} init failed — trying next backend")
                }
                Log.e(TAG, "resetSession: all backends for $tier failed — VLM stays offline (non-VLM features unaffected)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                sessionResetInProgress = false
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "resetSession reinit failed: ${e.message}", e)
                engine = null
                isInitialized = false
            } finally {
                sessionResetInProgress = false
            }
        }
    }

    /** True while a [resetSession] teardown+reinit blackout is in flight —
     *  the engine object exists in code but CANNOT serve inference. */
    @Volatile
    private var sessionResetInProgress = false
    fun isSessionResetting(): Boolean = sessionResetInProgress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Dedicated single-thread inference lane (Q3). A limitedParallelism(1)
     * view over the IO pool — only ever one engine/preprocess task runs on
     * it; blocking native waits behave as on IO. [onInferenceLane] is the
     * public hook the controller uses for thermal-tier preprocessing.
     */
    private val inferenceLane = object {
        private val dispatcher = Dispatchers.IO.limitedParallelism(1)
        suspend fun <T> lane(block: suspend () -> T): T = withContext(dispatcher) { block() }
    }

    /**
     * Dedicated single-thread ENGINE INIT lane. Engine creation/initialize()
     * runs here — structurally isolated from the inference lane so a native
     * build can never interleave with a live generation on the same thread,
     * and never competes with inference for a pooled worker.
     */
    private val engineInitDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Run [block] on the single-thread inference lane. Used by
     * VyzeCoreController to route MODERATE+ thermal bitmap preprocessing
     * onto the same thread that will run the generation.
     */
    suspend fun <T> onInferenceLane(block: suspend () -> T): T = inferenceLane.lane(block)

    // ── Model Resolution ──────────────────────────────────────────

    /**
     * Resolve the given brain's model file from the device filesystem.
     *
     * Lookup order:
     *  1. Public Download folder — `/storage/emulated/0/Download/<brain.fileName>`
     *  2. App-scoped external files — `context.getExternalFilesDir(null)`
     */
    private fun resolveModelFile(brain: BrainModel): File? {
        // Tier 1: Public Download folder (most accessible via ADB push)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists()) {
            val downloadsFile = File(downloadsDir, brain.fileName)
            Log.d(TAG, "Tier 1: Checking ${downloadsFile.absolutePath}")
            if (downloadsFile.exists() && downloadsFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Tier 1: Model found at ${downloadsFile.absolutePath} (${downloadsFile.length()} bytes)")
                return downloadsFile
            }
            Log.d(TAG, "Tier 1: Not found (exists=${downloadsFile.exists()}, size=${downloadsFile.length()})")
        } else {
            Log.d(TAG, "Tier 1: Downloads directory unavailable or does not exist")
        }

        // Tier 2: App-scoped external files — no special permissions required
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalFile = File(externalDir, brain.fileName)
            Log.d(TAG, "Tier 2: Checking ${externalFile.absolutePath}")
            if (externalFile.exists() && externalFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Tier 2: Model found at ${externalFile.absolutePath} (${externalFile.length()} bytes)")
                return externalFile
            }
            Log.d(TAG, "Tier 2: Not found (exists=${externalFile.exists()}, size=${externalFile.length()})")
        } else {
            Log.d(TAG, "Tier 2: getExternalFilesDir(null) returned null")
        }

        Log.w(TAG, "Model not found in any location")
        return null
    }

    // ── Cleanup ────────────────────────────────────────────────────

    override fun close() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing VLM engine: ${e.message}")
        }
        engine = null
        isInitialized = false
        activeBackend = "NONE"
        initFailureCount = 0
        gracefulFallbackMessage = null
        // A parked background GPU engine holds its own KV-cache tensors —
        // never leak it across lifecycle teardown.
        try {
            pendingGpuEngine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing parked GPU engine: ${e.message}")
        }
        pendingGpuEngine = null
    }

    // ── Companion ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "VlmEngineManager"

        /**
         * DEBUG-ONLY brain force (set via BrainToggleReceiver, debug builds
         * only). Null = production auto-resolution (MiniCPM preferred, Gemma
         * fallback). A forced brain whose file is missing fails LOUDLY with
         * the standard not-found error — that is the corridor A/B contract.
         * Release builds never set it.
         */
        @Volatile
        var brainOverride: BrainModel? = null

        /**
         * DEBUG-ONLY CPU thread-count override for [Backend.CPU] attempts
         * (Tier 2 CPU fallback / Tier 3). Null = tier default formula
         * (min(4, max(2, cores/2))): unchanged behavior. Set/cleared by
         * [com.vyze.app.debug.CpuThreadsToggleReceiver]; StickyProvider
         * restores it at process start in debug builds (release never sets
         * it — release behavior remains formula-driven).
         *
         * Exists because CPU prefill dominates wall time (94–99% profiled)
         * and XNNPack thread scaling on little-core Helio SoCs is un-
         * verified: the 60i session measures prefill/decode across 2/4/6/8
         * and this makes each value a cold restart instead of an APK
         * rebuild. Recomputed per backend construction, so mid-session
         * changes only take effect at the next engine init.
         */
        @Volatile
        var cpuThreadCountOverride: Int? = null

        /**
         * Pure Gemma 4 turn-format builder (companion so JVM unit tests can
         * pin the delimiter contract without constructing the engine).
         *
         * Wraps [userContent] as ONE `<start_of_turn>user … <end_of_turn>`
         * turn followed by `<start_of_turn>model` — the exact ADK/LiteRT-LM
         * role-tag sequence that makes the model distinguish user input from
         * its own output generation. Optional [conversationTurns] are folded
         * into the SAME single user turn as explicitly role-labeled history
         * (`User: …` / `You answered: …`) under a read-only context header —
         * never as extra model turns, and never adjacent to the generation
         * boundary. The CURRENT query always stays the last line of the user
         * turn, so generation begins with the model's own words, never with
         * a replay of the user's question.
         */
        internal fun companionBuildGemmaTurnPrompt(
            userContent: String,
            systemPrompt: String = "",
            conversationTurns: List<Pair<String, String>> = emptyList()
        ): String {
            val body = StringBuilder()
            if (systemPrompt.isNotBlank()) {
                body.append(systemPrompt)
                body.append("\n\n")
            }
            if (conversationTurns.isNotEmpty()) {
                body.append(
                    "Recent conversation (context only — never repeat, echo, or quote these lines):\n"
                )
                conversationTurns.forEach { (q, a) ->
                    body.append("User: ").append(q).append("\n")
                    body.append("You answered: ").append(a).append("\n")
                }
                body.append("\n")
            }
            body.append(userContent.trimEnd())
            return "<start_of_turn>user\n" +
                body.toString() +
                "\n<end_of_turn>\n" +
                "<start_of_turn>model\n"
        }

        /**
         * Brain dispatcher for turn formatting (companion: JVM-testable).
         * Gemma keeps the ADK/LiteRT-LM role-tag contract; MiniCPM bundles
         * carry their own ChatML template that RENDERS roles itself — we hand
         * it plain content (the bundle emits <|im_start|> itself, per the HF
         * card). Both forms keep the SAME anti-echo contract: labeled history
         * inside ONE user payload, the CURRENT query last.
         */
        internal fun buildTurnPrompt(
            brain: BrainModel,
            userContent: String,
            systemPrompt: String = "",
            conversationTurns: List<Pair<String, String>> = emptyList()
        ): String = when (brain) {
            BrainModel.GEMMA_4E2B ->
                companionBuildGemmaTurnPrompt(userContent, systemPrompt, conversationTurns)
            BrainModel.MINICPM_V47_1B ->
                companionBuildChatTurnPrompt(userContent, systemPrompt, conversationTurns)
        }

        /**
         * MiniCPM (ChatML-template bundle) turn builder: NO Gemma role tags —
         * the bundle template supplies the user/model roles around the plain
         * content we pass. System directive + labeled history + current
         * query, exactly the anti-echo contract of the Gemma form.
         */
        internal fun companionBuildChatTurnPrompt(
            userContent: String,
            systemPrompt: String = "",
            conversationTurns: List<Pair<String, String>> = emptyList()
        ): String {
            val body = StringBuilder()
            if (systemPrompt.isNotBlank()) {
                body.append(systemPrompt)
                body.append("\n\n")
            }
            if (conversationTurns.isNotEmpty()) {
                body.append(
                    "Recent conversation (context only — never repeat, echo, or quote these lines):\n"
                )
                conversationTurns.forEach { (q, a) ->
                    body.append("User: ").append(q).append("\n")
                    body.append("You answered: ").append(a).append("\n")
                }
                body.append("\n")
            }
            body.append(userContent.trimEnd())
            return body.toString()
        }

        // Model file sanity check (applies to every [BrainModel] bundle — our
        // production picks are 1.70 GB / 2.59 GB; the smallest usable bundle is
        // the 1.22 GB text-only MiniCPM variant).
        const val MIN_MODEL_SIZE = 500L * 1024 * 1024

        // Image preprocessing — no center-crop, just proportional downscale
        // Gemma 4 handles dynamic aspect ratios natively
        private const val MAX_INPUT_DIMENSION = 512
        const val JPEG_QUALITY = 75

        // Greedy decoding — fast, concise output with minimal latency
        const val TEMPERATURE = 0.1
        const val TOP_K = 1
        const val TOP_P = 1.0
        const val MAX_TOKENS = 32

        // Text-only Q&A and audio transcription caps
        const val TEXT_ONLY_MAX_TOKENS = 192
        const val ASR_MAX_TOKENS = 96

        /**
         * System directive for TEXT-ONLY inference (analyzeText) — a general
         * knowledge assistant, NOT a scene describer. PERSPECTIVE FIX: strict
         * second-person binding and a 1-sentence cap for fast spoken delivery.
         *
         * LANGUAGE-MIRRORING FIX (regression from the first perspective commit):
         * this directive contains NO hardcoded English spatial wording and NO
         * hardcoded English second-person examples. A 2B on-device model gives
         * the LAST-seen persona wording high attention weight — a pure-English
         * persona here overrode the [OUTPUT LANGUAGE] wrapper that
         * DynamicPromptBuilder prepends, dragging Malay/Chinese answers back
         * into English. The persona stays English-neutral; the language-bound
         * second-person rules are appended per turn by
         * [perspectiveClauseFor] in the ACTIVE language, and the
         * `[OUTPUT LANGUAGE: …]` (top) + `REMEMBER: Respond only in …`
         * (bottom) wrappers remain the sole language authorities.
         */
        /*
         * PROMPT-SLIMMING (2026-10-18, A/B evidence): user-perceived latency
         * is 100% prefill, which scales with prompt tokens. The directive
         * layers previously full-duplicated the [OUTPUT LANGUAGE] tag rules
         * (~630 chars), anti-echo (~160), and the plain-text/TTS contract
         * (~300) on EVERY turn. Each rule now appears exactly ONCE in the
         * stack: language authority lives in DynamicPromptBuilder's top tag
         * + mandate + bottom REMEMBER line; behavior rules live here in one
         * short line each. Every test-pinned regression-fix phrase (tag
         * authority, garble clause, anti-echo) is preserved verbatim.
         */
        private const val TEXT_ONLY_SYSTEM_DIRECTIVE =
            "You are Vyze, a fast, friendly visual assistant speaking aloud to a blind user. " +
            "Answer the user's question directly in 1 short spoken sentence — " +
            "never repeat or echo their question, never say you are an AI or offline. " +
            "If past conversation turns are provided, refer to them when relevant. " +
            "Reply in the language named in the [OUTPUT LANGUAGE] tag (no tag: " +
            "mirror the language of the user's query exactly). " +
            "Pure plain text for text to speech: no markdown symbols, bullets, " +
            "dashes, asterisks, or emoji, plain flowing sentences with clear punctuation."

        // Timeouts
        private const val INFERENCE_TIMEOUT_SEC = 180L  // 3 min for real inference
        private const val LOW_RAM_INFERENCE_TIMEOUT_SEC = 60L  // 1 min on low-RAM devices — fail fast
        private const val WARMUP_TIMEOUT_SEC = 60L       // 60s for engine warm-up (mid-tier may need longer)

        /**
         * Max time to wait for a previous (cancelled) native generation to fully
         * stop before starting a new one. Bounded so a truly hung native thread
         * degrades to the normal send error instead of blocking forever.
         */
        private const val GENERATION_DRAIN_TIMEOUT_MS = 20_000L

        /**
         * Gemma 4 system directive — folded into the user turn (Gemma has no system role).
         * CONVERSATIONAL PERSONA + PERSPECTIVE FIX: strict second-person binding,
         * 1 short spoken sentence, clock/left-right spatial clarity relative to the
         * USER, plain sentences only. Long OCR reads override the sentence cap via
         * an explicit carve-out in DynamicPromptBuilder. SINGLE SOURCE (Phase 4):
         * the former mirrored copy in DynamicPromptBuilder was removed — this is
         * the only definition in the codebase.
         *
         * LANGUAGE-MIRRORING FIX: no hardcoded English spatial wording or
         * second-person examples here (see TEXT_ONLY_SYSTEM_DIRECTIVE for the
         * regression rationale). Language-bound second-person rules arrive per
         * turn via [perspectiveClauseFor] in the ACTIVE language; the
         * `[OUTPUT LANGUAGE: …]` / `REMEMBER: Respond only in …` wrappers from
         * DynamicPromptBuilder stay strictly enforced on every turn.
         *
         * TAG-AUTHORITY FIX (v3): both directives below make the
         * [OUTPUT LANGUAGE] tag the single authority on answer language. The
         * old "detect the language of the user's query" wording conflicted
         * with the tag in the ASR-garble rescue path — detector says Malay,
         * garbled query text reads English, and the 2B model followed the
         * query text: English answer, Malay TTS voice (user-reported "English
         * with a Malay accent"). The tag is always present here because
         * VlmEngineManager only ever receives DynamicPromptBuilder output,
         * which opens with it.
         */
        private const val SYSTEM_DIRECTIVE =
            "You are Vyze, a fast, friendly visual assistant speaking aloud to a blind user. " +
            "Answer in 1 short spoken sentence about what you see, leading directly with " +
            "the answer — never repeat, echo, or quote the user's query. If asked what " +
            "color this is, reply with the color; if asked to read text, reply with the " +
            "text. Mention spatial position ('your left') ONLY when the user asks WHERE " +
            "an object is. OCR EXCEPTION: when OCR text is provided, read ALL of it in " +
            "reading order — the one-sentence cap does not apply. " +
            "Pure plain text for text to speech: no markdown symbols, bullets, dashes, " +
            "asterisks, or emoji, plain spoken sentences only. On follow-up turns answer " +
            "only what is new. Reply in the language named in the [OUTPUT LANGUAGE] tag " +
            "(the tag is the authority on the answer language; query-detection is only " +
            "the no-tag fallback — never default to English otherwise). Describe what " +
            "you see directly, without cross-translating or reasoning-chains."

        /**
         * Second-person spatial directive, emitted in the ACTIVE language by
         * [perspectiveClauseFor]. English carries the full never-say rule;
         * the Malay and Chinese translations carry the same second-person
         * binding in their own script so a 2B model never sees conflicting
         * English persona examples when mirroring ms/zh output.
         */
        private const val PERSPECTIVE_EN =
            "Describe positions relative to the USER ('in front of you', 'to your left', " +
            "'at 12 o'clock') — never 'my left'. Spatial wording only when the question " +
            "asks WHERE; state the object first. Never open every sentence the same way."
        private const val PERSPECTIVE_MS =
            "Nyatakan kedudukan relatif kepada PENGGUNA ('di hadapan anda', 'di sebelah kiri " +
            "anda', 'pukul 12') — jangan sesekali 'saya'. Sebut kedudukan hanya bila soalan " +
            "bertanya DI MANA; nyatakan objek dahulu. Jangan mulakan setiap ayat sama."
        private const val PERSPECTIVE_ZH =
            "位置以用户为基准来描述（'在你面前'、'在你的左边'、'12点钟方向'）——绝不要用'我'作基准。 " +
            "只有当问题询问位置时才提到方位，并且先说物体。每句开头不要一样。"

        // ── Mid-Tier / Low-RAM Thresholds ───────────────────────
        /** Minimum free device RAM (MB) required to attempt model init on standard devices. */
        private const val MIN_RAM_STANDARD_MB = 1200L
        /** Minimum free device RAM (MB) required on devices flagged as low-RAM. */
        private const val MIN_RAM_LOW_RAM_DEVICE_MB = 800L
        /**
         * Extra free-RAM headroom the pre-flight demands of the CPU-direct
         * tier (Tier 3): weights + KV-cache + activations all live in system
         * RAM there (no GPU offload). FIRST-CUT constant — tune from real
         * low-end device data; the tier-awareness is the load-bearing part.
         */
        private const val MIN_RAM_TIER3_EXTRA_MB = 400L
        /** Log a warning if free device RAM drops below this during inference (Tier 1). */
        private const val LOW_RAM_THRESHOLD_MB = 500L
        /** Tier-aware memory-pressure warning thresholds (free MB). */
        private const val LOW_RAM_THRESHOLD_MB_TIER2 = 700L
        private const val LOW_RAM_THRESHOLD_MB_TIER3 = 900L

        // ── Tiered Engine Initialization Strategy ───────────────
        // NOTE: ActivityManager.MemoryInfo.totalMem is KERNEL-ADJUSTED — an
        // "8 GB" phone reports ~7.2-7.7 GB, a "6 GB" phone ~5.4-5.8 GB. The
        // floors below are calibrated to REPORTED values so nominal device
        // tiers land correctly.
        /** Reported-RAM floor for Tier 1 (nominal 8 GB). */
        private const val TIER1_MIN_TOTAL_RAM_MB = 7168L
        /** Reported-RAM floor for Tier 2 (nominal 6 GB); below it → Tier 3. */
        private const val TIER2_MIN_TOTAL_RAM_MB = 5500L
        /**
         * Reported-RAM floor below which VLM generation is bypassed.
         * Calibrated so a NOMINAL 4 GB phone (kernel-adjusted totalMem
         * ~3.6-3.9 GB) lands in Tier 0, matching the init path's own spoken
         * claim "requires a device with at least 4 GB RAM". The old 3400
         * floor let nominal-4GB phones slip past it into Tier 3 CPU-direct
         * VLM — the reported low-end crash band (native OOM during init).
         * Reported 4.0 GB ≈ nominal 4.5 GB+.
         */
        private const val VLM_BYPASS_TOTAL_RAM_MB = 4000L

        /**
         * DEBUG-ONLY corridor override for the MiniCPM vision backend
         * (VisionGpuToggleReceiver, src/debug — never compiled into release):
         *
         *  - `true`  → force GPU vision for the next engine build.
         *  - `null`  → policy ([companionMiniCpmVisionBackendName]) decides.
         *
         * Takes effect at the NEXT engine build — flip via adb, then
         * force-stop and relaunch the process. Default `null` in every
         * release build (the setter does not exist there).
         */
        @Volatile
        var debugGpuVisionOverride: Boolean? = null

        /**
         * MINICPM VISION-BACKEND POLICY — tier + GPU-family adaptive.
         * Pure + JVM-testable (VisionBackendPolicyTest pins every branch);
         * companion so tests never construct the manager.
         *
         * History of this seam:
         *  1. 2026-10-09 v1: global `Backend.CPU()` pin (blind, pre-measure).
         *  2. 2026-10-18: measured mixed backend (vision CPU + decoder GPU)
         *     on the GT 50 Pro — 7.36-7.39 s (all-GPU) vs 3.7-4.3 s (mixed)
         *     image prefill. The pin was the measured Mali winner but did
         *     NOT adapt: every phone, every tier, got the same choice.
         *  3. 2026-10-19 (this policy): the choice keys on the device's own
         *     profile:
         *     - MiniCPM on Tier 1 + Adreno → GPU vision: the ONE family the
         *       model card shows vision-on-GPU as the fastest path (Galaxy
         *       S26: 0.93 s first token vs 2.11 s with CPU vision).
         *     - MiniCPM everywhere else (Mali / lower tiers / unknown HW)
         *       → CPU vision: the measured-winner + hang-safe line
         *       (Mali-G57 vision hang 2026-10-09; GT 50 Pro Mali A/B
         *       2026-10-18; GPU-swap engine inherits the same thumb).
         *     - Non-MiniCPM brains → engine plan backend unchanged (Gemma
         *       dragged vision with the engine and has no measured
         *       counter-case on file).
         *
         * SAFETY: a GPU-vision ATTEMPT that fails at runtime is contained
         * by the controller's first-image watchdog + grading — attempting
         * on the one measured-fast family costs nothing when the family is
         * genuinely capable, and the Adreno-only gate means the known-bad Mali
         * drivers can never see the path.
         *
         * @param isMiniCpm   brain == BrainModel.PROD_DEFAULT.
         * @param stepIsGpu   the engine plan step runs on GPU — doubles as
         *   the plan-level GPU allowance: when the adaptive gate drops GPU
         *   from the plan, GPU vision is dropped with it (a device whose
         *   decoder GPU failed must not be handed a GPU vision encoder).
         * @param tier        the classified hardware tier.
         * @param hardware    Build.HARDWARE (SoC id, e.g. "mt6899" or "sm8550").
         * @param debugOverride DEBUG corridor override; `true` = forced GPU,
         *   `null` = policy.
         * @return ("GPU" | "CPU", human-readable reason for the log).
         */
        internal fun companionMiniCpmVisionBackendName(
            isMiniCpm: Boolean,
            stepIsGpu: Boolean,
            tier: DeviceTier,
            hardware: String,
            debugOverride: Boolean?,
        ): Pair<String, String> {
            if (debugOverride != null) {
                return if (debugOverride)
                    "GPU" to "DEBUG FORCE — corridor A/B vision-GPU"
                else
                    "CPU" to "DEBUG OFF — corridor A/B policy re-check"
            }
            if (!isMiniCpm) {
                return if (stepIsGpu)
                    "GPU" to "non-MiniCPM brain: vision rides the engine plan"
                else
                    "CPU" to "non-MiniCPM brain: vision rides the engine plan"
            }
            return if (stepIsGpu && tier == DeviceTier.TIER_1 && isAdrenoGpu(hardware))
                "GPU" to "Tier 1 Adreno — model-card vision-GPU fastest (Galaxy S26: 0.93 s first token)"
            else
                "CPU" to "measured winner on this class (GT 50 Pro Mali A/B 2026-10-18: " +
                    "GPU vision 7.36-7.39 s vs mixed 3.7-4.3 s; Mali-G57 vision hang 2026-10-09)"
        }

        /**
         * Adreno-GPU heuristic over [Build.HARDWARE] (pure, JVM-testable).
         *
         * Qualcomm SoC families carry Adreno GPUs whose OpenCL drivers the
         * LiteRT-LM GPU delegate handles well on BOTH flagship (8-series)
         * and mid-range (6/7-series) silicon; MediaTek SoCs carry Mali, the
         * family with the measured vision-path problems (slow A/B on
         * Mali-G720, hang on Mali-G57).
         */
        internal fun isAdrenoGpu(hardware: String): Boolean {
            val hw = hardware.lowercase(Locale.US)
            if (hw.isEmpty()) return false
            // Codename hardware strings (qcom/msmnile/kona/taro/kalama/...)
            val qualcommNames = listOf(
                "qcom", "msmnile", "kona", "lahaina", "taro", "kalama",
                "pineapple", "atoll", "lito", "bengal", "holi"
            )
            if (qualcommNames.any { hw.contains(it) }) return true
            // SoC-style ids: sm8550, sdm845, msm8998, qcs9075, qcm6490
            return Regex("\\b(sm|sdm|msm|qcs|qcm)\\d").containsMatchIn(hw)
        }

        /**
         * Tier classification from total RAM + GPU class + the OS low-RAM
         * flag. Pure + JVM-testable (VlmEngineManagerTierTest pins every
         * boundary) — companion so tests never construct the manager.
         *
         *  - Tier 0  : OS-declared low-RAM device, or reported RAM below the
         *              bypass floor → VLM bypassed.
         *  - Tier 1  : reported RAM >= ~8 GB floor AND flagship GPU.
         *  - Tier 2  : reported RAM >= ~6 GB floor, OR a flagship GPU with
         *              squeezed RAM (still gets the GPU→CPU chain).
         *  - Tier 3  : everything else → CPU directly.
         */
        internal fun classifyTier(
            totalRamMb: Long,
            flagship: Boolean,
            midGpu: Boolean,
            isLowRamDevice: Boolean
        ): DeviceTier {
            // The OS itself declares this device memory-constrained — honor
            // the flag before any RAM arithmetic (the totalMem probe is least
            // reliable on exactly the devices the flag covers).
            if (isLowRamDevice) return DeviceTier.TIER_DISABLED
            if (totalRamMb <= 0) {
                // Probe failed — never disable VLM on a guess (documented
                // intent): use the tier with the safest backend chain. The
                // tier-aware pre-flight (requiredFreeRamMB) is the net that
                // degrades a genuinely weak probe-failed device gracefully
                // instead of crashing it.
                return DeviceTier.TIER_2
            }
            if (totalRamMb < VLM_BYPASS_TOTAL_RAM_MB) return DeviceTier.TIER_DISABLED
            return when {
                totalRamMb >= TIER1_MIN_TOTAL_RAM_MB && flagship -> DeviceTier.TIER_1
                totalRamMb >= TIER2_MIN_TOTAL_RAM_MB -> DeviceTier.TIER_2
                // High-end GPU with less RAM: the certified OpenCL/Vulkan
                // drivers are more trustworthy than raw capacity — still try
                // GPU → CPU.
                flagship -> DeviceTier.TIER_2
                else -> DeviceTier.TIER_3
            }
        }

        /**
         * Free-RAM bar for the pre-flight check in [initialize], scaled by
         * the chosen tier: the CPU-direct tier (Tier 3) carries model
         * weights, KV-cache AND vision/audio activations entirely in system
         * RAM (no GPU offload), so the same free reading is riskier there
         * than on a GPU attempt. First-cut margin — tune from device data.
         */
        internal fun requiredFreeRamMB(tier: DeviceTier, isLowRamDevice: Boolean): Long {
            val base = if (isLowRamDevice) MIN_RAM_LOW_RAM_DEVICE_MB else MIN_RAM_STANDARD_MB
            return if (tier == DeviceTier.TIER_3) base + MIN_RAM_TIER3_EXTRA_MB else base
        }

        /** Engine context window (EngineConfig.maxNumTokens) per tier. */
        private const val CONTEXT_TOKENS_TIER1 = 4096
        private const val CONTEXT_TOKENS_TIER2 = 3072
        private const val CONTEXT_TOKENS_TIER3 = 2048
        /** Headroom between the engine context window and per-turn maxOutputToken. */
        private const val CONTEXT_HEADROOM_TOKENS = 256

        /** Per-turn output token cap per tier (defense against runaway generation). */
        private const val MAX_TOKENS_TIER_CAP_TIER1 = 512
        private const val MAX_TOKENS_TIER_CAP_TIER2 = 320
        private const val MAX_TOKENS_TIER_CAP_TIER3 = 256

        /**
         * Image pre-processing dimension cap per tier (vision-token memory
         * ~quadratic). Tuned 2026-10-10 (run 1+2): the profiling evidence
         * (9890214) showed prefill is 94-99% of wall time, so the engine-side
         * cap was aligned to what the controller actually feeds: Tier 2
         * 384→256 ( Hot 60i receives 192px scenes — 384 only ever mattered
         * for tap-lane 1024px frames, which get preprocessed to 256 now
         * instead of 384), Tier 3 256→192 to match its 160-token budget.
         */
        private const val IMAGE_DIMENSION_TIER2 = 256
        private const val IMAGE_DIMENSION_TIER3 = 192

        /**
         * Visual token budget caps (ExperimentalFlags) for image encoding on
         * lower tiers. C (tuned 2026-10-10 after commit 878f made CPU vision
         * stable): Tier 2 320→256, Tier 3 192→160. Prefill encode time scales
         * roughly linearly with visual tokens; on the 8 GB Tier-2 reference
         * (Infinix Hot 60i) the 74–79 s worst-case answers overlap the window
         * with A (192px input) — two axes, both trims, per the plan recorded
         * before implementation. If answer quality regresses, back off to
         * 320/192 — these are budgets, not correctness guarantees.
         *
         * RUN 1+2 (2026-10-10, post-profiling 9890214): Tier 2 256→128 — the
         * profiling breakdown proved prefill dominates (94-99%), and the
         * scene lane ("what's here?", user's most common ask) does NOT do
         * brand/label reads by design; text/tap lanes route to the OCR path
         * and keep their own full-quality pipeline, so a scene-lane token
         * cut does not touch brand accuracy. Quality gate: if corpus/desktop
         * sanity or user testing shows scene descriptions losing salient
         * objects, revert to 256.
         */
        private const val VISUAL_TOKEN_BUDGET_TIER2 = 128
        private const val VISUAL_TOKEN_BUDGET_TIER3 = 160

        /**
         * CPU threads for the CPU-fallback backend (Tier 2 fallback / Tier 3).
         *
         * Thread-scaling VERDICT (2026-10-15, Infinix Hot 60i / mt6768, 8 cores,
         * same fridge scene ×2 per value, CPU-vision lane, [PIPELINE-BREAKDOWN]):
         * PREFILL is NOT thread-bound — warmed-run PREFILL is flat across
         * 2/4/6/8 (38.5 / 38.9 / 39.1 / 38.4 s); first-run variance (42.5–52.5 s)
         * is cold-start warm-up, uncorrelated with thread count. Default 4 stays.
         * Re-tested via the debug CpuThreadsToggleReceiver if a future engine
         * or SoC changes the vision encode path.
         */
        private const val CPU_FALLBACK_THREADS = 4

        /** Consecutive init failures before the engine degrades gracefully. */
        private const val MAX_INIT_FAILURES = 3

        /** Hard budget for ONE GPU Engine build + initialize() (2.59 GB model mmap needs time). */
        private const val ENGINE_CREATE_TIMEOUT_MS = 90_000L
        /** CPU-backend builds get a shorter budget (no GPU driver stall is possible). */
        private const val CPU_ENGINE_CREATE_TIMEOUT_MS = 60_000L
        /** Max wait for a timed-out engine build to materialize before it is closed. */
        private const val PENDING_BUILD_DRAIN_MS = 60_000L

        /** One-time first-image-inference grace (vision-kernel compile on Mali GPUs). */
        private const val FIRST_IMAGE_INFERENCE_BUDGET_MS = 90_000L

        /**
         * Per-query budget for image inference with the vision encoder on CPU
         * (Tier 2/3). CPU image prefill on Helio-class SoCs takes 60-75 s
         * (observed: correct answer in 74 s); every image query needs this
         * headroom, not just the first.
         */
        private const val CPU_VISION_IMAGE_BUDGET_MS = 120_000L

        /** Friendly fallback (spoken as TTS) when VLM is bypassed on < ~4 GB devices. */
        private const val VLM_BYPASS_MESSAGE =
            "This device's memory is too small for visual AI. " +
                "Text reading, color and light detection still work."
        /** Friendly fallback after repeated VLM init failures — fast-path tools stay operational. */
        private const val VLM_UNAVAILABLE_MESSAGE =
            "Visual AI is unavailable on this device. " +
                "Text reading, color and light detection still work."

        /**
         * Spoken notice when the raw-audio rescue lane is unavailable
         * (MiniCPM has no audio tower). Loud degradation, never silence.
         */
        private const val AUDIO_RESCUE_DEGRADED_MESSAGE =
            "Voice backup is not available on this device setup. " +
                "Your normal speech input still works — try again in a quieter place."

        private var nativeLibLoaded = false

        /**
         * Explicitly load the litertlm native library before Engine creation.
         */
        @Synchronized
        fun ensureNativeLibLoaded() {
            if (nativeLibLoaded) return
            try {
                System.loadLibrary("litertlm_jni")
                nativeLibLoaded = true
                Log.i(TAG, "Native library (litertlm_jni) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("litertlm")
                    nativeLibLoaded = true
                    Log.i(TAG, "Native library (litertlm) loaded successfully")
                } catch (e2: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native library auto-load failed: ${e2.message}. " +
                        "Will rely on AAR bundled loading.")
                    nativeLibLoaded = true
                }
            }
        }
    }
}

/**
 * Hardware classification of the running device (detected once, cached).
 * `internal` (not file-private) so the JVM tier-boundary test can pin
 * [VlmEngineManager.classifyTier] without constructing the manager.
 */
internal enum class DeviceTier(val label: String) {
    TIER_1("Tier 1 (high-end)"),
    TIER_2("Tier 2 (mid-range)"),
    TIER_3("Tier 3 (lower-end)"),
    TIER_DISABLED("Tier 0 (VLM bypassed — memory-constrained)")
}

/** Immutable snapshot of the detected hardware profile. */
private data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamMB: Long,
    val hardware: String,
    val flagshipGpu: Boolean,
    val midRangeGpu: Boolean,
    val glesVersion: String
)

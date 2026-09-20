package com.vyze.app.core
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.vyze.app.memory.MemoryRepository
import com.vyze.app.memory.SimilarInteraction
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device VLM engine wrapper using Google's LiteRT-LM framework.
 *
 * ## Model
 * Gemma 4 E2B (Edge 2 Billion) — multimodal vision-language model.
 * File: gemma-4-E2B-it.litertlm (2.59 GB) — generic variant with vision encoder
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

        val tier = classifyTier(totalRamMb, flagship, midGpu)
        val profile = DeviceProfile(tier, totalRamMb, hardware, flagship, midGpu, gles)

        Log.i(TAG, "Device tier detected: ${profileSummary(profile)}")
        CrashLogFile.log(TAG, "=== DEVICE TIER ===")
        CrashLogFile.log(TAG, "Tier: ${tier.label}")
        CrashLogFile.log(TAG, "Total RAM: ${totalRamMb}MB (reported) | hw=$hardware | flagshipGPU=$flagship | midGPU=$midGpu | $gles")
        CrashLogFile.log(TAG, "Low-RAM flag (Android): $isLowRamDevice")
        return profile
    }

    /**
     * Tier classification from total RAM + GPU class:
     *  - Tier 0  : reported RAM below the ~4 GB floor → VLM bypassed.
     *  - Tier 1  : reported RAM >= ~8 GB floor AND flagship GPU.
     *  - Tier 2  : reported RAM >= ~6 GB floor, OR a flagship GPU with
     *              squeezed RAM (still gets the GPU→CPU chain).
     *  - Tier 3  : everything else → CPU directly.
     */
    private fun classifyTier(totalRamMb: Long, flagship: Boolean, midGpu: Boolean): DeviceTier {
        if (totalRamMb <= 0) {
            // Detection failed — never disable VLM on a guess; use the tier
            // whose backend chain is safest (GPU attempt with CPU fallback).
            return DeviceTier.TIER_2
        }
        if (totalRamMb < VLM_BYPASS_TOTAL_RAM_MB) return DeviceTier.TIER_DISABLED
        return when {
            totalRamMb >= TIER1_MIN_TOTAL_RAM_MB && flagship -> DeviceTier.TIER_1
            totalRamMb >= TIER2_MIN_TOTAL_RAM_MB -> DeviceTier.TIER_2
            // High-end GPU with less RAM: the certified OpenCL/Vulkan drivers
            // are more trustworthy than raw capacity — still try GPU → CPU.
            flagship -> DeviceTier.TIER_2
            else -> DeviceTier.TIER_3
        }
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
            // MediaTek Dimensity 8000/9000 series
            "mt6983", "mt6985", "mt6989", "mt6893", "mt6895", "mt6896",
            "mt6879", "mt6886",
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
     *  | 0    | < ~4GB RAM                               | none — VLM bypassed, TTS fallback msg | —       | —   |
     *
     * Every engine-creation failure (JNI/C++ driver errors, OOM, GPU-init
     * cancellations like "m0 was canceled") is caught HERE and either retried
     * on the next backend in the chain or counted for graceful degradation —
     * it can never cancel the parent CoroutineScope or break non-VLM
     * features (standalone OCR, Light Check, Color Analysis).
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        CrashLogFile.log(TAG, "=== INIT START (Gemma 4 E2B, tiered) ===")

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
        // Gemma 4 E2B requires ~3 GB of RAM at peak (model weights
        // + KV-cache + image encoding). Reject early on constrained devices
        // with a clear error instead of crashing mid-inference with OOM.
        // Trigger GC first to reclaim idle memory before measuring.
        System.gc()
        Thread.sleep(100)
        val freeMB = availableHeapMB()
        val requiredMB = if (isLowRamDevice) MIN_RAM_LOW_RAM_DEVICE_MB else MIN_RAM_STANDARD_MB
        if (freeMB < requiredMB) {
            val errorMsg = "Insufficient RAM: ${freeMB}MB free, need ${requiredMB}MB. " +
                "Gemma 4 E2B requires a device with at least ${if (isLowRamDevice) "3" else "4"}GB RAM."
            Log.e(TAG, errorMsg)
            CrashLogFile.logError(TAG, errorMsg)
            onError?.invoke(errorMsg, "")
            return@withContext false
        }
        Log.i(TAG, "RAM check passed: ${freeMB}MB free (lowRam=$isLowRamDevice, required=${requiredMB}MB)")
        CrashLogFile.log(TAG, "RAM: ${freeMB}MB free, lowRam=$isLowRamDevice")

        try {
            Log.i(TAG, "Starting Gemma 4 E2B initialization [tier=${deviceProfile.tier.label}]...")

            // Step 0: Ensure native library is loaded
            CrashLogFile.log(TAG, "Step 0: Loading native library")
            ensureNativeLibLoaded()

            // Step 1: Resolve model file
            onStepProgress?.invoke(10, "Resolving model file...")
            CrashLogFile.log(TAG, "Step 1: Resolving model file")

            val modelFile = resolveModelFile()
            if (modelFile == null) {
                val errorMsg = buildModelNotFoundError()
                Log.e(TAG, errorMsg)
                CrashLogFile.logError(TAG, errorMsg)
                onError?.invoke(errorMsg, "")
                return@withContext false
            }

            Log.i(TAG, "Model resolved: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            CrashLogFile.log(TAG, "Model: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)}MB)")

            // Step 2: Walk the tier's backend plan (GPU → CPU per tier).
            // NPU is skipped — the Gemma 4 E2B generic model does not ship with
            // TF_LITE_PREFILL_DECODE for NPU, so NPU init always fails and
            // wastes 5-10s on mid-tier Dimensity/Snapdragon devices.
            val tier = deviceProfile.tier
            val plan = backendPlanFor(tier)
            CrashLogFile.log(TAG, "Backend plan [$tier]: ${plan.joinToString(" → ") { it.backendName }}")

            for ((index, step) in plan.withIndex()) {
                onStepProgress?.invoke(30 + index * 10, progressMessageFor(step))
                val ok = createEngineGuarded(modelFile, step, tier)
                if (ok) {
                    // Warm-up — pre-compile GPU kernels / warm the CPU decoder
                    onStepProgress?.invoke(75, "Sharpening my focus (this takes a moment)...")
                    CrashLogFile.log(TAG, "Step 3: Engine warm-up (dummy inference) [${step.backendName}]...")
                    warmUp()

                    onStepProgress?.invoke(95, "Finalizing...")
                    CrashLogFile.log(TAG, "=== INIT SUCCESS [${step.backendName}, $tier] ===")
                    Log.i(TAG, "Gemma 4 E2B loaded successfully [backend=${step.backendName}, tier=$tier]")
                    return@withContext true
                }
                CrashLogFile.log(TAG, "Backend ${step.backendName} failed — moving to next in plan")
            }

            // All backends in the tier's plan failed — count the failure.
            // Past the threshold, degrade gracefully: friendly message via
            // onError (spoken as TTS); fast-path tools stay untouched.
            initFailureCount++
            isInitialized = false
            val degrade = initFailureCount >= MAX_INIT_FAILURES
            if (degrade) gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            val errorMsg = "VLM init failed: all backends for $tier exhausted." +
                if (degrade) " $VLM_UNAVAILABLE_MESSAGE" else " (attempt $initFailureCount/$MAX_INIT_FAILURES)"
            Log.e(TAG, errorMsg)
            CrashLogFile.logError(TAG, errorMsg)
            onError?.invoke(errorMsg, "")
            false

        } catch (e: kotlinx.coroutines.CancellationException) {
            // The CALLER's coroutine was cancelled (app teardown / session
            // switch) — propagate silently. This is NOT an engine failure and
            // must not count toward the circuit breaker.
            CrashLogFile.log(TAG, "Init cancelled by caller — not an engine failure")
            throw e
        } catch (e: Throwable) {
            // Catches Errors too (OutOfMemoryError, UnsatisfiedLinkError from
            // native init) — a VLM init failure must never cancel the parent
            // CoroutineScope or break non-VLM features.
            initFailureCount++
            isInitialized = false
            val degrade = initFailureCount >= MAX_INIT_FAILURES
            if (degrade) gracefulFallbackMessage = VLM_UNAVAILABLE_MESSAGE
            val errorMsg = "VLM init failed: ${e.javaClass.simpleName}: ${e.message}" +
                if (degrade) " — $VLM_UNAVAILABLE_MESSAGE" else ""
            Log.e(TAG, errorMsg, e)
            CrashLogFile.logError(TAG, errorMsg, e)
            onError?.invoke(errorMsg, "")
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
     * The backend chain for a tier:
     *  - Tier 1: GPU only (flagship — certified drivers; a failure here is
     *    treated as a real fault, not a tiering issue).
     *  - Tier 2: GPU with catch-fallback to CPU (4 threads) — the mid-range
     *    Mali/OpenCL driver belt.
     *  - Tier 3: CPU directly — never waste 5-10s on a GPU init that will
     *    fail on low-end chipsets.
     */
    private fun backendPlanFor(tier: DeviceTier): List<TierBackendPlan> = when (tier) {
        DeviceTier.TIER_1 -> listOf(
            TierBackendPlan("GPU", Backend.GPU(), CONTEXT_TOKENS_TIER1)
        )
        DeviceTier.TIER_2 -> listOf(
            TierBackendPlan("GPU", Backend.GPU(), CONTEXT_TOKENS_TIER2),
            TierBackendPlan("CPU", cpuBackendForDevice(), CONTEXT_TOKENS_TIER2)
        )
        DeviceTier.TIER_3 -> listOf(
            TierBackendPlan("CPU", cpuBackendForDevice(), CONTEXT_TOKENS_TIER3)
        )
        DeviceTier.TIER_DISABLED -> emptyList()
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
    private fun applyTierExperimentalFlags(tier: DeviceTier) {
        try {
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
                "Experimental flags [tier=$tier]: mtp=${tier == DeviceTier.TIER_1}, " +
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
        modelFile: File,
        step: TierBackendPlan,
        tier: DeviceTier
    ): Boolean {
        return engineInitMutex.withLock {
            CrashLogFile.log(
                TAG,
                "Trying ${step.backendName} backend (tier=$tier, contextTokens=${step.contextTokens})..."
            )
            onStepProgress?.invoke(35, "Preparing my vision...")

            // Drop whatever we can BEFORE the native side allocates KV-cache
            // tensors — on lower-tier hardware every reclaimed MB counts.
            reclaimMemoryBeforeTensorAllocation(aggressive = tier != DeviceTier.TIER_1)

            val timeoutMs =
                if (step.backend is Backend.CPU) CPU_ENGINE_CREATE_TIMEOUT_MS
                else ENGINE_CREATE_TIMEOUT_MS

            try {
                applyTierExperimentalFlags(tier)

                val eng = buildEngineTimed(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = step.backend,
                        visionBackend = step.backend,
                        // Audio encoder runs on CPU — Gemma 4 E2B's audio model is
                        // separate from the text/vision path and loads on demand.
                        audioBackend = Backend.CPU(),
                        maxNumTokens = step.contextTokens,
                        cacheDir = context.cacheDir.path
                    ),
                    timeoutMs
                )

                if (eng == null) {
                    CrashLogFile.logError(
                        TAG,
                        "${step.backendName} build exceeded ${timeoutMs / 1000}s budget — treating as failure"
                    )
                    return@withLock false
                }

                onStepProgress?.invoke(45, "Getting my vision ready...")
                CrashLogFile.log(TAG, "Engine created + initialized [${step.backendName}]")

                engine = eng
                activeBackend = step.backendName
                isInitialized = true
                true

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
                false
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

    /** Image pre-processing dimension cap for the device tier. */
    private fun clampTargetDimension(requested: Int): Int = when (deviceProfile.tier) {
        DeviceTier.TIER_1 -> requested
        DeviceTier.TIER_2 -> minOf(requested, IMAGE_DIMENSION_TIER2)
        else -> minOf(requested, IMAGE_DIMENSION_TIER3)
    }

    private fun buildModelNotFoundError(): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val downloadsFile = downloadsDir?.let { File(it, MODEL_FILE) }
        val externalDir = context.getExternalFilesDir(null)
        val externalFile = externalDir?.let { File(it, MODEL_FILE) }

        val sb = StringBuilder()
        sb.appendLine("$MODEL_FILE not found.")
        sb.appendLine()
        sb.appendLine("Locations checked:")

        if (downloadsFile != null) {
            sb.appendLine("  1. ${downloadsFile.absolutePath}")
            sb.appendLine("     exists=${downloadsFile.exists()}, size=${downloadsFile.length()} bytes")
        } else {
            sb.appendLine("  1. /storage/emulated/0/Download/$MODEL_FILE  (Downloads dir unavailable)")
        }

        if (externalFile != null) {
            sb.appendLine("  2. ${externalFile.absolutePath}")
            sb.appendLine("     exists=${externalFile.exists()}, size=${externalFile.length()} bytes")
        } else {
            sb.appendLine("  2. <app-scoped external dir>/$MODEL_FILE  (dir unavailable)")
        }

        sb.appendLine()
        sb.appendLine("Push via ADB:")
        sb.appendLine("  adb push $MODEL_FILE /storage/emulated/0/Download/")

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
            CrashLogFile.log(TAG, "=== ANALYZE IMAGE ===")
            CrashLogFile.log(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}")

            // 1. Preprocess bitmap — proportional scale to the TIER-ADJUSTED
            //    target dimension (no center-crop; vision-token memory on
            //    lower tiers is bounded by the smaller dimension).
            val effectiveTarget = clampTargetDimension(targetDimension)
            scaledBitmap = preprocessBitmap(bitmap, effectiveTarget)
            CrashLogFile.log(
                TAG,
                "Preprocessed: ${scaledBitmap.width}x${scaledBitmap.height} " +
                    "(target=$effectiveTarget, tier=${deviceProfile.tier})"
            )

            // 2. Encode image to JPEG bytes
            val imageBytes = bitmapToJpegBytes(scaledBitmap)
            CrashLogFile.log(TAG, "JPEG: ${imageBytes.size} bytes")

            // 3. Build the user payload + Gemma 4 turn format.
            //    System directive is folded into the user turn (Gemma has no system role).
            //    User rules come from DynamicPromptBuilder inside the user payload.
            val userPayload = buildUserPayload(prompt, memoryContext)
            val formattedPrompt = buildGemmaTurnPrompt(
                userPayload,
                SYSTEM_DIRECTIVE + perspectiveClauseFor(activeUserLocale)
            )
            CrashLogFile.log(TAG, "Formatted prompt: ${formattedPrompt.take(120)}...")

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
            val response = runConversation(contents, maxTokens, sessionId)
            val elapsed = System.currentTimeMillis() - startTime
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
        CrashLogFile.log(TAG, "=== ANALYZE TEXT (no image) ===")
        val formattedPrompt = buildGemmaTurnPrompt(
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
     * Per the Gemma audio spec the bytes must be raw mono 16 kHz float32
     * PCM (samples in [-1, 1]) with NO WAV header. Audio is charged at
     * 25 tokens per second against the context window (max 30s clip).
     *
     * @param audioBytes Raw 16 kHz mono float32 PCM audio
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
        CrashLogFile.log(TAG, "=== TRANSCRIBE AUDIO (${audioBytes.size} bytes) ===")
        val formattedPrompt = buildGemmaTurnPrompt(prompt, "")
        runConversation(
            contents = Contents.of(
                Content.AudioBytes(audioBytes),
                Content.Text(formattedPrompt)
            ),
            maxTokens = maxTokens,
            sessionId = sessionId
        )
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
    private suspend fun warmUp() = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext
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

            Log.i(TAG, "Engine warm-up completed (text-only) [backend=$activeBackend]")
        } catch (e: Throwable) {
            Log.w(TAG, "Engine warm-up failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Engine State ──────────────────────────────────────────────

    fun isReady(): Boolean = isInitialized && engine != null
    fun getActiveBackend(): String = activeBackend

    /** True when VLM generation is usable — always false on Tier 0 devices. */
    fun isVlmAvailable(): Boolean = isReady()

    /** True when the device is Tier 0 (VLM bypassed; fast-path tools remain). */
    fun isVlmBypassed(): Boolean = deviceProfile.tier == DeviceTier.TIER_DISABLED

    /** Human-readable device tier (for status lines / telemetry). */
    fun getDeviceTierName(): String = deviceProfile.tier.label

    /** Full hardware profile summary (for logs / diagnostics screens). */
    fun getDeviceProfileSummary(): String = profileSummary(deviceProfile)

    /**
     * Check if the model file exists on disk (without initializing the engine).
     * Used to avoid announcing 'downloading' when the model is already present.
     */
    fun isModelOnDisk(): Boolean {
        return resolveModelFile() != null
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
        Log.i(TAG, "resetSession: clearing Engine KV-cache via reinit")
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
                val modelFile = resolveModelFile()
                if (modelFile == null) {
                    Log.e(TAG, "resetSession: model file not found — VLM stays offline")
                    return@launch
                }

                val tier = deviceProfile.tier
                for (step in backendPlanFor(tier)) {
                    if (createEngineGuarded(modelFile, step, tier)) {
                        Log.i(TAG, "resetSession: reinitialized on ${step.backendName} [tier=$tier]")
                        warmUp()
                        return@launch
                    }
                    Log.e(TAG, "resetSession: ${step.backendName} init failed — trying next backend")
                }
                Log.e(TAG, "resetSession: all backends for $tier failed — VLM stays offline (non-VLM features unaffected)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "resetSession reinit failed: ${e.message}", e)
                engine = null
                isInitialized = false
            }
        }
    }

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
     * Resolve model file from the device filesystem.
     *
     * Lookup order:
     *  1. Public Download folder — `/storage/emulated/0/Download/gemma-4-E2B-it.litertlm`
     *  2. App-scoped external files — `context.getExternalFilesDir(null)`
     */
    private fun resolveModelFile(): File? {
        // Tier 1: Public Download folder (most accessible via ADB push)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null && downloadsDir.exists()) {
            val downloadsFile = File(downloadsDir, MODEL_FILE)
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
            val externalFile = File(externalDir, MODEL_FILE)
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
    }

    // ── Companion ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "VlmEngineManager"

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

        // Model configuration — Gemma 4 E2B (2.59 GB) — generic multimodal with vision encoder
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 500L * 1024 * 1024  // 500MB minimum sanity check

        // Image preprocessing — no center-crop, just proportional downscale
        // Gemma 4 handles dynamic aspect ratios natively
        private const val MAX_INPUT_DIMENSION = 512
        const val JPEG_QUALITY = 75

        // Greedy decoding — fast, concise output with minimal latency
        const val TEMPERATURE = 0.1
        const val TOP_K = 1
        const val TOP_P = 1.0
        const val MAX_TOKENS = 35

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
        private const val TEXT_ONLY_SYSTEM_DIRECTIVE =
            "You are Vyze, a fast, friendly visual assistant speaking aloud to a blind user. " +
            "Answer in 1 short spoken sentence. " +
            "Always address the user directly. " +
            "If past conversation turns are provided, refer to them when relevant. " +
            "CRITICAL: NEVER repeat, echo, or quote the user's query or question at the start " +
            "of your response. Begin immediately with the direct answer. " +
            "LANGUAGE MIRRORING: Respond in the language named in the [OUTPUT LANGUAGE] " +
            "tag of the user's instructions — the tag is the authority on the answer " +
            "language. Even when the query text itself reads like English, the tag names " +
            "the user's actual spoken language — answer in the tag's language. If no tag " +
            "is present, detect the language of the user's query and respond strictly in " +
            "that exact same language (Malay query -> Malay response, English query -> " +
            "English response, Chinese query -> Chinese response); Never revert to default " +
            "English if the user speaks another language. " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "Write plain flowing sentences only. " +
            "Use clear punctuation (periods and commas) for spoken delivery. " +
            "Do not mention that you are an AI or offline."

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
            "Answer in 1 short spoken sentence about what you see. " +
            "Always address the user directly. " +
            "Refer to past conversation turns when they are provided. " +
            "CRITICAL: NEVER repeat, echo, or quote the user's query or question at the " +
            "start of your response. Begin immediately with the direct description or answer. " +
            "Always lead directly with the answer to the user's question without any " +
            "introductory location preamble. " +
            "If asked what color this is, reply directly with the color ('That is a red mug.'). " +
            "If asked to read text, reply directly with the text ('It says Organic Milk.'). " +
            "Only mention spatial position ('in front of you', 'to your left') when the user " +
            "specifically asks WHERE an object is located. " +
            "NEVER start a sentence with 'You are in front of', 'You are looking at', " +
            "'You are facing', or 'In front of you is' — vary the opening with the question. " +
            "IMPORTANT EXCEPTION: when OCR text is provided in the prompt, the one-sentence " +
            "rule does NOT apply — read the OCR text in full, in reading order, and do not " +
            "summarize, skip, or stop early. " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "Write plain spoken sentences only. " +
            "LANGUAGE MIRRORING: Respond in the language named in the [OUTPUT LANGUAGE] " +
            "tag of the user's instructions — the tag is the authority on the answer " +
            "language. Even when the query text itself reads like English, the tag names " +
            "the user's actual spoken language — answer in the tag's language. If no tag " +
            "is present, detect the language of the user's query and respond strictly in " +
            "that exact same language (Malay query -> Malay response, English query -> " +
            "English response, Chinese query -> Chinese response); Never revert to default " +
            "English if the user speaks another language. " +
            "On follow-up turns answer only what is new, in the tag's language, and never " +
            "speak the user's question back to them. " +
            "Describe what you see directly without cross-translating or outputting " +
            "internal reasoning chains."

        /**
         * Second-person spatial directive, emitted in the ACTIVE language by
         * [perspectiveClauseFor]. English carries the full never-say rule;
         * the Malay and Chinese translations carry the same second-person
         * binding in their own script so a 2B model never sees conflicting
         * English persona examples when mirroring ms/zh output.
         */
        private const val PERSPECTIVE_EN =
            "Always address the user directly in the second person ('you', 'your', 'in front of you'). " +
            "NEVER say 'in front of me' or 'to my left' — always describe positions relative to the " +
            "user ('in front of you', 'to your left', 'at 12 o'clock'). " +
            "Mention spatial position only when the question asks WHERE something is, and then " +
            "state the object first ('Your cup is directly to your right.'). NEVER begin a sentence " +
            "with 'You are in front of', 'You are looking at', 'You are facing', or 'In front of you is'."
        private const val PERSPECTIVE_MS =
            "Sentiasa rujuk pengguna dalam kata ganti nama kedua ('anda', 'di hadapan anda'). " +
            "JANGAN sesekali kata 'di hadapan saya' atau 'di sebelah kiri saya' — sentiasa nyatakan " +
            "kedudukan relatif kepada pengguna ('di hadapan anda', 'di sebelah kiri anda', 'pukul 12'). " +
            "Sebut kedudukan hanya apabila soalan bertanya DI MANA sesuatu berada, dan nyatakan " +
            "objek dahulu ('Cawan anda berada di sebelah kanan anda.'). JANGAN mulakan setiap ayat " +
            "dengan frasa yang sama, seperti 'Di hadapan anda ialah', 'Anda berada di hadapan', " +
            "atau 'Anda sedang melihat'."
        private const val PERSPECTIVE_ZH =
            "始终使用第二人称直接称呼用户（'你'、'在你面前'）。" +
            "绝不要说'在我面前'或'在我左边'——始终以用户为基准描述位置（'在你面前'、'在你的左边'、'12点钟方向'）。" +
            "只有当问题询问位置时才提到方位，并且先说物体——例如'您的杯子就在您右手边'。" +
            "绝不要每一句都用同样的开头，如'你面前是'、'你正在看'或'你在……的前面'。"

        // ── Mid-Tier / Low-RAM Thresholds ───────────────────────
        /** Minimum free device RAM (MB) required to attempt model init on standard devices. */
        private const val MIN_RAM_STANDARD_MB = 1200L
        /** Minimum free device RAM (MB) required on devices flagged as low-RAM. */
        private const val MIN_RAM_LOW_RAM_DEVICE_MB = 800L
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
        /** Reported-RAM floor below which VLM generation is bypassed (nominal 4 GB). */
        private const val VLM_BYPASS_TOTAL_RAM_MB = 3400L

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

        /** Image pre-processing dimension cap per tier (vision-token memory ~quadratic). */
        private const val IMAGE_DIMENSION_TIER2 = 384
        private const val IMAGE_DIMENSION_TIER3 = 256

        /** Visual token budget caps (ExperimentalFlags) for image encoding on lower tiers. */
        private const val VISUAL_TOKEN_BUDGET_TIER2 = 320
        private const val VISUAL_TOKEN_BUDGET_TIER3 = 192

        /** CPU threads for the CPU-fallback backend (Tier 2 fallback / Tier 3). */
        private const val CPU_FALLBACK_THREADS = 4

        /** Consecutive init failures before the engine degrades gracefully. */
        private const val MAX_INIT_FAILURES = 3

        /** Hard budget for ONE GPU Engine build + initialize() (2.59 GB model mmap needs time). */
        private const val ENGINE_CREATE_TIMEOUT_MS = 90_000L
        /** CPU-backend builds get a shorter budget (no GPU driver stall is possible). */
        private const val CPU_ENGINE_CREATE_TIMEOUT_MS = 60_000L
        /** Max wait for a timed-out engine build to materialize before it is closed. */
        private const val PENDING_BUILD_DRAIN_MS = 60_000L

        /** Friendly fallback (spoken as TTS) when VLM is bypassed on < ~4 GB devices. */
        private const val VLM_BYPASS_MESSAGE =
            "This device's memory is too small for visual AI. " +
                "Text reading, color and light detection still work."
        /** Friendly fallback after repeated VLM init failures — fast-path tools stay operational. */
        private const val VLM_UNAVAILABLE_MESSAGE =
            "Visual AI is unavailable on this device. " +
                "Text reading, color and light detection still work."

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

/** Hardware classification of the running device (detected once, cached). */
private enum class DeviceTier(val label: String) {
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

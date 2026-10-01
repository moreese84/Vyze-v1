package com.vyze.app.ui.delegates
import com.vyze.app.device.FlashlightManager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Handles all CameraX lifecycle and setup operations.
 *
 * ## Demand-Driven Frame Architecture (battery redesign, Phase 2 Step A)
 * The analyzer NO LONGER decodes frames continuously. While the camera is
 * open but idle, each delivered ImageProxy is used ONLY for the throttled
 * auto-torch luminance sample (reading the Y plane in-place — no Bitmap
 * allocation, no YUV→JPEG→ARGB round-trip) and then closed immediately.
 * The previous build decoded ~15 Bitmaps per second (~2.5 MB each) into a
 * rolling buffer that a user-triggered snapshot pulled from at most a few
 * times a minute — constant CPU, ISP and allocation-churn cost for stale
 * work. The 1 ms visual-similarity scene gate still runs in continuous
 * mode, but it now runs against a frame decoded at the moment of capture
 * instead of a continuously-refreshed buffer.
 *
 * Full YUV→ARGB decode happens ONLY when [takeSnapshot] demands a frame:
 * the demand registers a waiter and the ANALYZER thread decodes the NEXT
 * delivered frame exclusively for it, handing the bitmap straight to the
 * caller — one decode per capture, zero shared-buffer copies, and the
 * snapshot always reflects what the camera saw at-or-after the gesture.
 *
 * The old Thread.sleep(20) busy-wait is gone entirely: [takeSnapshot] is
 * fully ASYNC (registers a waiter, never blocks the caller — the old
 * implementation could sleep up to ~600 ms on the main thread on its
 * cold-start path), the analyzer wakes the waiter when the frame arrives,
 * and a main-handler timeout fails the capture if no frame comes.
 */
class CameraSetupDelegate {

    private val TAG = "CameraSetupDelegate"

    var preview: Preview? = null
        private set
    var camera: Camera? = null
        private set
    var cameraProvider: ProcessCameraProvider? = null
        private set

    private lateinit var flashlightManager: FlashlightManager

    /** Analysis executor — single thread; decodes happen here on demand. */
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Main handler — waiter timeout scheduling only. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether the analyzer is currently bound (is it producing frames?). */
    @Volatile
    private var analyzerBound = false

    /**
     * Monotonically increasing counter of frames DELIVERED by the analyzer.
     * Drives the luminance-sample throttle.
     */
    @Volatile
    private var frameCounter = 0L

    // ── Capture Waiters ────────────────────────────────────────────
    // takeSnapshot() registers a waiter; the ANALYZER thread serves it from
    // the next delivered frame (freshness guaranteed by ordering — the
    // waiter is registered before the frame that serves it arrives). FIFO
    // order when several captures queue up (e.g. a voice query barging in
    // front of a continuous tick): one decode per frame, oldest waiter first.

    private class CaptureWaiter {
        /** The decoded frame — set by the analyzer right before completion. */
        @Volatile
        var result: Bitmap? = null

        /** Decode attempts used (transient OOM/recycle-race failures retry). */
        var attempts = 0

        /** Uniform frames this waiter has been pushed back by (readiness gate). */
        var readinessDrops = 0

        /** Distinct failure message (set before completion, read on delivery). */
        var failure: String? = null

        /** Signalled (result set) or failed by exactly one party. */
        val done = CompletableDeferred<Unit>()

        /** Scheduled timeout — cancelled when the waiter is served. */
        var timeoutRunnable: Runnable? = null
    }

    private val captureWaiters = ArrayDeque<CaptureWaiter>()

    /** Readiness verdict for a delivered frame (see the gate in the analyzer). */
    enum class FrameReadiness { READY, TOO_DARK, UNIFORM }

    // ── High-Resolution Still Capture (OCR) ────────────────────────
    // A bound ImageCapture use case gives text queries a full-resolution
    // JPEG still (~2400x1350 vs the 960x720 analyzer stream). The analyzer
    // stream physically cannot hold a page of A4 text — at reading distance
    // its glyphs fall below ML Kit's reliable detection floor, which is why
    // long letters/documents read back garbled or empty. Scene, tap and
    // continuous queries never touch this use case, so their latency and
    // battery profile are unchanged.
    private var imageCapture: ImageCapture? = null

    // ── Auto-Torch Luminance Detection ────────────────────────────
    // REQUIRES the analyzer to keep delivering frames — so the analyzer
    // stays bound even in demand mode. Cost per delivered frame is now
    // tiny: an in-place Y-plane read (no Bitmap, no YUV->JPEG->ARGB
    // round-trip), only every Nth frame. This is the ONLY per-frame work
    // while idle.

    /** Frame counter at last luminance check — skips frames to reduce CPU load. */
    @Volatile
    private var lastLuminanceCheckFrame = 0L

    /** Hysteresis state — prevents rapid torch toggling at boundary light levels. */
    @Volatile
    private var isDarkEnvironment = false

    companion object {
        /**
         * Waiter timeout: if no analyzer frame arrives within this window the
         * capture fails instead of hanging (camera stall / unbind race).
         * Generous vs the ~33 ms frame period — covers analyzer backpressure
         * on mid-tier devices plus up to [DECODE_ATTEMPTS] decode retries.
         */
        private const val WAITER_TIMEOUT_MS = 1000L

        /**
         * Luminance-sample throttle (the only per-frame work now). Previously
         * this throttled Bitmap decodes; it now throttles Y-plane samples.
         */
        private const val DECODE_THROTTLE = 2

        /**
         * Decode attempts per waiter when a decode fails under memory
         * pressure (recycle race / OOM) — each retry is served by the NEXT
         * delivered frame.
         */
        private const val DECODE_ATTEMPTS = 3

        // ── Auto-Torch Luminance Thresholds ─────────────────────
        /** Average Y-plane brightness below this → torch ON (0-255). */
        private const val DARK_THRESHOLD = 35
        /** Average Y-plane brightness above this → torch OFF (0-255). */
        private const val BRIGHT_THRESHOLD = 65
        /** Check luminance every N frames (~600ms at 30fps) to minimize CPU load. */
        private const val LUMINANCE_CHECK_INTERVAL = 20L

        // ── Capture-Readiness Gate (fix #1, 2026-10-01) ──────────
        // Device evidence: 「您面前是纯绿色的背景」×2 — a snapshot taken in
        // the surface-init window (bind/resume) served a UNIFORM GREEN frame
        // to the VLM, which dutifully described a green background nobody
        // was looking at. The gate delays serving a capture until the
        // camera delivers a frame with real image CONTENT.
        /** Extra frames a waiter may be pushed back while the surface warms up. */
        private const val READINESS_RETRIES = 3
        /** Subsample grid for stats (every Nth pixel per axis) — cheap, sufficient. */
        private const val READY_SAMPLE_STEP = 4
        /** Variance below this on the sampled Y plane = no image content.
         *  Real scenes (even walls) carry sensor noise well above std≈5. */
        private const val UNIFORM_VARIANCE_MAX = 25.0
        /** Mean luma below this (textured) = dark scene — SERVED, logged only:
        *  the auto-torch system owns brightness, and dark scenes legitimately
        *  contain content. Never refuse a dark frame. */
        private const val READINESS_DARK_MEAN = 10

        /**
         * Classify a frame's readiness from its Y plane statistics.
         * PURE — same stats, same verdict; JVM-tested in CameraReadinessTest.
         *
         *  - UNIFORM: variance ≤ [UNIFORM_VARIANCE_MAX] — no image content
         *    (the surface-init window that produced the green-background
         *    hallucinations). The ONLY verdict the gate acts on.
         *  - TOO_DARK: mean luma < [READINESS_DARK_MEAN] with content — logged
         *    for diagnostics, never refused (the auto-torch owns brightness;
         *    a dark scene is still a scene).
         *  - READY: everything else — real content at usable brightness.
         */
        fun readinessVerdict(meanLuma: Double, variance: Double): FrameReadiness = when {
            variance <= UNIFORM_VARIANCE_MAX -> FrameReadiness.UNIFORM
            meanLuma < READINESS_DARK_MEAN -> FrameReadiness.TOO_DARK
            else -> FrameReadiness.READY
        }
    }

    /**
     * Initializes the camera provider and binds use cases.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        flashlightMgr: FlashlightManager
    ) {
        this.flashlightManager = flashlightMgr

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(context, lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Rebinds camera use cases (called on resume after pause).
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun rebindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        if (cameraProvider == null) return
        bindUseCases(context, lifecycleOwner, previewView)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindUseCases(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .build()

        // ImageAnalysis — delivers frames at camera framerate, but the
        // analyzer body below is now nearly free: Y-plane luminance sample
        // at most, proxy closed immediately. An explicit high target
        // resolution is CRITICAL for OCR (see whitepaper): small print on
        // tiny products drops below what ML Kit can detect at CameraX's
        // ~640x480 default. 960x720 keeps ~2.2x the pixel density while
        // halving the memory pressure of 1280x960.
        // NOTE: must NOT be combined with setTargetAspectRatio on the same
        // use case — CameraX throws IllegalArgumentException.
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(960, 720))
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // High-res still use case for OCR text reads. BOUND WITH the analyzer
        // in one bindToLifecycle call: CameraX guarantees a simultaneous
        // Preview + ImageAnalysis + ImageCapture combination (the default
        // supported use-case level), while re-binding later to add it would
        // race the analyzer and risk dropping frames mid-capture. 4:3 keeps
        // the full sensor field of view (16:9 crops it), so nothing at the
        // top or bottom of a document page is lost.
        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(2400, 1350))
            .setTargetRotation(previewView.display.rotation)
            .build()
        this.imageCapture = imageCaptureUseCase

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                // ── LUMINANCE SAMPLE (the ONLY idle per-frame work) ──
                // In-place Y-plane read: no Bitmap, no NV21 copy, no JPEG
                // encode/decode.
                if (frameCounter % DECODE_THROTTLE == 0L &&
                    frameCounter - lastLuminanceCheckFrame >= LUMINANCE_CHECK_INTERVAL
                ) {
                    lastLuminanceCheckFrame = frameCounter
                    val brightness = sampleLuminance(imageProxy)
                    if (brightness != null) {
                        updateAutoTorch(brightness)
                    }
                }

                frameCounter++

                // ── DEMAND SERVE ─────────────────────────────────
                // A capture demanded a frame — decode THIS one for the
                // oldest waiter. FIFO: one decode per proxy. A timed-out
                // waiter is removed from the deque before completion, so
                // it is never served late.
                val waiter: CaptureWaiter? = synchronized(captureWaiters) {
                    while (captureWaiters.isNotEmpty()) {
                        val head = captureWaiters.removeFirst()
                        if (head.done.isCompleted) continue   // timed out already
                        return@synchronized head
                    }
                    null
                }
                if (waiter != null) {
                    // ── READINESS GATE ────────────────────────
                    // A frame with no image content (uniform surface-init
                    // window) must not be served to the VLM — it produces
                    // the green-background hallucination class. Push the
                    // waiter back to be served by a LATER frame, up to
                    // READINESS_RETRIES, then serve anyway (never hang).
                    // Dark frames are SERVED (only logged): the auto-torch
                    // system owns brightness, and dark scenes legitimately
                    // contain content.
                    when (frameReadiness(imageProxy)) {
                        FrameReadiness.READY -> {}
                        FrameReadiness.TOO_DARK -> {
                            Log.d(TAG, "Readiness: dark frame (serving anyway)")
                        }
                        FrameReadiness.UNIFORM -> {
                            if (waiter.readinessDrops < READINESS_RETRIES) {
                                waiter.readinessDrops++
                                synchronized(captureWaiters) {
                                    captureWaiters.addFirst(waiter)
                                }
                                Log.d(TAG, "Readiness: uniform frame — retry ${waiter.readinessDrops}/$READINESS_RETRIES")
                                return@setAnalyzer
                            }
                            Log.w(TAG, "Readiness: still uniform after $READINESS_RETRIES — serving anyway")
                        }
                    }
                    val bitmap = doDecodeFrame(imageProxy)
                    if (bitmap != null) {
                        serveWaiter(waiter, bitmap)
                    } else if (waiter.attempts + 1 < DECODE_ATTEMPTS) {
                        // Transient decode failure (recycle race / OOM) —
                        // requeue to be served by the NEXT frame.
                        waiter.attempts++
                        synchronized(captureWaiters) { captureWaiters.addFirst(waiter) }
                    } else {
                        failWaiter(waiter)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Frame analysis error: ${e.message}")
            } finally {
                // ── PROXY CLOSE — unconditional, all paths ─────────
                // The single guarantee that prevents surface-buffer leaks:
                // every ImageProxy delivered here is closed exactly once,
                // whether it was luminance-sampled, decoded for a waiter,
                // or discarded because nobody demanded it.
                try {                imageProxy.close() } catch (_: Throwable) {}
            }
        }

        analyzerBound = true
        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalysis, imageCaptureUseCase
            )
            flashlightManager.camera = camera
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            analyzerBound = false
        }
    }

    // ── Snapshot (Demand-Driven, Fully Async) ──────────────────────

    /**
     * Take a snapshot on demand — ASYNC, never blocks the caller (gesture
     * handlers and the continuous-mode timer both invoke this on the main
     * thread). The analyzer decodes the NEXT delivered frame exclusively
     * for this capture and hands it to [onBitmap]; no shared rolling
     * buffer, no defensive copy — the bitmap is the caller's to recycle.
     *
     * Wait latency: one frame period (~33 ms) vs the old rolling-buffer hit
     * (~0-20 ms) — negligible against the multi-second VLM run that
     * follows, in exchange for eliminating ~15 Bitmap decodes/second idle.
     *
     * Callbacks: [onBitmap]/[onError] run on the analysis executor thread
     * (callers already route UI work through runOnUiThread). Exactly one
     * of them fires per call, at most [WAITER_TIMEOUT_MS] later.
     *
     * @param onCaptureStart  Optional synchronous callback fired before the
     *   frame wait starts (capture-start timestamping).
     * @param onBitmap Callback with a private ARGB bitmap.
     * @param onError  Callback with an error message.
     */
    fun takeSnapshot(
        onCaptureStart: (() -> Unit)? = null,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        onCaptureStart?.invoke()

        if (!analyzerBound || cameraProvider == null) {
            // Camera unbound (release/destroy in flight): frames will not
            // come — fail fast instead of waiting on a signal that never
            // arrives. (The old code busy-polled here for up to ~600 ms.)
            onError("Camera unavailable")
            return
        }

        val waiter = CaptureWaiter()

        // Delivery: fires on whichever thread completes the waiter (the
        // analyzer on success, the main handler on timeout), then POSTS the
        // callback to the MAIN thread — preserving the old takeSnapshot's
        // delivery contract (onBitmap/onError ran on main). The analyzer
        // thread stays free to process the next frame immediately.
        waiter.done.invokeOnCompletion {
            waiter.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            val bitmap = waiter.result
            val failure = waiter.failure
            mainHandler.post {
                try {
                    if (bitmap != null && !bitmap.isRecycled) {
                        onBitmap(bitmap)
                    } else {
                        bitmap?.recycle()
                        onError(failure ?: "Camera frame unavailable")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "takeSnapshot callback error: ${e.message}")
                    bitmap?.recycle()
                }
            }
        }

        // Timeout: if no frame arrives in time, fail the capture. The
        // waiter is removed from the deque first so the analyzer can no
        // longer serve it (no double-completion race).
        val timeoutRunnable = Runnable {
            var timedOut = false
            synchronized(captureWaiters) {
                if (captureWaiters.remove(waiter)) {
                    timedOut = true
                }
            }
            if (timedOut) failWaiter(waiter)
        }
        waiter.timeoutRunnable = timeoutRunnable

        synchronized(captureWaiters) { captureWaiters.addLast(waiter) }
        mainHandler.postDelayed(timeoutRunnable, WAITER_TIMEOUT_MS)
    }

    /** Complete a waiter with a frame (analyzer thread). */
    private fun serveWaiter(waiter: CaptureWaiter, bitmap: Bitmap) {
        synchronized(captureWaiters) { captureWaiters.remove(waiter) }
        waiter.result = bitmap
        waiter.done.complete(Unit)
    }

    /** Fail a waiter (timeout or exhausted decode attempts). */
    private fun failWaiter(waiter: CaptureWaiter) {
        waiter.done.complete(Unit)
    }

    // ── Capture-Readiness Gate (pure fns) ───────────

    /**
     * Mean and variance of the frame's Y plane on a subsampled grid
     * (every [READY_SAMPLE_STEP]-th pixel per axis) — O(w·h/step²), no
     * allocation beyond the accumulator. Mirrors the Y-plane read of
     * [sampleLuminance] and tolerates the same stride/layout quirks.
     * Returns null when the plane is unreadable (serve the frame — a
     * diagnostics miss must never stall a capture).
     */
    private fun frameStats(imageProxy: ImageProxy): Pair<Double, Double>? {
        val plane = try {
            imageProxy.planes[0].buffer
        } catch (_: Throwable) {
            return null
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        val rowStride = imageProxy.planes[0].rowStride
        val pixelStride = imageProxy.planes[0].pixelStride
        var y = 0
        while (y < imageProxy.height) {
            var x = 0
            val rowBase = y * rowStride
            while (x < imageProxy.width) {
                val idx = rowBase + x * pixelStride
                if (idx < plane.capacity()) {
                    val v = plane.get(idx).toInt() and 0xFF
                    sum += v
                    sumSq += v * v
                    n++
                }
                x += READY_SAMPLE_STEP
            }
            y += READY_SAMPLE_STEP
        }
        if (n == 0) return null
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return mean to variance.coerceAtLeast(0.0)
    }

    /** Verdict for this frame; unreadable planes default to READY (serve). */
    private fun frameReadiness(imageProxy: ImageProxy): FrameReadiness {
        val stats = frameStats(imageProxy) ?: return FrameReadiness.READY
        return readinessVerdict(stats.first, stats.second)
    }

    // ── High-Resolution Still Capture (OCR text reads) ────────────

    /**
     * Capture a FULL-RESOLUTION still and hand it to [onBitmap] — used ONLY
     * by explicit text-reading queries where small glyph detail decides
     * whether ML Kit can read a document at all. Fully async, main-thread
     * callback delivery (same contract as [takeSnapshot]).
     *
     * The analyzer stream (960x720) is fine for scene/OCR-at-arm's-length
     * work but cannot resolve a full page of print; the still (~2400px)
     * roughly quadruples glyph height at reading distance.
     *
     * Fallback: if no ImageCapture use case is bound (binding failed,
     * unbind race) or the hardware take fails, the error callback fires
     * and the CALLER (CameraFragment) falls back to the analyzer snapshot
     * — one degraded OCR read is better than a failed query.
     *
     * @param onCaptureStart Optional synchronous callback fired before the
     *   capture starts (capture-start timestamping, mirrors [takeSnapshot]).
     */
    fun takeHighResSnapshot(
        onCaptureStart: (() -> Unit)? = null,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        onCaptureStart?.invoke()

        val capture = imageCapture
        if (capture == null || camera == null) {
            onError("High-res capture unavailable")
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context ?: return),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Rotation is applied here so ML Kit sees upright text.
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    try {
                        val rotated = if (rotation != 0 && bitmap != null) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                .also { if (it !== bitmap) bitmap.recycle() }
                        } else bitmap
                        if (rotated != null) {
                            onBitmap(rotated)
                        } else {
                            onError("Still frame decode failed")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "High-res rotation failed: ${e.message}")
                        bitmap?.recycle()
                        onError("Still frame decode failed")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "High-res capture failed: ${exception.message}")
                    onError("High-res capture failed")
                }
            }
        )
    }

    // ── Frame Decode ───────────────────────────────────────────────

    /**
     * Convert an ImageProxy (YUV_888) to an ARGB_8888 Bitmap.
     * Handles rotation based on imageProxy.imageInfo.rotationDegrees.
     *
     * Runs on the analysis executor only. Never recycles the proxy —
     * the analyzer's finally block owns that.
     */
    private fun doDecodeFrame(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            // Y plane
            yBuffer.get(nv21, 0, ySize)
            // VU plane (interleaved for NV21)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, out)
            val jpegBytes = out.toByteArray()

            var bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            // Apply rotation — reuse the Matrix; allocating one per decode
            // is pure garbage pressure.
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = ROTATION_MATRIX
                matrix.reset()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                }
                bitmap = rotated
            }

            bitmap
        } catch (e: Throwable) {
            Log.w(TAG, "YUV conversion error: ${e.message}")
            null
        }
    }

    /** Reusable rotation matrix (single-threaded analyzer access only). */
    private val ROTATION_MATRIX = Matrix()

    /**
     * Reusable luminance row buffer (single-threaded analyzer access only).
     * Grown lazily to the largest row stride seen — removes one ByteArray
     * allocation per luminance sample (~every 600 ms while idle).
     */
    private var luminanceRowBuffer: ByteArray = ByteArray(0)

    // ── Auto-Torch Luminance ───────────────────────────────────────

    /**
     * Sample average luminance from the Y plane IN-PLACE (no Bitmap, no
     * buffer copy beyond one row at a time). Returns null on failure.
     */
    private fun sampleLuminance(imageProxy: ImageProxy): Int? {
        return try {
            val planes = imageProxy.planes
            if (planes.isEmpty()) return null

            val yBuffer = planes[0].buffer
            val yRowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            // Sample every 4th pixel for speed — enough for average brightness
            val sampleStep = 4
            var sum = 0L
            var count = 0

            val rowBuffer = if (luminanceRowBuffer.size >= yRowStride) {
                luminanceRowBuffer
            } else {
                ByteArray(yRowStride).also { luminanceRowBuffer = it }
            }
            for (row in 0 until height step sampleStep) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(rowBuffer, 0, minOf(yRowStride, rowBuffer.size))
                for (col in 0 until width step sampleStep * pixelStride) {
                    val idx = col * pixelStride
                    if (idx < rowBuffer.size) {
                        sum += (rowBuffer[idx].toInt() and 0xFF)
                        count++
                    }
                }
            }

            if (count == 0) null else (sum / count).toInt()
        } catch (e: Throwable) {
            Log.w(TAG, "Luminance sample error: ${e.message}")
            null
        }
    }

    /**
     * Hysteresis: ON < 35, OFF > 65, dead zone 35-65 prevents oscillation.
     */
    private fun updateAutoTorch(avgBrightness: Int) {
        val shouldBeOn = avgBrightness < DARK_THRESHOLD
        val shouldBeOff = avgBrightness > BRIGHT_THRESHOLD

        val newDarkState = if (shouldBeOn) true else if (shouldBeOff) false else isDarkEnvironment

        if (newDarkState != isDarkEnvironment) {
            isDarkEnvironment = newDarkState
            flashlightManager.autoTorch(isDarkEnvironment)
            Log.d(TAG, "Auto-torch: brightness=$avgBrightness/255, torch=${if (isDarkEnvironment) "ON" else "OFF"}")
        }
    }

    /**
     * Last known dark/bright environment state, maintained by the auto-torch
     * luminance checks. Used by the long-press light check.
     */
    fun isEnvironmentDark(): Boolean = isDarkEnvironment

    /**
     * Updates the target rotation for the image analysis.
     */
    fun updateRotation(display: Display) {
        // Rotation is applied at bind time; rebind needed for runtime changes
    }

    /** Reference to the application context. */
    private var context: Context? = null

    /** Set context for camera provider initialization. */
    fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Releases the camera and unbinds all use cases.
     *
     * Pending capture waiters are failed FAST — a capture in flight during
     * unbind reports an error instead of hanging on a signal that will
     * never arrive.
     */
    fun releaseCamera() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        if (::flashlightManager.isInitialized) {
            flashlightManager.camera = null
        }
        analyzerBound = false
        frameCounter = 0L
        lastLuminanceCheckFrame = 0L
        // Fail all pending waiters — unbind means no more frames will come.
        // (Their invokeOnCompletion handlers deliver onError / cancel the
        // timeout runnables.)
        val pending: List<CaptureWaiter> = synchronized(captureWaiters) {
            val list = captureWaiters.toList()
            captureWaiters.clear()
            list
        }
        pending.forEach { failWaiter(it) }
    }

    /**
     * Fully destroys the camera provider and analysis executor.
     */
    fun destroy() {
        releaseCamera()
        cameraProvider = null
        analysisExecutor.shutdownNow()
    }
}

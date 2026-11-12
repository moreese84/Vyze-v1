package com.vyze.app.ui.delegates
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.device.GestureDetectorHelper
import com.vyze.app.vision.ColorAnalyzer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.vyze.app.R
import com.vyze.app.data.ScanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles all gesture-to-action routing for the camera screen.
 *
 * Gesture Vocabulary:
 * - Single tap → Object Detection readout (with touch coordinate for hit-test)
 * - Double tap → OCR text recognition
 * - Long press → Scene Summary
 * - Triple tap → Color Analysis (center ROI)
 * - Triple tap + hold → Emergency SOS → opens dialer
 */
class GestureRouter(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val hapticManager: HapticManager,
    private val colorAnalyzer: ColorAnalyzer = ColorAnalyzer(),
    private val scanRepository: ScanRepository,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    private val TAG = "GestureRouter"

    /** Callback for single-tap with (x, y) in view coordinates. */
    var onSingleTapAction: (x: Float, y: Float) -> Unit = { _, _ -> }

    /** Callback for double-tap (OCR readout). */
    var onDoubleTapAction: () -> Unit = {}

    /** Callback for long-press (Scene Summary). */
    var onLongPressAction: () -> Unit = {}

    private lateinit var gestureDetectorHelper: GestureDetectorHelper
    private lateinit var gestureDetector: GestureDetector
    private var isAttached = false
    private var attachedView: View? = null

    /**
     * Structured scope for color analysis + scan persistence (#88):
     * replaces the fire-and-forget MainScope so in-flight work dies
     * with the view instead of persisting past detach.
     */
    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Attaches the gesture router to a container view.
     */
    fun attach(containerView: View) {
        if (isAttached) return
        isAttached = true
        attachedView = containerView

        gestureDetectorHelper = GestureDetectorHelper(
            onSingleTap = { e ->
                hapticManager.vibrateTap()
                onSingleTapAction(e.x, e.y)
            },
            onDoubleTap = {
                hapticManager.vibrateDoubleTap()
                onDoubleTapAction()
            },
            onLongPress = {
                hapticManager.vibrateLongPress()
                onLongPressAction()
            },
            onTripleTap = {
                hapticManager.vibrateDoubleTap()
                performColorAnalysis(containerView)
            },
            onTripleTapHold = {
                hapticManager.vibrateWarning()
                hapticManager.vibrateWarning()
                triggerEmergencySOS()
            }
        )

        gestureDetector = GestureDetector(context, gestureDetectorHelper)
        containerView.setOnTouchListener { _, event ->
            gestureDetectorHelper.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true // Consume the event so child overlays don't steal it
        }
    }

    /**
     * Detaches the gesture router, cleaning up listeners.
     */
    fun detach() {
        if (!isAttached) return
        isAttached = false
        // The view holds the listener lambda, which captures this router
        // (Activity context + fragment callbacks) — release all of it (#58).
        attachedView?.setOnTouchListener(null)
        attachedView = null
        onSingleTapAction = { _, _ -> }
        onDoubleTapAction = {}
        onLongPressAction = {}
        // In-flight color analysis dies with the view (#88).
        routerScope.cancel()
        gestureDetectorHelper.reset()
    }

    // ── Color Analysis ────────────────────────────────────────────────

    private fun performColorAnalysis(containerView: View) {
        ttsManager.speakImmediate(
            ttsManager.localized(
                context.getString(R.string.color_analyzing),
                "Menganalisis warna...",
                "正在分析颜色"
            )
        )

        if (containerView.width < 4 || containerView.height < 4) {
            ttsManager.speak(
                context.getString(R.string.color_result, context.getString(R.string.color_unknown))
            )
            return
        }

        // Center ROI only (#88): the analyzer samples the middle 20%, so
        // a 96px crop replaces the full-frame alloc + draw. View.draw must
        // stay on this (main) thread — only the pixels leave it.
        val roiBitmap = try {
            Bitmap.createBitmap(COLOR_ROI_PX, COLOR_ROI_PX, Bitmap.Config.ARGB_8888).also { roi ->
                val (tx, ty) = companionRoiTranslate(containerView.width, containerView.height, COLOR_ROI_PX)
                val canvas = android.graphics.Canvas(roi)
                canvas.translate(tx, ty)
                containerView.draw(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Color analysis failed", e)
            speakColorFailure()
            return
        }
        routerScope.launch(Dispatchers.Default) {
            try {
                val colorName = colorAnalyzer.analyzeCenterColor(context, roiBitmap)
                withContext(Dispatchers.Main) {
                    val announcement = context.getString(R.string.color_result, colorName)
                    ttsManager.speak(announcement)
                }
                scanRepository.saveColorScan(colorName)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Color analysis failed", e)
                withContext(Dispatchers.Main) { speakColorFailure() }
            } finally {
                roiBitmap.recycle()
            }
        }
    }

    private fun speakColorFailure() {
        ttsManager.speakImmediate(
            ttsManager.localized(
                "Color analysis failed.",
                "Analisis warna gagal.",
                "颜色分析失败。"
            )
        )
    }

    // ── Emergency SOS ─────────────────────────────────────────────────

    private fun triggerEmergencySOS() {
        ttsManager.speakImmediate(
            ttsManager.localized(
                context.getString(R.string.sos_activated),
                "Mod kecemasan diaktifkan. Membuka dialer.",
                "紧急模式已激活，正在打开拨号器。"
            )
        )

        mainHandler.postDelayed({
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$SOS_EMERGENCY_NUMBER")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open dialer for SOS", e)
                ttsManager.speakImmediate("Could not open dialer.")
            }
        }, SOS_DIAL_DELAY_MS)
    }

    companion object {
        const val SOS_EMERGENCY_NUMBER = "999"
        const val SOS_DIAL_DELAY_MS = 1500L

        /** Center-crop edge for triple-tap color analysis (#88). */
        private const val COLOR_ROI_PX = 96

        /**
         * Canvas translate that centers a [roiPx] crop on a
         * [viewW]×[viewH] view. Pure — JVM-pinned (GestureRouterRoiTest).
         */
        internal fun companionRoiTranslate(viewW: Int, viewH: Int, roiPx: Int): Pair<Float, Float> =
            Pair((roiPx - viewW) / 2f, (roiPx - viewH) / 2f)
    }
}

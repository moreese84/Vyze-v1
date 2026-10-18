package com.vyze.app.device

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent

/**
 * Helper class that wraps GestureDetector.SimpleOnGestureListener
 * for full-screen accessibility gestures.
 *
 * Gesture vocabulary:
 *  - Single Tap: Triggers Object Detection readout
 *  - Double Tap: Triggers ML Kit Text Recognition (OCR) readout
 *  - Long Press: Triggers Scene Summary readout
 *  - Triple Tap: Triggers Color Analysis readout
 *  - Triple Tap + Hold: Triggers Emergency SOS
 */
class GestureDetectorHelper(
    private val onSingleTap: (MotionEvent) -> Unit = { _ -> },
    private val onDoubleTap: () -> Unit = {},
    private val onLongPress: () -> Unit = {},
    private val onTripleTap: () -> Unit = {},
    private val onTripleTapHold: () -> Unit = {}
) : GestureDetector.SimpleOnGestureListener() {

    companion object {
        private const val TAG = "GestureDetectorHelper"
        /** Max interval between taps to count as a multi-tap sequence (ms). */
        const val TRIPLE_TAP_WINDOW_MS = 600L
        /** How long the third tap must be held to trigger SOS (ms). */
        const val TRIPLE_TAP_HOLD_THRESHOLD_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Timestamps of recent taps for multi-tap detection. */
    private val tapTimestamps = mutableListOf<Long>()

    /** Whether the third tap is currently being held down. */
    @Volatile
    private var isThirdTapHeld = false

    /** Timestamp when the third tap went down. */
    private var thirdTapDownTime = 0L

    /** Runnable that fires SOS after hold threshold is reached. */
    private val sosRunnable = Runnable {
        if (isThirdTapHeld) {
            onTripleTapHold()
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        recordTap()

        val tapCount = countRecentTaps()

        when {
            tapCount >= 3 -> {
                // Third tap within the window: this is a triple-tap sequence.
                // The gesture-map contract (v2) is: 3rd tap = color analysis
                // (triple tap) and, with a hold, SOS. A plain third-tap
                // release does NOT also suppress the single-tap action on the
                // prior tap — the prior tap is a complete, handled gesture.
                //
                // If this third tap was a hold, the hold detection (started
                // on ACTION_DOWN/ACTION_UP transitions elsewhere) fires SOS.
                // We start the hold timer so the hold path is armed; the
                // hold thread will fire after TRIPLE_TAP_HOLD_THRESHOLD_MS if
                // the finger is still down. (Hold is wired via the onTouchEvent
                // ACTION_DOWN track below.)
                isThirdTapHeld = true
                thirdTapDownTime = SystemClock.uptimeMillis()
                handler.postDelayed(sosRunnable, TRIPLE_TAP_HOLD_THRESHOLD_MS)

                // Fire triple-tap color analysis (this is the 3rd distinct
                // release gesture).
                onTripleTap()
                return true
            }
            else -> {
                // 1st or 2nd tap in the window is a plain single-tap gesture.
                // The second-tap 'wait for possible third' no-op branch was a
                // bug: a second isolated tap in the window had to pass through
                // the whole tap-count chain, so the engine only woke after three
                // taps. Now every isolated confirmed-tap gesture fires.
                onSingleTap(e)
                return true
            }
        }
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        // Record two taps at once for double-tap
        recordTap()
        recordTap()
        onDoubleTap()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        // Only trigger long-press if we're NOT in a triple-tap sequence
        if (countRecentTaps() < 2) {
            onLongPress()
        }
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    override fun onShowPress(e: MotionEvent) {
        // No-op — handled by onDown
    }

    override fun onFling(
        e1: MotionEvent?, e2: MotionEvent,
        velocityX: Float, velocityY: Float
    ): Boolean {
        return false
    }

    override fun onScroll(
        e1: MotionEvent?, e2: MotionEvent,
        distanceX: Float, distanceY: Float
    ): Boolean {
        return false
    }

    /**
     * Call this from the touch listener when ACTION_UP or ACTION_CANCEL
     * is received, to end the hold detection for triple-tap-hold (SOS).
     */
    fun onTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isThirdTapHeld) {
                    isThirdTapHeld = false
                    handler.removeCallbacks(sosRunnable)
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun recordTap() {
        val now = SystemClock.uptimeMillis()
        tapTimestamps.add(now)

        // Evict taps outside the triple-tap window
        while (tapTimestamps.isNotEmpty() &&
            (now - tapTimestamps[0]) > TRIPLE_TAP_WINDOW_MS
        ) {
            tapTimestamps.removeAt(0)
        }
    }

    private fun countRecentTaps(): Int {
        val now = SystemClock.uptimeMillis()
        // Remove expired taps
        tapTimestamps.removeAll { (now - it) > TRIPLE_TAP_WINDOW_MS }
        return tapTimestamps.size
    }

    /**
     * Resets all tap state. Call when the gesture sequence is interrupted.
     */
    fun reset() {
        tapTimestamps.clear()
        isThirdTapHeld = false
        handler.removeCallbacks(sosRunnable)
    }
}

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
                // Third tap within the window: fire color analysis ONLY.
                // SOS is NEVER armed here (audit P2 #12, 2026-11-09):
                // onSingleTapConfirmed fires after the finger is already up
                // (confirmed = completed tap + double-tap timeout), so arming
                // a "still held" timer here could never observe a release —
                // EVERY triple-tap opened the 999 dialer 500 ms later. The
                // hold path arms on the third ACTION_DOWN instead (see
                // onTouchEvent) and any UP/CANCEL disarms it.
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
     * Call this from the touch listener for EVERY motion event (the
     * router forwards the full stream). The third-tap hold arms HERE on
     * ACTION_DOWN — never on tap-confirm, which fires after release:
     * two recent taps plus this down inside the window means the third
     * tap is going down; if the finger stays down past
     * [TRIPLE_TAP_HOLD_THRESHOLD_MS], SOS fires. Any UP/CANCEL disarms.
     */
    fun onTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isThirdTapHeld && countRecentTaps() >= 2) {
                    isThirdTapHeld = true
                    handler.postDelayed(sosRunnable, TRIPLE_TAP_HOLD_THRESHOLD_MS)
                }
            }
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

package com.vyze.app.device

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage

/**
 * Manages the camera flashlight / torch via CameraX [Camera.cameraControl].
 *
 * Provides:
 * - [toggleTorch]: Turn the torch on or off.
 * - [isTorchOn]: Query the current torch state.
 * - [autoTorch]: Automatically enable the torch when the environment is dark,
 *   and disable it when lighting is sufficient.
 */
class FlashlightManager {

    /**
     * The CameraX [Camera] instance that owns the cameraControl used for torch toggling.
     * Must be set from [CameraFragment] after the camera is bound.
     *
     * Volatile (audit P3 #90): bound on the main thread, read from the
     * analyzer thread (autoTorch) — without it the dark-room read can see
     * a stale null (torch never fires) or a stale camera past unbind
     * (enableTorch fails; #47's observed future contains that case).
     */
    @Volatile
    var camera: Camera? = null

    /**
     * Callback fired when the torch state changes (auto or manual).
     * Parameters: (isNowOn: Boolean)
     */
    var onTorchStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Tracks the last-known torch state to avoid redundant toggle commands.
     * CONFIRMED state only — set when the enableTorch future completes, not
     * when the command is sent (audit P2 #47).
     */
    @Volatile
    private var torchEnabled: Boolean = false

    /**
     * Generation counter for toggle requests: only the LATEST request may
     * publish state, so a slow OUT-OF-ORDER completion can never flip the
     * flag back over a newer command.
     */
    private val torchRequestSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Toggle the torch on or off.
     *
     * The enableTorch future is OBSERVED: [torchEnabled] flips only on
     * success, so a rejected command (torn-down camera, HAL error) can no
     * longer leave [isTorchOn] lying — a lie the auto-torch hysteresis
     * would treat as "no change needed" and stick on (audit P2 #47).
     *
     * @param enable `true` to turn the torch on, `false` to turn it off.
     * @param onDone invoked with the CONFIRMED state when the future
     * settles (on the completing thread — callers needing the main thread
     * post themselves, as the auto-torch announcement does).
     */
    fun toggleTorch(enable: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        val cam = camera
        if (cam == null) {
            Log.w(TAG, "Camera not available; cannot toggle torch")
            onDone?.invoke(torchEnabled)
            return
        }

        try {
            if (cam.cameraInfo.hasFlashUnit()) {
                val mySeq = torchRequestSeq.incrementAndGet()
                val future = cam.cameraControl.enableTorch(enable)
                future.addListener({
                    try {
                        future.get() // throws when the HAL rejects the command
                        if (torchRequestSeq.get() == mySeq) {
                            torchEnabled = enable
                            Log.d(TAG, "Torch ${if (enable) "ON" else "OFF"} (confirmed)")
                        } else {
                            Log.d(TAG, "Torch stale completion dropped (seq=$mySeq)")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Torch ${if (enable) "ON" else "OFF"} failed — state unchanged", e)
                    }
                    onDone?.invoke(torchEnabled)
                }, { it.run() })
            } else {
                Log.w(TAG, "This camera has no flash unit")
                onDone?.invoke(torchEnabled)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
            onDone?.invoke(torchEnabled)
        }
    }

    /**
     * Returns the current known torch state.
     */
    fun isTorchOn(): Boolean = torchEnabled

    /**
     * Sets the torch state based on the hysteresis decision from
     * ambient light level checks.
     *
     * The dual-threshold hysteresis (ON < 35 lux, OFF > 65 lux) is already
     * computed by the analyzer, so this method simply applies the result
     * without its own comparison logic.
     *
     * @param shouldBeOn `true` if the hysteresis says torch should be on,
     *                    `false` if it should be off.
     */
    fun autoTorch(shouldBeOn: Boolean) {
        if (shouldBeOn != torchEnabled) {
            // The announcement fires from the CONFIRMED state (P2 #47) —
            // and only when the command actually took effect. Reading
            // torchEnabled synchronously here would announce the stale
            // pre-command value; announcing a FAILED command would speak
            // "on" for a dark torch — or spam every retry tick while the
            // HAL keeps rejecting. Silence on failure is correct: the
            // condition persists, the next tick retries, success speaks.
            toggleTorch(shouldBeOn) { confirmed ->
                if (confirmed == shouldBeOn) onTorchStateChanged?.invoke(confirmed)
            }
        }
    }

    /**
     * Toggle the torch if the user explicitly requests it (e.g., on long-press).
     * If the environment is dark and the torch is off, turn it on.
     * If the torch is already on, turn it off.
     */
    fun userToggle() {
        toggleTorch(!torchEnabled)
    }

    companion object {
        private const val TAG = "FlashlightManager"
    }
}

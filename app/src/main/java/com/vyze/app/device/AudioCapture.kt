package com.vyze.app.device
import com.vyze.app.core.VlmEngineManager

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures speech audio for Gemma 4 E2B's NATIVE audio encoder.
 *
 * Gemma's audio spec: mono, 16 kHz, 32-bit float samples in [-1, 1].
 * [AudioRecord] with [AudioFormat.ENCODING_PCM_FLOAT] at 16 kHz mono
 * produces exactly that — we serialize the floats little-endian as RAW
 * bytes. (The WAV container LiteRT-LM's decoder requires is added by
 * [VlmEngineManager.transcribeAudio] at the engine boundary — never
 * store or send these bytes as audio elsewhere.)
 *
 * Used as Vyze's offline ASR fallback: when Android's SpeechRecognizer
 * fails in a noisy room, capture a short clip here and hand the bytes to
 * [VlmEngineManager.transcribeAudio] so the model itself does the speech
 * recognition — fully offline, no Google services.
 *
 * Phase 3 (suspect-transcript audio replay): the LAST capture is also
 * retained in [lastCapture] so a transcript that came back as wrong-language
 * garble can be re-transcribed offline by Gemma's language-agnostic audio
 * encoder WITHOUT asking the user to repeat. The retention is best-effort —
 * a null/stale value simply disables the replay path.
 */
object AudioCapture {

    private const val TAG = "AudioCapture"

    /** Gemma 4's native audio sample rate. */
    const val SAMPLE_RATE_HZ = 16000

    /** Max clip length — Gemma caps audio input at 30s; short queries need ~6-8s. */
    const val MAX_DURATION_MS = 8000L

    /**
     * Most recent successful capture, retained for the suspect-transcript
     * replay path. Cleared in [clearLastCapture] when a fresh session starts.
     * Volatile: written on the ASR coroutine (IO), read on the main thread.
     */
    @Volatile
    var lastCapture: ByteArray? = null
        private set

    /** Drop the retained capture (new voice session — stale audio is useless). */
    fun clearLastCapture() {
        lastCapture = null
    }

    /** Stop early when this much near-silence has elapsed (user finished speaking). */
    private const val SILENCE_TIMEOUT_MS = 1200L

    /** RMS below this (of a [-1,1] float signal) counts as silence. */
    private const val SILENCE_RMS_THRESHOLD = 0.015f

    /** Don't stop for silence before at least this much speech has been captured. */
    private const val MIN_SPEECH_MS = 1500L

    private val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /**
     * Record speech from the microphone and return raw 16 kHz mono float32
     * PCM bytes ready for [VlmEngineManager.transcribeAudio] (which adds the
     * WAV container LiteRT-LM's decoder requires).
     *
     * Blocks until the user stops speaking (silence timeout), the max
     * duration is reached, or an error occurs. Returns null on failure
     * (no permission, no mic, empty capture).
     *
     * MUST be called off the main thread.
     */
    fun recordSpeech(maxDurationMs: Long = MAX_DURATION_MS): ByteArray? {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "getMinBufferSize failed: ${e.message}")
            return null
        }
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            return null
        }

        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBuffer * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (state=${recorder.state})")
                return null
            }

            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "Failed to start recording")
                return null
            }

            val out = java.io.ByteArrayOutputStream()
            val floatBuf = FloatArray(minBuffer / 4)  // 4 bytes per float sample
            var silenceMs = 0L
            var speechMs = 0L
            val startTime = System.currentTimeMillis()

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= maxDurationMs) {
                    Log.d(TAG, "Max duration reached (${elapsed}ms)")
                    break
                }

                val read = recorder.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord read returned $read")
                    continue
                }

                // RMS of this chunk — silence detector
                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = floatBuf[i].toDouble()
                    sumSq += s * s
                }
                val rms = Math.sqrt(sumSq / read).toFloat()

                if (rms < SILENCE_RMS_THRESHOLD) {
                    silenceMs += (read * 1000L) / SAMPLE_RATE_HZ
                } else {
                    speechMs += (read * 1000L) / SAMPLE_RATE_HZ
                    silenceMs = 0L  // reset silence streak — user still talking
                }

                // Serialize the floats that were actually read (little-endian)
                val byteBuf = ByteBuffer.allocate(read * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) {
                    byteBuf.putFloat(floatBuf[i])
                }
                out.write(byteBuf.array())

                // Stop once we have enough speech AND the user has gone quiet
                if (speechMs >= MIN_SPEECH_MS && silenceMs >= SILENCE_TIMEOUT_MS) {
                    Log.d(TAG, "Silence detected after ${speechMs}ms speech — stopping")
                    break
                }
            }

            val bytes = out.toByteArray()
            Log.i(TAG, "Captured ${bytes.size} bytes (${bytes.size / 4.0 / SAMPLE_RATE_HZ}s at ${SAMPLE_RATE_HZ}Hz)")

            // Reject empty / near-empty captures — nothing useful to transcribe
            if (bytes.size < SAMPLE_RATE_HZ / 4) {  // < 0.25s
                Log.w(TAG, "Capture too short (${bytes.size} bytes) — discarding")
                return null
            }
            lastCapture = bytes  // retained for the suspect-transcript replay
            return bytes

        } catch (e: Throwable) {
            Log.e(TAG, "Audio capture failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        } finally {
            try {
                recorder?.stop()
            } catch (_: Throwable) {}
            try {
                recorder?.release()
            } catch (_: Throwable) {}
        }
    }
}

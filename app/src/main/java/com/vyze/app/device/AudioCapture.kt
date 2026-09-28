package com.vyze.app.device
import com.vyze.app.core.VlmEngineManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.util.CrashLogFile

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
 * PRIMARY offline ASR (Gemma-primary voice, 2026-09): when the device is
 * offline, MainActivity destroys the platform SpeechRecognizer entirely and
 * records here instead — Gemma's audio encoder IS the ears in offline mode
 * ("no cloud recognizer", live-verified 2026-09-22). It also serves as the
 * online-path fallback: when Android's SpeechRecognizer
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

    /** Stop early when this much near-silence has elapsed (user finished speaking).
     * (L2: 1200ms → 800ms — the trailing wait was pure added latency on every
     * offline query; 800ms still tolerates natural mid-phrase pauses.) */
    /**
     * Trailing silence before the recorder decides the user is done.
     * 800 → 450ms (2026-09-28 latency pass): 450ms still clears natural
     * inter-word pauses (~150–300ms) while saving ~350ms on every ask.
     */
    private const val SILENCE_TIMEOUT_MS = 450L

    /** RMS below this (of a [-1,1] float signal) counts as silence. This is the
     * FALLBACK floor — [SilenceGate] replaces it with a per-session ambient
     * calibration whenever the calibration succeeds (L1). */
    private const val SILENCE_RMS_THRESHOLD = 0.015f

    /** Don't stop for silence before at least this much speech has been captured.
     * (L2b: 1500ms → 600ms. The old 1.5s floor meant a short query like
     * "specify" (~0.6s of speech) could NEVER satisfy the silence stop — the
     * recorder then ran to the full 8s cap. That was the dominant offline
     * latency cost in the 2026-09-22 sessions.) */
    /**
     * Classified speech needed BEFORE the trailing-silence stop may engage.
     * 600 → 400ms (2026-09-28 latency pass): desk queries classified at
     * 400–500ms sat under the old bar, so the recorder skipped the early
     * stop and ran to the 4s no-speech bail — up to ~1.5s of dead wait.
     * Anti-hallucination is unaffected: SpeechGatePolicy.MIN_SPEECH_MS
     * (300ms) still gates transcription AFTER capture.
     */
    private const val MIN_SPEECH_MS = 400L

    /**
     * L1 noise-floor calibration: sample this long of ambient BEFORE treating
     * the mic as live. The measured floor + margin becomes the session's
     * silence threshold, so a quiet room stops quickly and a noisy room gets
     * a raised gate instead of recording the full 8s cap of room audio.
     */
    /**
     * Calibration window (failure mode 3, 2026-09-28): 800ms instead of
     * 300ms so a double-tap desk knock — which RINGS through the tabletop
     * for ~500ms — decays INSIDE the window and the quietest-half median
     * sees true ambient. Costs 500ms of head-of-capture audio, which was
     * dead lead-in anyway (the user has not spoken yet).
     */
    private const val CAL_WINDOW_MS = 800L

    /**
     * NO-SPEECH BAIL (L2c): if this much time has elapsed with less than
     * [MIN_SPEECH_MS] of classified speech (and a silent tail), stop anyway.
     * 2026-09-24 08:00 session: 5/5 captures ran the full 8s cap because a
     * contaminated threshold classified real speech as silence (speechMs
     * 20–560 < 600) — the early stop was never eligible. Gemma transcribed
     * that speech from the tail anyway, proving the wait is pure waste.
     */
    private const val NO_SPEECH_BAIL_MS = 4000L

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

            // ── L1: AMBIENT CALIBRATION ─────────────────────────────────
            // Read CAL_WINDOW_MS of room tone WITHOUT storing it, then hand
            // the measured floor to [SilenceGate] (which calibrates on the
            // quietest half of the window — see failure mode 3). If
            // calibration fails for any reason the gate falls back to the
            // fixed floor.
            val gate = SilenceGate()
            val calibFloats = FloatArray(((SAMPLE_RATE_HZ * CAL_WINDOW_MS) / 1000).toInt())
            var calibRead = 0
            while (calibRead < calibFloats.size) {
                val n = recorder.read(calibFloats, calibRead, calibFloats.size - calibRead, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                calibRead += n
            }
            if (calibRead > 0) {
                gate.calibrate(calibFloats, 0, calibRead)
            }
            Log.i(TAG, "SilenceGate calibrated: floor=%.4f threshold=%.4f (calibrated=${calibRead > 0})"
                .format(gate.floorRms, gate.thresholdRms))

            val out = java.io.ByteArrayOutputStream()
            val floatBuf = FloatArray(minBuffer / 4)  // 4 bytes per float sample
            var silenceMs = 0L
            var speechMs = 0L

            // ── RETROACTIVE CLASSIFICATION + LEAD-SILENCE TRIM ───────────
            // (failure mode 4 + latency recovery, 2026-09-28)
            // The calibration audio used to be DISCARDED — but a desk user
            // starts speaking immediately after the double-tap, so the
            // question itself landed inside the window and was thrown away
            // (device evidence: speechMs=100/60/80 with the question audible
            // in the HAL power history). The calibration audio is REAL
            // audio: classify it with the now-known gate so speech inside
            // the window counts toward [speechMs], and RETAIN it in the
            // clip — TRIMMING the leading silent chunks first so the common
            // case (user pauses before speaking) does not feed the ASR
            // 800ms of dead air.
            //
            // FAILURE MODE 5 (device, 09:39): on FOLLOW-UP queries the
            // retained window contained Vyze's OWN TTS tail (the speaker
            // ringing down from the previous answer) — the ASR dutifully
            // transcribed "Hello, I'm a large language model…" and
            // SelfTalkPolicy blocked the turn. So retention is now
            // CONDITIONAL: when TTS spoke within [TTS_ECHO_GUARD_MS], the
            // window is classified but DISCARDED again (the old cold-start
            // rejection only ever mattered on a cold/desk start, never
            // right after an answer).
            if (calibRead > 0) {
                var i = 0
                var leadSilentChunks = 0
                var stillLeading = true
                var fullChunks = 0
                while (i + SilenceGate.CAL_CHUNK_SAMPLES <= calibRead) {
                    var sumSq = 0.0
                    for (j in i until i + SilenceGate.CAL_CHUNK_SAMPLES) {
                        val s = calibFloats[j].toDouble()
                        sumSq += s * s
                    }
                    val rms = Math.sqrt(sumSq / SilenceGate.CAL_CHUNK_SAMPLES).toFloat()
                    val ms = (SilenceGate.CAL_CHUNK_SAMPLES * 1000L) / SAMPLE_RATE_HZ
                    val silent = gate.isSilence(rms)
                    if (silent) {
                        silenceMs += ms
                        if (stillLeading) leadSilentChunks++
                    } else {
                        speechMs += ms
                        silenceMs = 0L
                        stillLeading = false
                    }
                    fullChunks++
                    i += SilenceGate.CAL_CHUNK_SAMPLES
                }
                val ttsEchoRisk = TTSManager.spokeVeryRecently
                val trimStart = if (ttsEchoRisk) {
                    calibRead // discard everything — old cold-rejection behavior
                } else {
                    LeadSilenceTrim.startSample(
                        leadSilentChunks, fullChunks, SilenceGate.CAL_CHUNK_SAMPLES
                    )
                }
                val kept = calibRead - trimStart
                if (kept > 0) {
                    val calibBuf = ByteBuffer.allocate(kept * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (j in trimStart until calibRead) {
                        calibBuf.putFloat(calibFloats[j])
                    }
                    out.write(calibBuf.array())
                }
                Log.i(TAG, "Calibration audio " +
                    (if (ttsEchoRisk) "DISCARDED (TTS echo risk)" else "retained: ${kept * 1000L / SAMPLE_RATE_HZ}ms") +
                    ", trimmed lead silence: ${trimStart * 1000L / SAMPLE_RATE_HZ}ms, " +
                    "retroactive speechMs so far: $speechMs")
            }
            val stopPolicy = CaptureStopPolicy(
                minSpeechMs = MIN_SPEECH_MS,
                trailingSilenceMs = SILENCE_TIMEOUT_MS,
                noSpeechBailMs = NO_SPEECH_BAIL_MS,
                maxDurationMs = maxDurationMs
            )
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

                // RMS of this chunk — silence classifier (calibrated gate)
                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = floatBuf[i].toDouble()
                    sumSq += s * s
                }
                val rms = Math.sqrt(sumSq / read).toFloat()

                if (gate.isSilence(rms)) {
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

                // Stop once we have enough speech AND the user has gone quiet —
                // or after the no-speech bail window when the gate never saw
                // meaningful speech (contaminated threshold / empty room).
                if (stopPolicy.shouldStop(speechMs = speechMs, silenceMs = silenceMs, elapsedMs = elapsed)) {
                    Log.d(TAG, "Stop: speech=${speechMs}ms silence=${silenceMs}ms elapsed=${elapsed}ms")
                    break
                }
            }

            val bytes = out.toByteArray()
            Log.i(TAG, "Captured ${bytes.size} bytes (${bytes.size / 4.0 / SAMPLE_RATE_HZ}s at ${SAMPLE_RATE_HZ}Hz) speechMs=$speechMs")
            CrashLogFile.log(
                TAG,
                "CAPTURE DETAIL: ${(bytes.size / 4.0 / SAMPLE_RATE_HZ)}s clip, speechMs=$speechMs, " +
                    "floor=%.4f, threshold=%.4f, calibrated=${gate.isCalibrated}".format(gate.floorRms, gate.thresholdRms)
            )

            // Reject empty / near-empty captures — nothing useful to transcribe
            if (bytes.size < SAMPLE_RATE_HZ / 4) {  // < 0.25s
                Log.w(TAG, "Capture too short (${bytes.size} bytes) — discarding")
                return null
            }
            // ── LAYER 0: SPEECH GATE (L4 anti-hallucination stack) ──
            // A clip with essentially NO classified speech is hallucination
            // bait: every observed Gemma fabrication ("Selamat pagi", "Saya
            // tidak dapat memproses…", "I am a large language model…") sat
            // on sub-300ms speech. Return null — the honest "I didn't catch
            // that" — instead of spending ~1.5s of inference to fabricate.
            // Pure policy, evidence-bounded floor: see [SpeechGatePolicy].
            if (!SpeechGatePolicy.shouldTranscribe(speechMs)) {
                Log.w(TAG, "Speech gate: speechMs=$speechMs < ${SpeechGatePolicy.MIN_SPEECH_MS} — silent clip, not transcribing")
                CrashLogFile.log(TAG, "SPEECH GATE: dropped (speechMs=$speechMs < ${SpeechGatePolicy.MIN_SPEECH_MS})")
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

/**
 * L1 noise-floor silence gate (latency lever #2, offline voice).
 *
 * The old fixed RMS floor (0.015) had two failure modes, both observed in
 * the 2026-09-22 device sessions:
 *
 *  1. Noisy room: the floor sat BELOW the ambient noise, so room audio
 *     counted as "speech" forever — the recorder ran to the full 8s cap
 *     and Gemma was fed (and transcribed!) media/ambient audio.
 *  2. Quiet room + short query: fine threshold, but the 1.5s min-speech
 *     floor meant sub-1.5s queries could never trigger the early stop.
 *
 * This class fixes (1): at capture start, ~300ms of ambient is sampled and
 * the silence threshold is derived RELATIVE to the measured floor. (2) is
 * fixed separately by lowering MIN_SPEECH_MS. Pure math — no Android — so
 * JVM tests pin the contract directly.
 *
 * FAILURE MODE 3 (2026-09-28, reproduced on device twice): the overall-RMS
 * floor was POISONED by a capture-start transient — the double-tap
 * vibration thump when the phone rests on a hard surface. One ~100ms
 * burst inside the calibration window dragged the floor ~10×
 * (0.002 → 0.017–0.030 observed), the derived threshold rose above
 * speech level, and every ask was rejected as "silent" ("I did not
 * catch that") until the next capture re-calibrated clean. Fix (two
 * stages, both device-evidenced): (a) median of per-chunk RMS instead of
 * overall RMS; (b) after the desk test showed the knock RINGS through the
 * tabletop for ~500ms — covering most of a 300ms window, defeating the
 * plain median — the window grew to 800ms and the floor became the
 * median of the QUIETEST HALF of the chunks. Genuine steady ambient
 * noise still raises the gate exactly as before.
 *
 * Contract (all clamped by ABS_MAX so a loud room can never lock the gate):
 *  - Uncalibrated → fixed fallback floor (0.015), threshold = floor × MARGIN
 *  - Calibrated   → floor = median per-chunk RMS; threshold =
 *                   max(floor × MARGIN, floor + ABS_MIN_MARGIN)
 *  - threshold never exceeds ABS_MAX (loud-room safety valve)
 *  - constant-amplitude input → median == overall RMS (old tests hold)
 */
class SilenceGate {

    /** Measured ambient floor (RMS) — 0 until [calibrate] succeeds. */
    var floorRms: Float = FALLBACK_FLOOR_RMS
        private set

    /** Derived silence threshold — [isSilence] compares against this. */
    var thresholdRms: Float = FALLBACK_FLOOR_RMS * MARGIN
        private set

    /** Whether [calibrate] has run successfully at least once. */
    var isCalibrated: Boolean = false
        private set

    /**
     * Derive floor + threshold from an ambient sample. The window is split
     * into [CAL_CHUNK_SAMPLES]-sample chunks (~20ms at 16kHz — the HAL frame
     * size) and the floor is the MEDIAN of the QUIETEST HALF of the per-chunk
     * RMS values, so a capture-start transient (desk thump, TTS tail,
     * double-tap vibration) cannot poison it — including the desk-resonance
     * case (2026-09-28) where the knock RINGS for most of a short window:
     * with an 800ms window the ring decays inside it and the quiet tail
     * dominates the quietest half. Malformed input (null/empty/zero RMS)
     * leaves the fallback state untouched.
     */
    fun calibrate(samples: FloatArray?, offset: Int = 0, length: Int = samples?.size ?: 0) {
        if (samples == null || length <= 0 || offset < 0 || offset + length > samples.size) return
        val chunkRms = mutableListOf<Float>()
        val end = offset + length
        var i = offset
        while (i < end) {
            val chunkEnd = minOf(i + CAL_CHUNK_SAMPLES, end)
            var sumSq = 0.0
            var count = 0
            for (j in i until chunkEnd) {
                val s = samples[j].toDouble()
                sumSq += s * s
                count++
            }
            if (count > 0) chunkRms.add(Math.sqrt(sumSq / count).toFloat())
            i = chunkEnd
        }
        if (chunkRms.isEmpty()) return
        chunkRms.sort()
        // Median of the QUIETEST half (ceil(n/2) values). Constant amplitude
        // → every chunk is in the quiet half → identical to the overall
        // median (backward compat). A transient ringing through ≤ half the
        // window is excluded entirely.
        val half = (chunkRms.size + 1) / 2
        val n = half
        val median = if (n % 2 == 1) chunkRms[n / 2]
                     else (chunkRms[n / 2 - 1] + chunkRms[n / 2]) / 2f
        // A dead mic or all-zero buffer yields median RMS 0 — keep the fallback.
        if (median <= 0.0) return
        floorRms = median
        val relative = floorRms * MARGIN
        val absolute = floorRms + ABS_MIN_MARGIN
        thresholdRms = Math.min(Math.max(relative, absolute), ABS_MAX)
        isCalibrated = true
    }

    /** True when this chunk's RMS counts as silence under the current gate. */
    fun isSilence(rms: Float): Boolean = rms < thresholdRms

    companion object {
        /** Fallback floor when calibration never ran (old fixed behavior). */
        const val FALLBACK_FLOOR_RMS = 0.015f

        /** Silence threshold margin over the measured/fallback floor. */
        const val MARGIN = 1.8f

        /**
         * Minimum absolute headroom above a measured floor (quiet rooms).
         * 2026-09-28: halved 0.012 → 0.006 — device logs showed desk-
         * occluded speech peaking at only ~0.02 RMS, which could not clear
         * the old floor+0.012 bar in a near-silent room (classified speech
         * halved; asks rejected at 280ms against the 300ms bar). Room-hiss
         * rejection still holds: ambient sits at the floor level, far below
         * floor+0.006, and noisy rooms are governed by the ×1.8 relative
         * margin, which is unchanged.
         */
        const val ABS_MIN_MARGIN = 0.006f

        /** Loud-room safety valve — threshold can never exceed this. */
        const val ABS_MAX = 0.35f

        /**
         * Calibration sub-chunk size in samples (~20ms at 16kHz — matches
         * the observed HAL frame count). The floor is the median of the
         * quietest half of these chunks' RMS (failure mode 3, 2026-09-28).
         */
        const val CAL_CHUNK_SAMPLES = 320
    }
}

/**
 * Pure decision for the LEAD-SILENCE TRIM (latency recovery for failure
 * mode 4, 2026-09-28): the retained calibration window usually opens with
 * silence — the user has not spoken yet — and serializing it whole fed the
 * ASR up to 800ms of dead air (+~800ms perceived latency, observed on
 * device). [startSample] returns the sample offset where the clip should
 * begin: the end of the leading silent run, MINUS one chunk of natural
 * lead-in so the first word is never clipped. No leading silence → 0
 * (keep everything); all-silent window → keep the final chunk (the speech
 * onset may sit in it — the streaming part continues after it).
 */
object LeadSilenceTrim {

    fun startSample(leadSilentChunks: Int, fullChunks: Int, chunkSamples: Int): Int {
        if (leadSilentChunks <= 0 || fullChunks <= 0 || chunkSamples <= 0) return 0
        val silentEndChunk = minOf(leadSilentChunks, fullChunks)
        val keepFromChunk = maxOf(0, silentEndChunk - 1)
        return keepFromChunk * chunkSamples
    }
}

/**
 * Pure stop-decision policy for the capture loop (L2c, 2026-09-24).
 *
 * Three exits, in escalating order:
 *  1. Normal: enough classified speech AND a silent tail → user finished.
 *  2. No-speech bail: the bail window elapsed, the gate never accumulated
 *     [minSpeechMs] of speech, and the tail is silent → stop waiting; the
 *     RMS gate is beatable (quiet speech / contaminated threshold — both
 *     observed on-device), and Gemma's ears are better than the classifier.
 *  3. Hard cap: [maxDurationMs] reached.
 *
 * Pure math — JVM-testable, no Android.
 */
class CaptureStopPolicy(
    private val minSpeechMs: Long,
    private val trailingSilenceMs: Long,
    private val noSpeechBailMs: Long,
    private val maxDurationMs: Long,
) {
    fun shouldStop(speechMs: Long, silenceMs: Long, elapsedMs: Long): Boolean {
        // (3) hard cap
        if (elapsedMs >= maxDurationMs) return true
        // (1) normal completion: real speech happened, then went quiet
        if (speechMs >= minSpeechMs && silenceMs >= trailingSilenceMs) return true
        // (2) no-speech bail: bail window passed, still no classified speech,
        // but the recent tail is quiet (not mid-utterance)
        if (elapsedMs >= noSpeechBailMs &&
            speechMs < minSpeechMs &&
            silenceMs >= trailingSilenceMs
        ) return true
        return false
    }
}

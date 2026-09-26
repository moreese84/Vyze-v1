package com.vyze.app.speech
import com.vyze.app.VyzeApplication
import com.vyze.app.R
import com.vyze.app.util.CrashLogFile
import com.vyze.app.ui.TtsViewModel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton Text-to-Speech manager for the Vyze accessibility app.
 *
 * ## Single Instance
 * This class is a strict singleton — only one instance exists per process.
 * VyzeApplication + TtsViewModel both resolve the same instance via
 * [TTSManager.getInstance].
 *
 * ## Engine: Native Android TextToSpeech ( Principal Android Architect pivot )
 * Speech is produced by the platform engine (`android.speech.tts.TextToSpeech`,
 * i.e. Google TTS on most devices). The previous sherpa-onnx VITS engine was
 * removed: no JNI, no ONNX assets, no manual PCM conversion, no model
 * extraction. This class preserves the EXACT public API the app consumed
 * with sherpa — ViewModels, fragments, and services are untouched.
 *
 * ## Asynchronous Initialization Contract (unchanged)
 * Platform TTS initializes asynchronously via [OnInitListener]. Until init
 * completes, every speak entry point BUFFERS text into [speechBuffer]; when
 * [onInit] fires, the buffer drains automatically and a 5s retry timer
 * ([DRAIN_RETRY_MS]) re-drains anything that raced the engine. Callers never
 * need to wait or poll.
 *
 * ## State Events
 * [UtteranceProgressListener] callbacks drive:
 *  - [pendingUtteranceIds] — deterministic hasPendingSpeech() tracking.
 *  - [utteranceCallbacks] — the per-utterance callback registry: every speak()
 *    call registers its own onStart/onDone/onError against its utterance ID,
 *    and the single engine-global listener dispatches each event to exactly
 *    the flow that owns that utterance. Overlapping speech flows (answer
 *    streaming + a follow-up cue) can never cross-deliver callbacks again.
 *
 * ## Audio Focus
 * Requests AUDIOFOCUS_GAIN (permanent) for the entire app session.
 * Focus is held from app open to app close. Never released per-utterance
 * or on stop() — only on onDestroy().
 */
class TTSManager private constructor(context: Context) {

    // ── Platform TTS Engine ────────────────────────────────────────

    @Volatile
    private var engine: TextToSpeech? = null

    /** True once [onInit] reported TextToSpeech.SUCCESS. */
    @Volatile
    private var isInitialized = false

    /** True once the missing-engine/voice warning has been logged/reported. */
    @Volatile
    private var missingModelWarningShown = false

    /** True between TextToSpeech construction and the OnInitListener callback. */
    @Volatile
    private var engineInitInFlight = false

    /** True when the constructed engine was explicitly the Google TTS package. */
    @Volatile
    private var usedGoogleEngine = false

    /** One-shot flag: Google engine failed init → retry once with system default. */
    @Volatile
    private var defaultEngineRetryTried = false

    private var cachedVolume: Float = DEFAULT_VOLUME
    private var cachedRate: Float = DEFAULT_SPEECH_RATE
    private val appContext: Context = context.applicationContext

    // ── Audio Manager ─────────────────────────────────────────────

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Audio Focus ───────────────────────────────────────────────

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentLocale: Locale = Locale.US

    // ── Debounce state ────────────────────────────────────────────

    @Volatile
    private var lastSpeechTime = 0L

    @Volatile
    private var lastSpokenText = ""

    // ── Speech Buffer ─────────────────────────────────────────────
    // Text arriving before engine init is buffered here and drained on ready.

    private val speechBuffer = ConcurrentLinkedQueue<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Callback invoked when TTS engine is fully ready. */
    var onReady: (() -> Unit)? = null

    /**
     * Per-utterance callback registry (Phase 1 fix — listener-overwrite race).
     *
     * The platform engine supports exactly ONE UtteranceProgressListener per
     * TextToSpeech instance. The previous design exposed
     * setOnUtteranceProgressListener() and callers replaced it on every speech
     * flow — when two flows overlapped (answer streaming + a follow-up cue),
     * the first flow's utterances delivered their onDone to the SECOND flow's
     * listener, firing the wrong callback: premature IDLE, mic reopened
     * mid-answer, and the recognizer captured the answer's tail.
     *
     * Now every speak() call registers its callbacks here, keyed by
     * utteranceId, and the single engine-global listener dispatches each
     * event to exactly the flow that owns that utterance. Callbacks may fire
     * on a TTS service thread — hop to the main thread inside them if UI
     * state is touched.
     */
    private class PerUtteranceCallbacks(
        val onStart: (() -> Unit)?,
        val onDone: (() -> Unit)?,
        val onError: (() -> Unit)?
    )

    private val utteranceCallbacks = ConcurrentHashMap<String, PerUtteranceCallbacks>()

    /**
     * Additive, engine-global callback fired ONLY for utterances that played
     * to completion — never for speech flushed by stop()/QUEUE_FLUSH (those
     * are reported as onError instead). Multiple concerns can observe
     * completion independently. Used by the preference learner.
     */
    var onUtteranceCompleted: ((utteranceId: String) -> Unit)? = null

    // ── Utterance ID Tracking ─────────────────────────────────────
    // Thread-safe set of utterance IDs currently queued or playing.
    // Every speak() call adds an ID; onDone/onError removes it.
    // hasPendingSpeech() returns true iff the set is non-empty.

    private val pendingUtteranceIds = ConcurrentHashMap.newKeySet<String>()

    /** Monotonically increasing counter for unique utterance IDs. */
    private val utteranceCounter = AtomicLong(0)

    /**
     * Generate a unique utterance ID for a speak() call.
     * Format: "utt_{counter}_{timestamp}"
     */
    private fun nextUtteranceId(): String {
        return "utt_${utteranceCounter.incrementAndGet()}_${System.currentTimeMillis()}"
    }

    /**
     * Returns true if any utterances are currently queued or playing.
     * This is the deterministic replacement for isSpeaking() polling.
     */
    fun hasPendingSpeech(): Boolean = pendingUtteranceIds.isNotEmpty()

    /**
     * Number of utterances currently queued or playing — the Q3 backpressure
     * signal. The controller holds sentence flushes while this exceeds
     * [MAX_PENDING_UTTERANCES] so generation outpacing speech cannot stack
     * unbounded synthesis work in the TTS service (worst exactly during a
     * thermal event). The final flush bypasses the cap, so no text is lost.
     */
    fun pendingUtteranceCount(): Int = pendingUtteranceIds.size

    // ── Audio Attributes (Media stream — follows the phone volume) ──
    // USAGE_MEDIA routes TTS to STREAM_MUSIC, so Vyze speaks at exactly
    // the phone's media volume and the hardware volume buttons work
    // normally during speech.

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ── Initialization ────────────────────────────────────────────

    init {
        Log.i(TAG, "[TTSManager] Platform TextToSpeech engine active (native Google TTS)")
        bootstrapEngine()
    }

    /**
     * Construct the platform TextToSpeech engine. The OnInitListener callback
     * arrives asynchronously on the main thread; until then all speech is
     * buffered. Construction is deferred to the main thread because
     * TextToSpeech binds to the engine service and registers a service
     * connection — always safest from the main looper.
     */
    private fun bootstrapEngine() {
        // Self-heal: a teardown path (onDestroy on the shared singleton) can
        // leave isInitialized=false while the stale engine reference still
        // blocks reconstruction below. Rebuild the engine so onInit fires
        // again — otherwise every speak() buffers forever and the app is mute.
        if (engine != null && !isInitialized && !engineInitInFlight) {
            Log.w(TAG, "bootstrapEngine: stale de-initialized engine — rebuilding")
            try { engine?.shutdown() } catch (_: Throwable) {}
            engine = null
        }
        if (engine != null || engineInitInFlight) return
        engineInitInFlight = true
        mainHandler.post {
            var constructed = false
            // Language FIX: the whitepaper (§7.2) documents that Vyze FORCES
            // the Google TTS engine (com.google.android.tts) — it is the only
            // engine with reliable ms-MY / zh-CN voice coverage. The pivot
            // build constructed TextToSpeech with no engine package, so
            // devices whose DEFAULT engine is a third-party TTS with English-
            // only packs silently spoke every language switch in English
            // (switchToLocale logged LANG_NOT_SUPPORTED and kept going).
            if (isGoogleTtsInstalled()) {
                try {
                    Log.i(TAG, "bootstrapEngine: forcing Google TTS engine ($GOOGLE_TTS_PACKAGE)")
                    usedGoogleEngine = true
                    engine = TextToSpeech(appContext, { status -> onInit(status) }, GOOGLE_TTS_PACKAGE)
                    constructed = true
                } catch (e: Throwable) {
                    usedGoogleEngine = false
                    Log.w(TAG, "bootstrapEngine: Google TTS construction failed: ${e.message} — falling back to system default")
                }
            } else {
                Log.i(TAG, "bootstrapEngine: Google TTS not installed — using system default engine")
            }
            if (!constructed) {
                try {
                    Log.i(TAG, "bootstrapEngine: constructing TextToSpeech...")
                    engine = TextToSpeech(appContext) { status -> onInit(status) }
                } catch (e: Throwable) {
                    Log.e(TAG, "bootstrapEngine: TextToSpeech construction failed", e)
                    engineInitInFlight = false
                    if (!missingModelWarningShown) {
                        missingModelWarningShown = true
                        CrashLogFile.log(TAG, "TTS startup: platform TextToSpeech unavailable (${e.message})")
                    }
                }
            }
        }
    }

    /** True when the Google TTS engine app is present on the device. */
    private fun isGoogleTtsInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0) != null
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Platform [OnInitListener] callback — fires on the main thread once the
     * TTS engine service is bound.
     *
     * Handles SUCCESS, LANG_MISSING_DATA, and LANG_NOT_SUPPORTED, defaulting
     * to [Locale.US]. On success: restore persisted settings, install the
     * engine-global UtteranceProgressListener (the single listener routes
     * every event to its per-utterance registered callbacks), drain the
     * speech buffer, and start the drain-retry timer.
     */
    fun onInit(status: Int) {
        engineInitInFlight = false
        if (status != TextToSpeech.SUCCESS) {
            // Language FIX: if the FORCED Google engine failed to bind, retry
            // once with the system default engine instead of staying mute.
            if (usedGoogleEngine && !defaultEngineRetryTried) {
                defaultEngineRetryTried = true
                usedGoogleEngine = false
                engine = null
                try {
                    Log.w(TAG, "onInit: Google TTS engine failed (status=$status) — retrying with system default engine")
                    engine = TextToSpeech(appContext) { s -> onInit(s) }
                    return
                } catch (e: Throwable) {
                    Log.e(TAG, "onInit: default-engine retry failed: ${e.message}")
                }
            }
            Log.w(TAG, "onInit: TextToSpeech init FAILED (status=$status)")
            if (!missingModelWarningShown) {
                missingModelWarningShown = true
                Log.w(TAG, "TTS startup warning: platform TTS engine not available (status=$status)")
                CrashLogFile.log(TAG, "TTS startup: platform TTS init failed (status=$status)")
            }
            return
        }

        val tts = engine
        if (tts == null) {
            Log.w(TAG, "onInit: SUCCESS but engine reference is null")
            return
        }

        // Engine-global progress listener: platform TTS supports exactly one
        // listener per instance, matching the old engine-global contract.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) onUtteranceStart(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) onUtteranceDone(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != null) onUtteranceError(utteranceId)
            }
        })

        // Restore persisted settings (rate/pitch/volume/language).
        applySettings(appContext)

        // Default/init language: US English unless persisted preference applies.
        val langResult = tts.setLanguage(currentLocale)
        Log.i(TAG, "onInit: setLanguage(${currentLocale}) → result=$langResult " +
            "(0=SUCCESS, -1/−2=missing data/not supported; engine falls back internally)")
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "onInit: ${currentLocale} not usable — falling back to Locale.US")
            currentLocale = Locale.US
            tts.setLanguage(Locale.US)
        }

        isInitialized = true

        // Restore the persisted voice selection AFTER language is final —
        // platform setLanguage() resets the engine's voice to the language
        // default, so a voice restored before it would be silently clobbered.
        // (Principal verification fix: KEY_VOICE_NAME was persisted by the
        // voice pickers but never re-applied on init under the pivot build.)
        try {
            val savedVoice = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VOICE_NAME, VOICE_AUTO) ?: VOICE_AUTO
            if (savedVoice != VOICE_AUTO) {
                setVoiceByName(savedVoice)
                Log.i(TAG, "onInit: restored persisted voice '$savedVoice'")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onInit: voice restore failed: ${e.message}")
        }

        Log.i(TAG, "TTS fully initialized — platform engine ready, locale=$currentLocale, " +
            "initial drain: ${speechBuffer.size} buffered")
        drainPendingQueue()
        onReady?.invoke()
        startDrainRetryTimer()
    }

    // ── Drain Retry Timer ─────────────────────────────────────────

    private var drainRetryRunnable: Runnable? = null

    private fun startDrainRetryTimer() {
        drainRetryRunnable?.let { mainHandler.removeCallbacks(it) }

        val startTime = System.currentTimeMillis()
        drainRetryRunnable = object : Runnable {
            override fun run() {
                if (speechBuffer.isNotEmpty() && isInitialized) {
                    val drained = drainPendingQueue()
                    if (drained > 0) {
                        Log.d(TAG, "Drain retry: spoke $drained messages")
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < DRAIN_RETRY_MS && speechBuffer.isNotEmpty()) {
                    mainHandler.postDelayed(this, DRAIN_RETRY_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(drainRetryRunnable!!, DRAIN_RETRY_INTERVAL_MS)
    }

    private fun stopDrainRetryTimer() {
        drainRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        drainRetryRunnable = null
    }

    // ── Speech Buffer Drain ───────────────────────────────────────

    private fun drainPendingQueue(): Int {
        if (!isInitialized) {
            Log.w(TAG, "drainPendingQueue: TTS not ready, skipping")
            return 0
        }

        var drained = 0
        while (true) {
            val text = speechBuffer.poll() ?: break
            if (text.isBlank()) continue

            val utteranceId = nextUtteranceId()

            if (engine != null) {
                val enhanced = prepareForEngine(text)
                pendingUtteranceIds.add(utteranceId)
                val accepted = speakWithEngine(
                    enhanced, utteranceId,
                    queueMode = TextToSpeech.QUEUE_ADD,
                    onStart = null,
                    onDone = null,
                    onError = null
                )
                if (!accepted) {
                    pendingUtteranceIds.remove(utteranceId)  // Phase 1 leak fix
                }
                Log.d(TAG, "Drained #$drained (platform, id=$utteranceId): ${text.take(60)}...")
            } else {
                Log.d(TAG, "drainPendingQueue: engine unavailable — re-queuing: \"${text.take(60)}\"")
                speechBuffer.add(text)
                break
            }

            lastSpeechTime = System.currentTimeMillis()
            lastSpokenText = text
            drained++
        }

        if (drained > 0) {
            Log.i(TAG, "Drained $drained buffered utterances, buffer remaining: ${speechBuffer.size}, " +
                "pending IDs: ${pendingUtteranceIds.size}")
        }
        return drained
    }

    // ── Public Speech API (contract preserved) ────────────────────

    /**
     * Speaks the given text with the specified queue mode.
     * Generates a unique utteranceId, adds it to pendingUtteranceIds,
     * and tracks it until onDone/onError removes it.
     *
     * @param text       The text to speak
     * @param queueMode  QUEUE_FLUSH barges in (cancels current + queued speech
     *                   and reports them as onError); QUEUE_ADD appends.
     * @param utteranceId Optional caller-provided ID (e.g., "session_chunk_3")
     *                    If null, generates one automatically.
     * @param onStart     Invoked when THIS utterance starts playing (may fire
     *                    on a TTS service thread).
     * @param onDone      Invoked when THIS utterance finishes playing.
     * @param onError     Invoked when THIS utterance fails or is dropped by
     *                    stop()/QUEUE_FLUSH. Exactly one of onDone/onError fires.
     * @return true if speak() succeeded
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        utteranceId: String? = null,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ): Boolean {
        Log.i(TAG, "speak() INVOKED: text='${text.take(100)}', isReady=${isInitialized}, bufferSize=${speechBuffer.size}, pendingIds=${pendingUtteranceIds.size}")

        if (text.isBlank()) {
            Log.w(TAG, "speak() INVOKED: text is blank — returning false")
            return false
        }

        if (!isInitialized) {
            Log.d(TAG, "speak() before TTS init — buffering: \"${text.take(60)}\" (drained automatically on init)")
            speechBuffer.add(text)
            bootstrapEngine()
            return false
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speak() DEBOUNCE — dropping duplicate: \"${text.take(60)}\"")
            return false
        }

        lastSpeechTime = now
        lastSpokenText = text

        // Pronunciation overrides + language smoothing + prosody pauses
        val enhancedText = prepareForEngine(text)

        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            notifyFlushed()
        }
        val id = utteranceId ?: nextUtteranceId()
        // Track BEFORE submission — the engine can fire onStart from its binder
        // thread as soon as tts.speak() lands, before this call returns.
        pendingUtteranceIds.add(id)
        val accepted = speakWithEngine(
            enhancedText, id,
            queueMode = queueMode,
            onStart = onStart,
            onDone = onDone,
            onError = onError
        )
        if (!accepted) {
            // Phase 1 LEAK FIX: a rejected submission never fires terminal
            // callbacks. The ID used to stay in the set forever, pinning
            // hasPendingSpeech() true and deadlocking every waitForTtsDrain /
            // mic-reopen path until the 30s SPEAKING_TIMEOUT force-reset.
            pendingUtteranceIds.remove(id)
        }
        Log.d(TAG, "speak() ${if (accepted) "OK" else "REJECTED"} (platform) id=$id queueMode=$queueMode " +
            "pending=${pendingUtteranceIds.size} text=\"${text.take(60)}\"")
        return accepted
    }

    /**
     * Immediate speech for urgent accessibility feedback.
     * Stops current speech, speaks with QUEUE_FLUSH.
     */
    fun speakImmediate(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ): Boolean {
        Log.i(TAG, "speakImmediate() INVOKED: text='${text.take(100)}', isReady=${isInitialized}, bufferSize=${speechBuffer.size}, pendingIds=${pendingUtteranceIds.size}")

        if (text.isBlank()) {
            Log.w(TAG, "speakImmediate() called with blank text — skipping")
            return false
        }

        if (!isInitialized) {
            Log.d(TAG, "speakImmediate() before TTS init — buffering: \"${text.take(60)}\" (drained automatically on init)")
            speechBuffer.add(text)
            bootstrapEngine()
            return false
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speakImmediate() DEBOUNCE — dropping duplicate: \"${text.take(60)}\"")
            return false
        }

        lastSpeechTime = now
        lastSpokenText = text

        // Pronunciation overrides + language smoothing + prosody pauses
        val enhancedText = prepareForEngine(text)

        // QUEUE_FLUSH semantics: barge-in — report the flushed utterances as
        // errors so waiting listeners can resume.
        notifyFlushed()
        val id = nextUtteranceId()
        pendingUtteranceIds.add(id)
        val accepted = speakWithEngine(
            enhancedText, id,
            queueMode = TextToSpeech.QUEUE_FLUSH,
            onStart = onStart,
            onDone = onDone,
            onError = onError
        )
        if (!accepted) {
            pendingUtteranceIds.remove(id)  // Phase 1 leak fix — see speak()
        }
        Log.d(TAG, "speakImmediate() ${if (accepted) "OK" else "REJECTED"} (platform) id=$id " +
            "pending=${pendingUtteranceIds.size} text=\"${text.take(60)}\"")
        return accepted
    }

    fun speakQueued(text: String) {
        Log.i(TAG, "speakQueued() INVOKED: text='${text.take(100)}', isReady=${isInitialized}, bufferSize=${speechBuffer.size}")

        if (text.isBlank()) {
            Log.d(TAG, "speakQueued: text is blank — skipping")
            return
        }

        if (isInitialized) {
            speakImmediate(text)
        } else {
            Log.d(TAG, "speakQueued: TTS not ready — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
            bootstrapEngine()
        }
    }

    fun speakImmediateQueued(text: String) {
        if (text.isBlank()) return
        if (isInitialized) {
            speakImmediate(text)
        } else {
            Log.d(TAG, "speakImmediateQueued: TTS not ready — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
            bootstrapEngine()
        }
    }

    /**
     * Core engine submission. Registers the per-utterance callbacks and
     * returns the platform accept result:
     * TextToSpeech.queueSpeak returns SUCCESS(0) or ERROR(-1).
     */
    private fun speakWithEngine(
        text: String,
        utteranceId: String,
        queueMode: Int,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: (() -> Unit)?
    ): Boolean {
        val tts = engine
        if (tts == null) {
            try { onError?.invoke() } catch (_: Throwable) {}
            return false
        }
        return try {
            // Critical-info pacing: money amounts and medication doses are
            // safety-critical for a blind user — spoken ~15% slower so the
            // figures land clearly. Rate is applied per utterance via the
            // engine (KEY_PARAM_RATE is not public API) and restored when the
            // utterance finishes, so the user's global rate is untouched.
            val critical = isCriticalInfoText(text)
            val targetRate = if (critical) {
                criticalUtteranceIds.add(utteranceId)
                cachedRate * CRITICAL_INFO_RATE_FACTOR
            } else {
                cachedRate
            }
            try {
                if (engineRateApplied != targetRate) {
                    tts.setSpeechRate(targetRate)
                    engineRateApplied = targetRate
                }
            } catch (e: Throwable) {
                Log.w(TAG, "speakWithEngine: setSpeechRate($targetRate) failed: ${e.message}")
            }
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, cachedVolume)
            }
            // Register BEFORE submission — the engine can fire onStart from
            // its binder thread as soon as tts.speak() lands.
            utteranceCallbacks[utteranceId] = PerUtteranceCallbacks(onStart, onDone, onError)
            val result = tts.speak(text, queueMode, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                // Rejected at submission — no terminal callback will ever fire
                // from the engine. Untrack everything and report the failure
                // directly so waiting callers (drain loops, speakThen) are
                // released instead of hanging.
                utteranceCallbacks.remove(utteranceId)
                criticalUtteranceIds.remove(utteranceId)
                try { onError?.invoke() } catch (_: Throwable) {}
            }
            result == TextToSpeech.SUCCESS
        } catch (e: Throwable) {
            utteranceCallbacks.remove(utteranceId)
            Log.e(TAG, "speakWithEngine failed: ${e.javaClass.simpleName}: ${e.message}")
            try { onError?.invoke() } catch (_: Throwable) {}
            false
        }
    }

    fun isSpeaking(): Boolean =
        pendingUtteranceIds.isNotEmpty() || (engine?.isSpeaking == true)

    fun isReady(): Boolean = isInitialized && engine != null

    /**
     * Stop all speech and clear all pending utterance tracking.
     * This is the ONLY way to guarantee hasPendingSpeech() returns false
     * immediately after stop().
     */
    fun stop() {
        notifyFlushed()
        try {
            engine?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "engine stop failed: ${e.message}")
        }
        Log.d(TAG, "stop() — pendingUtteranceIds cleared, flushed utterances reported as onError")
    }

    // ── Utterance Event Forwarding ────────────────────────────────

    // Events are dispatched to the per-utterance callbacks captured in
    // [utteranceCallbacks] at speak() time — each utterance's terminal event
    // can only ever reach the flow that submitted it (the Phase 1 fix for
    // the engine-global listener-overwrite race).

    /** Utterance IDs currently playing at the slowed critical-info rate. */
    private val criticalUtteranceIds = ConcurrentHashMap.newKeySet<String>()

    /** Rate last applied to the engine (avoids redundant setSpeechRate calls). */
    private var engineRateApplied = DEFAULT_SPEECH_RATE

    /** Restore the user's normal rate after a critical-info utterance ends. */
    private fun restoreNormalRateAfterCritical(id: String) {
        if (criticalUtteranceIds.remove(id)) {
            try {
                engine?.setSpeechRate(cachedRate)
                engineRateApplied = cachedRate
            } catch (_: Throwable) {}
        }
    }

    /** Utterance started processing — dispatch to its registered callbacks. */
    private fun onUtteranceStart(id: String) {
        try { utteranceCallbacks[id]?.onStart?.invoke() } catch (e: Throwable) {
            Log.w(TAG, "onUtteranceStart: callback error: ${e.message}")
        }
    }

    /** Utterance finished playing — untrack, dispatch, and clean up. */
    private fun onUtteranceDone(id: String) {
        pendingUtteranceIds.remove(id)
        restoreNormalRateAfterCritical(id)
        val callbacks = utteranceCallbacks.remove(id)
        try { onUtteranceCompleted?.invoke(id) } catch (_: Throwable) {}
        try { callbacks?.onDone?.invoke() } catch (e: Throwable) {
            Log.w(TAG, "onUtteranceDone: callback error: ${e.message}")
        }
    }

    /** Utterance failed (or was flushed) — untrack and dispatch. */
    private fun onUtteranceError(id: String) {
        pendingUtteranceIds.remove(id)
        restoreNormalRateAfterCritical(id)
        val callbacks = utteranceCallbacks.remove(id)
        try { callbacks?.onError?.invoke() } catch (e: Throwable) {
            Log.w(TAG, "onUtteranceError: callback error: ${e.message}")
        }
    }

    /**
     * Report currently-pending utterances as errored and untrack them.
     * Mirrors Google TTS, which fires onError for utterances dropped by
     * stop()/QUEUE_FLUSH — callers rely on that to leave their wait state.
     * Each flushed utterance's OWN registered callbacks are dispatched, so a
     * barged-in flow always resumes even while another flow is speaking.
     */
    private fun notifyFlushed() {
        val ids = pendingUtteranceIds.toList()
        if (ids.isEmpty()) return
        pendingUtteranceIds.removeAll(ids)
        ids.forEach { id ->
            val callbacks = utteranceCallbacks.remove(id)
            try { callbacks?.onError?.invoke() } catch (_: Throwable) {}
        }
    }

    /**
     * Speak [text] and invoke [onDone] exactly when THIS utterance reaches a
     * terminal state — onDone (played fully) or onError (failed, or dropped
     * by stop()/QUEUE_FLUSH from a later barge-in). Never fires for any other
     * utterance, and never fires twice.
     *
     * Replaces the old engine-global listener swap: the platform engine has
     * ONE listener slot, and callers that replaced it cross-delivered
     * terminal callbacks between overlapping speech flows — the mid-sentence
     * follow-up cutoff bug. Returns false when the submission was rejected
     * (engine unavailable/blank text/debounce) — [onDone] has already been
     * invoked in that case, so callers can simply proceed.
     *
     * The callback fires on a TTS service thread — hop to the main thread
     * inside the callback if UI state is touched.
     */
    fun speakThen(text: String, onDone: () -> Unit): Boolean {
        return speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            onDone = onDone,
            onError = onDone
        )
    }

    /**
     * Explicitly release audio focus.
     */
    fun abandonFocus() {
        abandonAudioFocus()
    }

    // ── Audio Focus ───────────────────────────────────────────────

    /**
     * Request permanent audio focus for the entire app session.
     * Uses AUDIOFOCUS_GAIN (not TRANSIENT) to suppress TalkBack and
     * other accessibility audio while Vyze is active.
     * Called once on app open — not per-utterance.
     */
    fun holdSessionFocus() {
        if (audioFocusRequest != null) {
            Log.d(TAG, "holdSessionFocus: already holding focus")
            return
        }
        try {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            Log.i(TAG, "holdSessionFocus: AUDIOFOCUS_GAIN requested (result=$result)")
            CrashLogFile.log(TAG, "Session audio focus acquired (GAIN, result=$result)")
        } catch (e: Throwable) {
            Log.w(TAG, "holdSessionFocus failed: ${e.message}")
        }
    }

    /**
     * Release session audio focus. Called only on app destroy.
     * Allows TalkBack and other services to resume.
     */
    fun releaseSessionFocus() {
        abandonAudioFocus()
        CrashLogFile.log(TAG, "Session audio focus released")
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
                Log.d(TAG, "Audio focus abandoned")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }

    // ── Voice Quality Selection ──────────────────────────────────

    private fun selectBestVoice(locale: Locale) {
        // The platform engine picks its best voice for the locale set via
        // setLanguage(). Kept as a hook for future quality tuning.
    }

    // ── Voice Picker Support (Tier 1) ──────────────────────────────

    /** Installed voices for the current language (uninstalled packs excluded). */
    fun getInstalledVoicesForCurrentLanguage(): List<Voice> {
        val tts = engine ?: return emptyList()
        return try {
            tts.voices?.filter { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale.language == currentLocale.language
            } ?: emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "getInstalledVoicesForCurrentLanguage failed: ${e.message}")
            emptyList()
        }
    }

    /** Name of the voice actually in use, or null if unknown. */
    fun getCurrentVoiceName(): String? = try {
        engine?.voice?.name
    } catch (e: Throwable) {
        null
    }

    /**
     * True when the platform engine can speak the given language code
     * ("en" / "ms" / "zh"). Drives the voice-settings language gate.
     */
    fun hasInstalledVoicesFor(language: String): Boolean {
        val tts = engine ?: return false
        return try {
            val probe = when (language) {
                LANGUAGE_MALAY -> Locale("ms", "MY")
                LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                else -> Locale.US
            }
            val result = tts.isLanguageAvailable(probe)
            result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } catch (e: Throwable) {
            Log.w(TAG, "hasInstalledVoicesFor($language) failed: ${e.message}")
            false
        }
    }

    /**
     * Select a voice by name ("" or [VOICE_AUTO] → auto-pick the best
     * installed voice).
     */
    fun setVoiceByName(name: String) {
        val tts = engine ?: return
        try {
            if (name.isBlank() || name == VOICE_AUTO) {
                tts.setLanguage(currentLocale)
                return
            }
            val voice = tts.voices?.firstOrNull { it.name == name }
            if (voice != null) {
                val result = tts.setVoice(voice)
                Log.i(TAG, "setVoiceByName: '$name' → result=$result")
            } else {
                Log.w(TAG, "setVoiceByName: voice '$name' not installed — keeping current")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "setVoiceByName failed: ${e.message}")
        }
    }

    /**
     * True when the best installed voice for the current language is the
     * robotic base quality (or when no voice pack is installed at all and
     * the engine falls back to its default). Used to offer the better
     * voice install prompt.
     */
    fun isVoiceQualityLow(): Boolean {
        val voices = getInstalledVoicesForCurrentLanguage()
        if (voices.isEmpty()) return true
        // Platform heuristic: a device with the full Google TTS data pack
        // exposes MULTIPLE voices per language (en-us-x-tpd-local,
        // en-us-x-sfg-network, ... incl. the higher-quality "network" and
        // premium "studio"/"enhanced" tiers). A bare engine (Pico-style or
        // partial install) exposes 1-2 basic voices only. Quality counts as
        // acceptable when ≥3 voices exist OR any premium-tier name is present.
        if (voices.size >= 3) return false
        val premium = voices.any { v ->
            val n = v.name.lowercase(Locale.ROOT)
            n.contains("studio") || n.contains("enhanced") || n.contains("network")
        }
        return !premium
    }

    /**
     * Direct test function: immediately speaks a test phrase.
     * Use this from a button or adb to verify the full TTS pipeline:
     * engine init → queue → audio output.
     */
    fun testPlayback() {
        Log.i(TAG, "testPlayback() INVOKED — triggering speakImmediate(\"Testing audio playback 1 2 3\")")
        speakImmediate("Testing audio playback 1 2 3")
    }

    // ── Engine Text Interception (pronunciation + language smoothing) ─

    /**
     * Single text interceptor that every speak() entry point funnels through.
     * Runs, in order:
     *  1. [applyPronunciationOverrides] — curated respellings (brand names,
     *     phonetic repairs for English loans read by the wrong voice).
     *  2. [applyLanguageSmoothing] — per-language flow fixes: Malay liaison
     *     and Chinese trailing particle so sentences do not sound like
     *     isolated word blocks with dead air between them.
     *  3. [enhanceForNaturalProsody] — whitespace/punctuation normalization.
     */
    private fun prepareForEngine(text: String): String {
        if (text.isBlank()) return text
        var out = applyPronunciationOverrides(text)
        out = expandIdentifierCodes(out)
        out = applyLanguageSmoothing(out)
        out = enhanceForNaturalProsody(out)
        return out
    }

    /**
     * Identifier codes (vehicle plates, serial/reference numbers) must be
     * spoken CHARACTER BY CHARACTER — "QLB 3469" as "Q L B, three four six
     * nine", never "three thousand four hundred sixty-nine". The prompt now
     * instructs the model to do this; this TTS-layer net catches whatever the
     * model still emits in compact form (or verbatim OCR echoes).
     *
     * Matches letter-prefix + digits tokens ("QLB 3469", "QLB3469", "W 1234")
     * and spaces out every character. Currency/unit prefixes ("RM12.90",
     * "12kg") are explicitly excluded so prices and quantities are untouched.
     */
    private fun expandIdentifierCodes(text: String): String {
        // Letters (1-3) + optional space/hyphen + digits (1-4) + optional
        // trailing letter — the common plate/code shapes.
        val codeRegex = Regex("\\b([A-Za-z]{1,3})[- ]?(\\d{1,4})([A-Za-z])?\\b")
        return codeRegex.replace(text) { m ->
            val prefix = m.groupValues[1].uppercase()
            val trailing = m.groupValues[3]
            // Price/quantity guard: currency prefixes (RM12, USD99) and units
            // (kg25 is rare, but “No12”-style refs stay untouched) are never
            // expanded; neither is a price decimal (“RM12.90”).
            val next = m.range.last + 1
            val followedByDecimal =
                next < text.length && text[next] == '.' &&
                    next + 1 < text.length && text[next + 1].isDigit()
            if (prefix in CURRENCY_AND_UNIT_PREFIXES || followedByDecimal) {
                m.value
            } else {
                val letters = m.groupValues[1].uppercase().toCharArray().joinToString(" ")
                val digits = m.groupValues[2].toCharArray().joinToString(" ")
                if (trailing.isNotBlank()) "$letters $digits ${trailing.uppercase()}" else "$letters $digits"
            }
        }
    }

    /**
     * True when [text] carries safety-critical readouts — money amounts or
     * medication doses. Such answers are spoken ~15% slower ([CRITICAL_INFO_RATE_FACTOR])
     * so the user can register the exact figures. Detection is shape-based
     * (currency symbol + digits, digits + unit word) so it works regardless
     * of answer language, and deliberately narrow to avoid slowing ordinary
     * scene chatter that merely contains a number ("about 2 steps ahead").
     */
    private fun isCriticalInfoText(text: String): Boolean {
        // Currency symbol/prefix directly on digits: "RM12.90", "USD 99"
        if (Regex("(?i)(rm|rp|usd|sgd|eur|gbp|myr)\\s*\\d").containsMatchIn(text)) return true
        // Digits + money word: "12.90 ringgit", "99 dollars", "5 sen"
        if (Regex("(?i)\\d([.,]\\d{1,2})?\\s*(ringgit|sen|rupiah|dollar|euro|yuan|令吉|块|元|仙)")
                .containsMatchIn(text)
        ) return true
        // CJK money markers directly adjacent to digits: "12令吉90仙"
        if (Regex("\\d(令吉|块|元|毛|仙|分)").containsMatchIn(text)) return true
        // Medication dosing: "500 mg", "5 ml", "2 tablets"
        if (Regex("(?i)\\d\\s*(mg|mcg|ml|milligram|tablet|tablets|kapsul|kaplet|pil)\\b")
                .containsMatchIn(text)
        ) return true
        // CJK dosing: "500毫克", "5毫升"
        if (Regex("\\d\\s*(毫克|毫升|微克)").containsMatchIn(text)) return true
        return false
    }

    /**
     * True when [text] is predominantly CJK (Chinese). Used to route text to
     * the Chinese smoothing rules even when the ACTIVE voice is a different
     * language (e.g. a VLM answer containing embedded Chinese).
     */
    private fun isChineseText(text: String): Boolean {
        var cjk = 0
        var letters = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++
            if (ch.isLetter()) letters++
        }
        return letters > 0 && cjk * 2 >= letters
    }

    /**
     * Per-language flow smoothing — fixes plosology (sharp, disconnected
     * word sounds) that violates natural speech timing.
     *
     * ## Chinese (zh-CN voice)
     * The voice reads each character in isolation with hard boundaries when
     * text lacks natural prosody anchors. Two fixes:
     *  - Normalize ASCII punctuation ("," ":" ";" "!" "?") to full-width
     *    Chinese equivalents so the voice inserts native micro-pauses and
     *    connects syllables instead of stuttering at ASCII boundaries.
     *  - Append the soft trailing tail "，请稍等。" ("…please wait.") to
     *    short status phrases (≤ 14 chars, e.g. "正在分析画面"). The tail
     *    gives the voice a falling, completed intonation contour, so the
     *    audio flows out instead of stopping abruptly on the last word.
     *    Longer open sentences just get a closing 。; sentences with their
     *    own terminal punctuation are left untouched.
     *
     * ## Malay (ms-MY voice)
     * Google's ms-MY voice is a thin English-leaning pack: it reads English
     * loans letter-by-letter with pauses between every word ("gas station"
     * becomes two isolated words). Fix:
     *  - Respell common English/technical loans with Malay phonetics so the
     *    voice blends them into one fluid sound (see the table).
     *  - Append the soft closing particle " ya." ("…okay.") to short phrases
     *    so the last word lands in a natural falling contour instead of an
     *    abrupt English-style full stop.
     */
    private fun applyLanguageSmoothing(text: String): String {
        if (text.isBlank()) return text
        return when {
            currentLocale.language == "zh" || isChineseText(text) -> smoothChinese(text)
            currentLocale.language == "ms" -> smoothMalay(text)
            else -> text
        }
    }

    /** Chinese-specific smoothing — see [applyLanguageSmoothing]. */
    private fun smoothChinese(text: String): String {
        // Ellipses (status strings end with “…”) read as an awkward dead-air
        // hold or get merged with the next chunk — strip them first.
        var out = text
            .replace("\u2026", "")   // …
            .replace("...", "")
            // Decimal guard: "3.5" / "1,000" must keep their ASCII marks —
            // the zh voice reads them natively but stumbles on 3。5.
            .replace(Regex("(?<!\\d),(?!\\d)"), "\uff0c")   // , → ，
            .replace(":", "\uff1a")      // : → ：
            .replace(";", "\uff1b")      // ; → ；
            .replace("!", "\uff01")      // ! → ！
            .replace("?", "\uff1f")      // ? → ？
            .replace(Regex("(?<!\\d)\\.(?!\\d)"), "\u3002")  // . → 。
            // Collapse duplicated terminators left by the replacements above.
            .replace("\u3002\u3002", "\u3002")
            .replace("\u3002\uff01", "\uff01")
            .replace("\u3002\uff1f", "\uff1f")
            .trim()

        if (out.isEmpty()) return out

        val endsOpen = out.last() != '\u3002' && out.last() != '\uff01' && out.last() != '\uff1f'
        if (endsOpen && out.length <= 14) {
            // Short status phrase (“正在分析画面”) — close it with a soft,
            // natural tail so the voice lands in a falling contour and the
            // audio flows out instead of stopping abruptly.
            out = "${out}\uff0c\u8bf7\u7a0d\u7b49\u3002"   // ，请稍等。
        } else if (endsOpen) {
            out = "${out}\u3002"
        }
        return out
    }

    /** Malay-specific smoothing — see [applyLanguageSmoothing]. */
    private fun smoothMalay(text: String): String {
        var out = text
            .replace("\u2026", ".")   // … → . (ms voice reads U+2026 poorly)
            .replace("...", ".")
        // Phonetic respelling of English loans the ms voice stutters on.
        // Whole-word, case-insensitive.
        for ((from, to) in MALAY_PHONETIC_RESPELLINGS) {
            out = out.replace(Regex("(?i)\\b" + Regex.escape(from) + "\\b"), to)
        }
        out = out.trim()

        // Soft closing particle for short status-like phrases: a gentle
        // " ya." tail gives the voice a completed intonation contour instead
        // of an abrupt stop, blending the last word into a natural fall.
        if (out.isNotEmpty() && out.length < 48 &&
            !out.last().let { it == '.' || it == '!' || it == '?' }
        ) {
            out = "$out ya."
        }
        return out
    }

    /**
     * Apply curated pronunciation overrides for brand/product names that
     * generic TTS voices misread. Example: Google's English voice reads the
     * noodle brand "Maggi" as "MAY-jee"; the respelling below forces the
     * brand's real pronunciation "MAY-ghee" (ghee → hard g, /giː/).
     *
     * Whole-word, case-insensitive, and scoped to the ACTIVE voice language
     * (Malay and Chinese voices already read these brands correctly, so the
     * respellings are English-only — a Malay "Mayghee" would itself be wrong).
     * Additions welcome: one entry per brand + language. Heuristic by nature:
     * final accuracy depends on the installed engine voice.
     */
    private fun applyPronunciationOverrides(text: String): String {
        if (text.isBlank()) return text
        val overrides = when (currentLocale.language) {
            "en" -> ENGLISH_PRONUNCIATION_OVERRIDES
            else -> return text
        }
        var out = text
        for ((from, to) in overrides) {
            out = out.replace(Regex("(?i)\\b" + Regex.escape(from) + "\\b"), to)
        }
        return out
    }

    private fun enhanceForNaturalProsody(text: String): String {
        if (text.isBlank()) return text

        var enhanced = text.trim()

        // CJK text is handled by [smoothChinese] (full-width punctuation);
        // the Latin-oriented spacing rules below would corrupt it.
        if (isChineseText(enhanced)) return enhanced

        // Ellipsis pause FIX: Google TTS inserts a long native ellipsis pause
        // mid-sentence ("I can see... a mug"). Collapse model-emitted "..."
        // and the single-char U+2026 (…) to a plain period so the ellipsis
        // reads as a normal sentence boundary instead of dead air. (Chinese
        // and Malay paths already strip ellipses in their smoothing rules —
        // this covers English, which had no handling.)
        enhanced = enhanced.replace("...", ".").replace("\u2026", ".")

        // Ensure sentence terminators are followed by a space
        enhanced = enhanced.replace(Regex("([.!?])([A-Za-z0-9])"), "$1 $2")

        // Ensure commas are followed by a space
        enhanced = enhanced.replace(Regex("(,)([A-Za-z0-9])"), "$1 $2")

        // Ensure colons/semicolons are followed by a space
        enhanced = enhanced.replace(Regex("([:;])([A-Za-z0-9])"), "$1 $2")

        // Add trailing period if missing. A trailing ',' is already valid
        // punctuation for TTS and marks a mid-sentence fast-start fragment —
        // stamping a period there would force full-stop intonation and a
        // dead-air seam into the middle of a flowing sentence.
        if (enhanced.isNotEmpty() && !enhanced.last().isWhitespace() &&
            enhanced.last() !in charArrayOf('.', '!', '?', ',')
        ) {
            enhanced = "$enhanced."
        }

        return enhanced
    }

    // ── Locale Switching ──────────────────────────────────────────

    fun switchToLocale(locale: Locale) {
        currentLocale = locale
        if (!isInitialized) {
            Log.d(TAG, "switchToLocale($locale) — TTS not ready, will apply on next init")
            return
        }

        try {
            var result = engine?.setLanguage(locale)
            // Language FIX: Google TTS on many builds exposes Malay/Chinese as
            // the bare language ("ms", "zh") while the exact region variant
            // (ms-MY, zh-CN) reports LANG_MISSING_DATA / LANG_NOT_SUPPORTED.
            // Retry language-only before declaring the switch unusable —
            // without this, mirrored switches silently kept the old voice.
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val languageOnly = Locale(locale.language)
                result = engine?.setLanguage(languageOnly)
                Log.i(TAG, "switchToLocale: region variant rejected — retried language-only $languageOnly → result=$result")
            }
            Log.i(TAG, "switchToLocale: setLanguage($locale) → result=$result")
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "switchToLocale: $locale not usable on this device — keeping locale for tracking, engine may fall back")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "switchToLocale failed: ${e.message}")
        }
    }

    /**
     * Mirror a raw STT-detected locale onto the TTS voice (dynamic language
     * mirroring). This is the ENGINE-FACING half of the mirroring contract:
     * it normalizes the detected locale into a language the app + platform
     * engine actually support, then applies it via [switchToLocale].
     *
     * Normalization (Principal verification fix — the raw STT path previously
     * bypassed the mirroring rules):
     *  - null / blank / "und" (undetermined) → Locale.US
     *  - ISO 639-3 STT codes: "zlm" → ms-MY, "cmn" → zh (Simplified)
     *  - any "zh*" tag (zh-CN, zh-TW, zh-Hans-CN) → Simplified Chinese
     *  - any "en*" tag (en-GB, en-IN) → Locale.US
     *  - anything else ("ja", "ko", ...) → Locale.US — per product spec,
     *    unknown languages fall back to English instead of diverging the
     *    engine state (platform setLanguage keeps the previous language on
     *    LANG_NOT_SUPPORTED while our tracking field would claim otherwise).
     *
     * Ephemeral by design — never persists to SharedPreferences; the user's
     * explicit Voice Settings choice is untouched. Use [setLanguage] for
     * persistent changes.
     */
    fun mirrorDetectedLocale(detectedLocale: Locale?) {
        val normalized = normalizeMirroredLocale(detectedLocale)
        if (normalized != detectedLocale) {
            Log.i(TAG, "mirrorDetectedLocale: STT $detectedLocale → $normalized")
        }
        switchToLocale(normalized)
    }

    private fun normalizeMirroredLocale(detectedLocale: Locale?): Locale {
        val lang = detectedLocale?.language?.lowercase(Locale.ROOT)
        return when {
            detectedLocale == null || lang.isNullOrBlank() || lang == "und" -> Locale.US
            lang == "zlm" || lang == "ms" -> Locale("ms", "MY")        // Bahasa Malaysia
            lang == "cmn" || lang.startsWith("zh") -> Locale.SIMPLIFIED_CHINESE // Mandarin
            lang == "en" || lang.startsWith("en") -> Locale.US          // English (default)
            else -> Locale.US                                           // unknown → eng fallback
        }
    }

    /**
     * Dynamic language mirroring from STT (speech-to-text) detection.
     *
     * Supported codes (ISO 639-3 and 639-1 both accepted):
     *  - "eng" / "en"  → English (DEFAULT — out-of-the-box state)
     *  - "zlm" / "ms"  → Bahasa Malaysia
     *  - "cmn" / "zh"  → Chinese / Mandarin
     *  - anything else / null / blank → fallback to English
     *
     * NOTE (ephemeral by design): mirroring updates the ACTIVE locale only —
     * it does NOT persist to SharedPreferences. STT-detected language is an
     * ambient, per-conversation signal; the user's explicit Voice Settings
     * choice in prefs must not be silently overwritten by background speech.
     * Call [setLanguage] instead to make a user-intended change persistent.
     */
    fun setLanguageMirroring(languageCode: String?) {
        val normalized = languageCode?.trim()?.lowercase(Locale.ROOT)
        val key = when (normalized) {
            "eng", "en" -> LANGUAGE_ENGLISH
            "zlm", "ms" -> LANGUAGE_MALAY
            "cmn", "zh" -> LANGUAGE_CHINESE
            else -> LANGUAGE_ENGLISH   // null / blank / unknown → default back to eng
        }
        val locale = localeFromKey(key)
        val changed = key != keyFromLocale(currentLocale)
        currentLocale = locale
        if (isInitialized) {
            switchToLocale(locale)
        }

        Log.i(TAG, "setLanguageMirroring: STT code='$languageCode' → language=$key (locale=$locale, " +
            "changed=$changed, ephemeral — user pref untouched)")
    }

    fun setLanguage(languageKey: String, context: Context) {
        val locale = localeFromKey(languageKey)
        currentLocale = locale
        if (isInitialized) {
            switchToLocale(locale)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageKey)
            .apply()

        Log.d(TAG, "TTS language switched to: $currentLocale")
    }

    fun getCurrentLanguageKey(): String = keyFromLocale(currentLocale)

    /**
     * Pick a spoken prompt variant for the ACTIVE TTS language.
     *
     * Status announcements in the gesture flow ("Analyzing scene…",
     * "Listening.", …) must follow the TTS voice — NOT the device locale
     * (R.string follows the device, which silently diverges from the
     * mirrored/persisted voice language). Feeding English text to the Malay
     * or Chinese voice is what produced the "analising sin" mispronunciations.
     *
     * Same contract as the audition prompts: one variant per supported
     * language, keyed on [getCurrentLanguageKey].
     */
    fun localized(english: String, malay: String, chinese: String): String =
        when (getCurrentLanguageKey()) {
            LANGUAGE_MALAY -> malay
            LANGUAGE_CHINESE -> chinese
            else -> english
        }

    fun getCurrentLanguageDisplayName(context: Context): String {
        return when (keyFromLocale(currentLocale)) {
            LANGUAGE_MALAY -> context.getString(R.string.tts_lang_malay)
            LANGUAGE_CHINESE -> context.getString(R.string.tts_lang_chinese)
            else -> context.getString(R.string.tts_lang_english)
        }
    }

    // ── Settings ──────────────────────────────────────────────────

    fun setSpeechRate(rate: Float) {
        cachedRate = rate.coerceIn(0.5f, 2.0f)
        engineRateApplied = cachedRate
        try {
            engine?.setSpeechRate(cachedRate)
        } catch (e: Throwable) {
            Log.w(TAG, "setSpeechRate engine call failed: ${e.message}")
        }
    }

    fun setPitch(pitch: Float) {
        try {
            engine?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        } catch (e: Throwable) {
            Log.w(TAG, "setPitch engine call failed: ${e.message}")
        }
    }

    fun setVolume(volume: Float) {
        cachedVolume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = cachedVolume

    internal fun cachedRate(): Float = cachedRate

    fun applySettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSpeechRate(prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE))
        setPitch(prefs.getFloat(KEY_PITCH, DEFAULT_PITCH))
        setVolume(prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME))
        cachedRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)

        val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
        setLanguage(savedLang, context)
    }

    /**
     * Re-anchor the ACTIVE voice to the user's PERSISTED language choice.
     *
     * WHY THIS EXISTS (2026-09-26 device session): TTSManager is app-scoped,
     * so [currentLocale] survives Activity re-entry — including STT mirroring
     * from the previous session ([mirrorDetectedLocale] is ephemeral in the
     * PERSISTENCE sense only; the in-memory locale it set lives as long as
     * the process). Boot/loading cues then played in the LAST SPOKEN
     * language instead of the stored choice: zh queries at 13:38 → boot cues
     * played Chinese at 13:39 with the preference untouched. Cold starts were
     * never affected (fresh singleton starts at the English default), which
     * is why the bug only appeared after re-entering the app mid-session.
     *
     * Call BEFORE any boot cue on activity/controller re-initialization:
     * startup announcements then follow the persisted preference — English
     * by default, or the language the user explicitly chose in Voice
     * Settings — never the residual session voice. Session mirroring resumes
     * normally with the next spoken query.
     */
    fun reanchorToPersistedLanguage(context: Context) {
        applySettings(context)
        Log.i(TAG, "reanchorToPersistedLanguage: active voice = ${getCurrentLanguageKey()} " +
            "(persisted pref, session mirroring cleared)")
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun onDestroy() {
        stopDrainRetryTimer()
        mainHandler.removeCallbacksAndMessages(null)
        // stop() only — the engine is a shared app-scoped singleton
        // (VyzeApplication + TtsViewModel both hold a TTSManager), so one
        // instance's teardown must not shut down the engine for the other.
        notifyFlushed()
        try {
            engine?.stop()
        } catch (_: Throwable) {}
        pendingUtteranceIds.clear()
        abandonAudioFocus()
        isInitialized = false
        speechBuffer.clear()
        Log.d(TAG, "TTS destroyed")
    }

    /**
     * Full engine shutdown. ONLY for process teardown (VyzeApplication) —
     * per-component teardown must use [onDestroy], which keeps the engine
     * alive for the shared singleton's other holders.
     */
    fun shutdownEngine() {
        onDestroy()
        try {
            engine?.shutdown()
        } catch (_: Throwable) {}
        engine = null
        Log.i(TAG, "shutdownEngine: platform TextToSpeech released")
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun localeFromKey(key: String): Locale {
        return when (key) {
            LANGUAGE_MALAY -> Locale("ms", "MY")
            LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.US
        }
    }

    private fun keyFromLocale(locale: Locale): String {
        return when (locale.language) {
            "ms" -> LANGUAGE_MALAY
            "zh" -> LANGUAGE_CHINESE
            else -> LANGUAGE_ENGLISH
        }
    }

    companion object {
        private const val TAG = "[TTSManager]"

        /**
         * Prefixes that mark a letter+digit token as currency/quantity, not an
         * identifier code — "RM12.90" must stay "twelve ringgit", while
         * "QLB 3469" must be spelled out.
         */
        private val CURRENCY_AND_UNIT_PREFIXES = setOf(
            "RM", "RP", "SGD", "USD", "EUR", "GBP", "IDR", "MYR", "HKD",
            "NT", "RS", "R", "US", "AU", "NZ", "CA", "HK", "S",
            "KG", "CM", "MM", "KM", "ML", "MG", "OZ", "LB", "NO", "NUM"
        )

        @Volatile
        private var instance: TTSManager? = null

        fun getInstance(context: Context): TTSManager {
            return instance ?: synchronized(this) {
                instance ?: TTSManager(context.applicationContext).also { instance = it }
            }
        }

        const val PREFS_NAME = "vyze_tts_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_LANGUAGE = "tts_language"
        const val KEY_VOICE_NAME = "tts_voice_name"
        const val VOICE_AUTO = "automatic"
        const val KEY_VOICE_PROMPT_RESOLVED = "voice_prompt_resolved"
        const val KEY_VOICE_SETTINGS_KNOWN = "voice_settings_known"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 1.0f
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_MALAY = "ms"
        const val LANGUAGE_CHINESE = "zh"
        val SUPPORTED_LANGUAGES = listOf(LANGUAGE_ENGLISH, LANGUAGE_MALAY, LANGUAGE_CHINESE)

        fun storedLanguageLocale(context: android.content.Context): Locale {
            val key = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
            return when (key) {
                LANGUAGE_MALAY -> Locale("ms", "MY")
                LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                else -> Locale.US
            }
        }

        const val DEBOUNCE_MS = 1500L

        /** Sentence flushes pause above this many pending utterances (Q3). */
        const val MAX_PENDING_UTTERANCES = 3

        /** Speech-rate multiplier for safety-critical readouts (money, medication). */
        private const val CRITICAL_INFO_RATE_FACTOR = 0.85f

        const val ENGINE_SETTLE_DELAY_MS = 200L
        const val DRAIN_RETRY_INTERVAL_MS = 200L
        const val DRAIN_RETRY_MS = 5000L

        /** Google TTS engine package — forced per whitepaper §7.2. */
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        private val ENGLISH_PRONUNCIATION_OVERRIDES = mapOf(
            "maggi" to "Mayghee"
        )

        /**
         * Phonetic respellings applied when the ACTIVE TTS voice is MALAY.
         * Google's ms-MY voice is a thin English-leaning pack: plain English
         * loans come out as letter-by-letter, disconnected sounds (the
         * "analising sin" bug). Respelling the words in Malay orthography
         * forces the engine to blend them into one fluid sound.
         *
         * Example: "analyzing screen" → "analising sin" — exactly how the
         * engine should SAY it. Keep entries lowercase; matching is
         * whole-word and case-insensitive.
         */
        private val MALAY_PHONETIC_RESPELLINGS = mapOf(
            "analyzing" to "analising",
            "analyze" to "analisis",
            "screen" to "skrin",
            "camera" to "kamera",
            "image" to "imej",
            "photo" to "foto",
            "object" to "objek",
            "battery" to "bateri"
        )
        private const val WARM_PITCH = 0.96f
        private const val WARM_RATE = 0.98f
    }
}

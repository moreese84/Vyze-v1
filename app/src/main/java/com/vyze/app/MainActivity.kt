package com.vyze.app
import com.vyze.app.util.CrashLogFile
import com.vyze.app.ui.MainViewModel
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.ui.TtsViewModel
import com.vyze.app.device.AudioCapture
import com.vyze.app.ui.SplashViewModel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import com.vyze.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main entry point into the Vyze app. Single-activity pattern with
 * Navigation component hosting all fragments.
 *
 * ## Accessibility Flow
 * - Onboarding fires AFTER VLM model is ready (deferred, not on TTS init)
 * - Barge-in: any user touch or mic trigger instantly silences TTS
 * - Single-output: speak VLM result once, then go to IDLE (no infinite loop)
 * - Timeouts reset to IDLE without spoken error loops
 *
 * ## State Machine
 * IDLE → listening → (speech result) → analyzing → (VLM result) → speaking → IDLE
 */
class MainActivity : AppCompatActivity() {

    private var activityMainBinding: ActivityMainBinding? = null
    private val viewModel: MainViewModel by viewModels()
    private val ttsViewModel: TtsViewModel by viewModels()
    private var hapticManager: HapticManager? = null

    // ── TTS ───────────────────────────────────────────────────────

    private val ttsManager: TTSManager by lazy { ttsViewModel.ttsManager }
    private var ttsReady = false
    var talkBackDetected = false
        private set

    /** Clear the detected-TalkBack flag once the user confirms TalkBack is off. */
    fun clearTalkBackDetected() {
        talkBackDetected = false
    }

    // ── Speech-to-Text ────────────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /**
     * True while the fragment WANTS the mic open (a voice session, the voice
     * audition, report mode). Cleared the instant the user aborts with a tap
     * or the session ends. When false, stale recognizer callbacks from the
     * just-cancelled session are dropped at the source instead of reaching
     * the fragment — this is what caused the phantom "I did not catch that"
     * speech that used to precede tap results when switching between single
     * tap and double tap.
     */
    @Volatile
    var voiceSessionWanted = false

    // ── Noise Robustness (Tier 1) ────────────────────────────────
    // L1: Adaptive restart backoff — grows between failed recognition
    // cycles so the recognizer doesn't beep-loop in noisy rooms.
    // L3: Chatter counter — when ambient conversation keeps getting
    // rejected, pause free-form listening until the user taps.
    private var retryIndex = 0

    @Volatile
    private var noisePaused = false

    private var rejectedCycleCount = 0

    /** Last partial transcription from the active session (L2 stability check). */
    @Volatile
    private var lastPartialText: String = ""

    // ── Tier 2: Model-Native ASR Rescue ──────────────────────────
    // When Android's SpeechRecognizer fails in a noisy room (NO_MATCH,
    // SPEECH_TIMEOUT, ERROR_AUDIO), Vyze rescues the query with Gemma 4
    // E2B's NATIVE audio encoder: it records the user's speech directly
    // and transcribes it fully offline — no Google services, no network.
    private val asrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Prevent fallback loops — only one model-ASR attempt per session. */
    @Volatile
    private var asrFallbackTried = false

    /**
     * Suspect-transcript ladder retries consumed THIS session. Kept separate
     * from [localeFallbackIndex] (which the NO_MATCH ladder advances) so the
     * two retry paths can never combine into an unbounded loop. Reset on
     * every fresh session and whenever a transcription is accepted.
     */
    @Volatile
    private var suspectLadderRetries = 0

    /**
     * The transcript that triggered the suspect ladder — held while the
     * ladder retry session runs. If the retry fails (NO_MATCH, silence,
     * error), the ORIGINAL transcript is delivered instead of an error: it
     * passed the chatter filter, so the user demonstrably spoke and a
     * possibly-wrong-language answer beats losing the query. The retry
     * captures NEW audio (the recognizer cannot replay the old session), so
     * this fallback is what keeps latency bounded and the query safe.
     */
    @Volatile
    private var pendingSuspectTranscript: String? = null

    /**
     * True once the recognizer heard the user BEGIN speaking in the current
     * session (onBeginningOfSpeech). The model-ASR rescue is ONLY allowed for
     * speech that was attempted but failed (the noisy-room case). Pure silence
     * timeouts must never trigger the "Please say that again" rescue — doing so
     * hijacked quiet pauses and made double-tap sessions appear dead.
     */
    @Volatile
    private var speechAttempted = false

    /**
     * False while the hands-free voice audition is running — the rescue's
     * "Please say that again" cue must never interrupt audition samples.
     * Toggled by the fragment when the audition starts/stops.
     */
    @Volatile
    var modelAsrRescueAllowed = true

    /** Last locale Google's recognizer reported — reused for the model-ASR result. */
    @Volatile
    private var lastDetectedLocale: java.util.Locale? = null

    /**
     * Index into [FALLBACK_RECOGNITION_LOCALES] for the locale-fallback ladder.
     * When an English-pinned session fails (NO_MATCH / low-confidence — the
     * classic first-contact Malay/Chinese "no response" bug), the session is
     * silently retried in the next language of the ladder before giving up.
     * Reset when a transcription is accepted or the session is user-aborted.
     */
    private var localeFallbackIndex = 0

    /**
     * Language tag the CURRENT recognizer session is pinned to ("en-US",
     * "ms-MY", "zh-CN"), or null when the session is unpinned (auto-detect).
     * The NO_MATCH ladder skips this locale — re-running the language that
     * just failed would only reproduce the same NO_MATCH.
     */
    private var lastPinnedLocaleTag: String? = null

    /**
     * Callback invoked when speech recognition completes with final text +
     * detected language + recognizer confidence (0.0–1.0). Confidence lets
     * the fragment run a "Did you say X?" confirmation on grey-band
     * transcripts instead of burning an inference on a probable mis-hear.
     * The model-ASR rescue passes 0 — that path already asked the user to
     * repeat once, so it must never chain another confirmation ask.
     */
    var onSpeechResult: ((String, java.util.Locale?, Float) -> Unit)? = null

    /** Callback invoked with partial (live) transcription text for UI feedback. */
    var onPartialSpeechResult: ((String) -> Unit)? = null

    /** Callback for speech recognition errors (non-fatal). */
    var onSpeechError: ((String) -> Unit)? = null

    /** Invoked when repeated rejected cycles indicate a noisy room (Tier 1 L3). */
    var onNoiseDetected: (() -> Unit)? = null

    /** Permission launcher for RECORD_AUDIO at runtime. */
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "RECORD_AUDIO granted — starting listening")
                startListeningInternal()
            } else {
                Log.w(TAG, "RECORD_AUDIO denied")
                Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_LONG).show()
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        var superCalled = false

        try {
            CrashLogFile.log(TAG, "onCreate start")

            val splashScreen = installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !SplashViewModel.isMlReady }

            super.onCreate(savedInstanceState)
            superCalled = true

            try {
                hapticManager = HapticManager(applicationContext)
                hapticManager?.vibrateTap()
            } catch (e: Throwable) {
                Log.e(TAG, "HapticManager init failed: ${e.message}")
            }

            activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(activityMainBinding!!.root)

            try {
                supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
            } catch (e: Throwable) {
                Log.e(TAG, "NavHostFragment lookup failed: ${e.message}")
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            })

            initSpeechRecognizer()
            initTts()

            // Hold permanent audio focus for the entire session (like Google Lens / Be My Eyes)
            // This suppresses TalkBack and other accessibility audio while Vyze is active.
            mainHandler.postDelayed({
                ttsManager.holdSessionFocus()
                detectTalkBack()
            }, 500L)

            CrashLogFile.log(TAG, "onCreate completed successfully")

        } catch (e: Throwable) {
            Log.e(TAG, "FATAL onCreate crash: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "FATAL onCreate crash", e)
            CrashLogFile.flush()

            if (!superCalled) {
                try {
                    super.onCreate(savedInstanceState)
                    superCalled = true
                } catch (_: Throwable) {
                    Log.e(TAG, "super.onCreate() failed in catch block — unrecoverable")
                    return
                }
            }

            try {
                val errorText = buildString {
                    appendLine("VYZE LAUNCH ERROR")
                    appendLine()
                    appendLine("${e.javaClass.simpleName}: ${e.message}")
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(e.stackTraceToString())
                }

                val scrollView = ScrollView(this)
                val textView = TextView(this).apply {
                    text = errorText
                    setTextColor(Color.RED)
                    setBackgroundColor(Color.BLACK)
                    textSize = 12f
                    setPadding(32, 32, 32, 32)
                    setOnLongClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", errorText)
                        )
                        Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                scrollView.addView(textView)
                setContentView(scrollView)
            } catch (e2: Throwable) {
                Log.e(TAG, "Error screen itself crashed: ${e2.message}")
                try {
                    Toast.makeText(this, "VYZE CRASH: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onDestroy() {
        destroySpeechRecognizer()
        ttsManager.releaseSessionFocus()
        super.onDestroy()
        hapticManager?.cancel()

        // Note: Process kill removed. The previous killProcess(myPid()) call
        // conflicted with the error-screen catch block in onCreate: when the
        // user pressed Back on the error screen, onDestroy ran with isFinishing=true
        // and killed the process — making it impossible to read the crash details
        // and producing an EXIT_SELF / status=255 in dumpsys.
        // GPU/LiteRT/CameraX resources are reclaimed by the OS on process death
        // or by the next cold start.
    }

    // ── TTS Setup ────────────────────────────────────────────────

    /**
     * Initialize TTS — no onboarding here.
     * Onboarding is triggered by CameraFragment after VLM model is ready.
     */
    private fun initTts() {
        if (ttsManager.isReady()) {
            ttsReady = true
        } else {
            ttsManager.onReady = { ttsReady = true }
        }
    }

    /**
     * True when TalkBack (or another touch-exploration screen reader) is
     * enabled. Touch exploration means the screen reader intercepts taps,
     * which conflicts with Vyze's gesture map — the user should disable
     * it while using Vyze.
     */
    fun isTalkBackEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            am != null && am.isEnabled && am.isTouchExplorationEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "TalkBack detection failed: ${e.message}")
            false
        }
    }

    /**
     * Detect if TalkBack is enabled and warn the user once.
     * Vyze cannot duck or pause TalkBack (Android forbids it), so if the user
     * has TalkBack enabled we advise them to turn it off for the best
     * experience — or open Accessibility Settings for them on request.
     */
    private fun detectTalkBack() {
        if (isTalkBackEnabled()) {
            Log.i(TAG, "TalkBack detected — announcing advisory")
            CrashLogFile.log(TAG, "TalkBack enabled — advisory spoken")
            // One-time advisory after model is ready (handled by CameraFragment onboarding)
            // Store flag so CameraFragment can include the advisory in its onboarding message
            talkBackDetected = true
        }
    }

    /**
     * Open the system Accessibility Settings screen so the user can toggle
     * TalkBack off (TalkBack still works on that screen, so they can navigate
     * it). Returns false if the screen is unavailable on this device.
     */
    fun openAccessibilitySettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.i(TAG, "Opened Accessibility Settings")
                true
            } else {
                Log.w(TAG, "No Accessibility Settings screen available")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open Accessibility Settings: ${e.message}")
            false
        }
    }

    /**
     * Play the onboarding announcement. Called by CameraFragment when VLM is ready.
     * Speaks once, then returns to IDLE — no auto-listening restart.
     */
    fun playOnboardingSpeech() {
        if (!ttsReady) {
            Log.w(TAG, "playOnboardingSpeech called but TTS not ready")
            return
        }

        Log.i(TAG, "Playing onboarding announcement")
        CrashLogFile.log(TAG, "Playing onboarding announcement")

        ttsManager.speakImmediate(
            ttsManager.localized(
                "Vyze model ready. Tap anywhere or speak to ask a question, " +
                "such as what is in front of me. Tap again to interrupt or ask a new question.",
                "Model Vyze sedia. Sentuh di mana-mana atau bercakap untuk bertanya soalan, " +
                "contohnya apa yang ada di hadapan saya. Sentuh lagi untuk ganggu atau tanya soalan baharu.",
                "Vyze模型已就绪。点击任意位置或说话提问，例如我前面有什么。再次点击可打断或提出新问题。"
            )
        )
    }

    /**
     * Speak text and invoke a callback when TTS finishes.
     * Used to speak VLM output. Does NOT restart listening — returns to IDLE.
     *
     * Phase 1 FIX (listener-overwrite race): this used to REPLACE the
     * engine-global UtteranceProgressListener on every call. The platform
     * engine has exactly one listener slot, so when two speech flows
     * overlapped (answer streaming + a follow-up cue), the first flow's
     * utterances delivered their onDone to the SECOND flow's listener —
     * firing the wrong flow's callback: premature IDLE, the mic reopened
     * mid-answer, and the recognizer captured the answer's tail as a
     * phantom query. Callbacks now register per-utterance inside TTSManager
     * and can never cross-deliver.
     */
    fun speakThenCallback(text: String, onDone: () -> Unit) {
        if (!ttsReady || text.isBlank()) {
            onDone()
            return
        }

        // Barge-in: stop any current speech first. The flushed utterances
        // report onError to their OWN registered callbacks — each waiting
        // flow resumes independently.
        ttsManager.stop()

        // One-shot guard: onDone and onError must fire exactly once, even if
        // the engine sends both terminal events.
        var fired = false
        val deliver: () -> Unit = {
            if (!fired) {
                fired = true
                runOnUiThread { onDone() }
            }
        }
        val accepted = ttsManager.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            onDone = deliver,
            onError = deliver
        )
        Log.d(TAG, "speakThenCallback: submission ${if (accepted) "OK" else "REJECTED (onDone already delivered)"}")
    }

    /**
     * Speak a short announcement without waiting for completion.
     * Used for status updates like "Analyzing scene..."
     */
    fun announceStatus(text: String) {
        if (!ttsReady || text.isBlank()) return
        ttsManager.speakImmediate(text)
    }

    /**
     * Barge-in: immediately silence any active TTS output.
     * Called on any user touch, mic trigger, or incoming spoken prompt.
     */
    fun interruptTts() {
        if (ttsReady && ttsManager.isSpeaking()) {
            Log.d(TAG, "Barge-in: stopping TTS")
            ttsManager.stop()
        }
    }

    /**
     * Barge-in with mic pause: stops TTS AND pauses the microphone
     * for [settleMs] milliseconds so the physical tap sound on glass
     * is not captured as a false audio intent.
     * After the settle period the mic automatically resumes.
     */
    fun interruptTtsWithMicPause(settleMs: Long = 100L) {
        // 0. Cancel SpeechRecognizer session to avoid hw conflict with ImageCapture
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                isListening = false
            } catch (_: Throwable) {}
        }
        // 1. Stop TTS immediately
        interruptTts()
        // 2. Resume mic after settle delay (thread-safe)
        mainHandler.postDelayed({ startListeningSafely() }, settleMs)
    }

    // ── Speech Recognizer Setup ───────────────────────────────────

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun initSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "Speech recognition not available on this device")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "SpeechRecognizer init failed: ${e.javaClass.simpleName}: ${e.message}")
            speechRecognizer = null
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            Log.d(TAG, "SpeechRecognizer destroyed")
        } catch (e: Throwable) {
            Log.w(TAG, "SpeechRecognizer destroy failed: ${e.message}")
            speechRecognizer = null
            isListening = false
        }
    }

    // ── Noise Robustness Helpers (Tier 1) ─────────────────────────

    /** L1: schedule the next mic restart with adaptive backoff. */
    private fun scheduleAdaptiveRestart() {
        if (noisePaused) {
            Log.d(TAG, "scheduleAdaptiveRestart: noise pause active — not restarting")
            return
        }
        val delay = RETRY_RAMP_MS[retryIndex]
        if (retryIndex < RETRY_RAMP_MS.size - 1) retryIndex++
        Log.d(TAG, "Adaptive mic restart in ${delay}ms (ramp=$retryIndex)")
        mainHandler.postDelayed({ startListeningSafely() }, delay)
    }

    /** L1: reset the backoff ramp (real query accepted or user tapped). */
    fun resetRetryBackoff() {
        retryIndex = 0
        Log.d(TAG, "Retry backoff reset to ${RETRY_RAMP_MS[0]}ms")
    }

    /** L3: count a rejected ambient-chatter cycle; pause listening at threshold. */
    private fun registerRejectedCycle() {
        rejectedCycleCount++
        Log.d(TAG, "Rejected cycle #$rejectedCycleCount (threshold $NOISE_DETECTION_THRESHOLD)")
        if (rejectedCycleCount >= NOISE_DETECTION_THRESHOLD) {
            noisePaused = true
            rejectedCycleCount = 0
            Log.w(TAG, "Noise detected — pausing free-form listening until user taps")
            CrashLogFile.log(TAG, "NOISE PAUSE triggered: $NOISE_DETECTION_THRESHOLD rejected cycles")
            mainHandler.post { onNoiseDetected?.invoke() }
        } else {
            scheduleAdaptiveRestart()
        }
    }

    /** Clear the noise pause + backoff — called when the user taps. */
    fun resumeAfterNoisePause() {
        noisePaused = false
        rejectedCycleCount = 0
        resetRetryBackoff()
        Log.i(TAG, "Noise pause cleared — listening can resume")
    }

    /**
     * Restore the one-shot model-ASR rescue budget. Called when a fresh
     * hands-free voice session opens (double tap / voice audition / report), so
     * each conversation gets one offline rescue when genuine speech fails.
     */
    fun resetModelAsrBudget() {
        asrFallbackTried = false
    }

    // ── Tier 2: Model-Native ASR Rescue ────────────────────────────

    /**
     * Rescue a failed recognition with Gemma 4 E2B's NATIVE audio encoder.
     *
     * Called when Android's SpeechRecognizer gives up (NO_MATCH,
     * SPEECH_TIMEOUT, ERROR_AUDIO) — the classic "query lost in a noisy
     * room" case. Vyze speaks a short cue, records the user's repeat with
     * [AudioCapture], and transcribes it fully offline via the model.
     *
     * @return true if the rescue path is running (caller must NOT end the
     *         session); false if the rescue is unavailable and the caller
     *         should fall through to normal error handling.
     */
    private fun attemptModelAsrRescue(originalError: String): Boolean {
        if (!modelAsrRescueAllowed) {
            Log.d(TAG, "Model-ASR rescue suppressed (e.g. voice audition active)")
            return false
        }
        if (asrFallbackTried) {
            Log.d(TAG, "Model-ASR rescue already attempted this session — skipping")
            return false
        }
        asrFallbackTried = true

        val core = (application as? VyzeApplication)?.coreController
        if (core == null || !core.isEngineReady() || core.isCurrentlyInferring()) {
            Log.d(TAG, "Model-ASR rescue unavailable (core=${core != null}, " +
                "ready=${core?.isEngineReady()}, inferring=${core?.isCurrentlyInferring()})")
            return false
        }

        Log.i(TAG, "SpeechRecognizer failed — launching model-native ASR rescue")
        CrashLogFile.log(TAG, "MODEL-ASR RESCUE: $originalError")

        // Cue the user to repeat, then record + transcribe on the IO scope.
        // speakThenCallback fires onDone on the UI thread.
        speakThenCallback(
            ttsManager.localized(
                "Please say that again.",
                "Sila sebut sekali lagi.",
                "请再说一遍。"
            )
        ) {
            asrScope.launch {
                try {
                    val audio = AudioCapture.recordSpeech()
                    if (audio == null) {
                        Log.w(TAG, "Model-ASR rescue: audio capture failed")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    // ── RE-CHECK BEFORE TRANSCRIBING ──────────────
                    // The readiness guard above ran BEFORE the spoken cue and the
                    // multi-second recording. A tap analysis or continuous
                    // snapshot may have started since — transcribing now would
                    // collide with the running inference (the engine runs one
                    // native generation at a time). Also bail if the user tapped
                    // away while we were recording.
                    if (!voiceSessionWanted) {
                        Log.d(TAG, "Model-ASR rescue: session aborted during recording — dropping")
                        return@launch
                    }
                    if (core.isCurrentlyInferring()) {
                        Log.d(TAG, "Model-ASR rescue: inference started during recording — aborting rescue")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    val transcription = core.transcribeAudio(audio)?.trim()
                    if (transcription.isNullOrBlank()) {
                        Log.w(TAG, "Model-ASR rescue: nothing understood")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    Log.i(TAG, "Model-ASR transcription: \"$transcription\"")
                    CrashLogFile.log(TAG, "MODEL-ASR transcription: \"$transcription\"")
                    runOnUiThread {
                        // The user may have tapped away while the rescue was
                        // recording (e.g. they chose a tap instead of repeating)
                        // — a late transcription must not fire as a fresh query.
                        if (!voiceSessionWanted) {
                            Log.d(TAG, "Model-ASR transcription after session aborted — dropping")
                            return@runOnUiThread
                        }
                        // Language FIX: detect the locale from the rescue
                        // TRANSCRIPTION itself. The old code reused
                        // lastDetectedLocale — typically the recognizer's own
                        // default (en) or a stale value from an earlier session
                        // — so a Malay/Chinese query rescued by the model-ASR
                        // path always came back English, breaking mirroring.
                        // detectLocaleFromText applies the same CJK/Malay
                        // detection used by the normal onResults path.
                        // Also persist the rescue locale for recognition:
                        // resolveRecognitionLocale() reads it, so the NEXT mic
                        // session listens in the language the rescue actually
                        // heard instead of falling back to en-US again.
                        val rescueLocale = detectLocaleFromText(transcription)
                        lastDetectedLocale = rescueLocale
                        // Confidence 0: the rescue already asked the user to
                        // repeat once — never chain another confirmation ask.
                        onSpeechResult?.invoke(transcription, rescueLocale, 0f)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Model-ASR rescue crashed: ${e.message}")
                    finishRescueWithError(originalError)
                }
            }
        }
        return true
    }

    /**
     * SUSPECT-TRANSCRIPT AUDIO REPLAY (Phase 3, attempt 2).
     *
     * The recognizer produced a suspect transcript (wrong-language garble)
     * and the one re-listen failed or is not available. The session's
     * captured PCM (retained by [AudioCapture]) is sent to Gemma 4 E2B's
     * NATIVE audio encoder — language-agnostic, fully offline — so the user
     * does NOT have to repeat. On success the transcription is delivered
     * exactly like a model-ASR rescue result. Returns true when the replay
     * is running (caller must not end the session).
     */
    private fun attemptSuspectAudioReplay(): Boolean {
        val core = (application as? VyzeApplication)?.coreController
        val audio = AudioCapture.lastCapture
        if (core == null || !core.isEngineReady() || core.isCurrentlyInferring()) {
            Log.d(TAG, "Suspect audio replay unavailable (core=${core != null}, " +
                "ready=${core?.isEngineReady()}, inferring=${core?.isCurrentlyInferring()})")
            return false
        }
        if (audio == null) {
            Log.d(TAG, "Suspect audio replay: no retained capture — skipping")
            return false
        }
        Log.i(TAG, "Suspect audio replay: transcribing ${audio.size} bytes offline (Gemma audio encoder)")
        CrashLogFile.log(TAG, "SUSPECT AUDIO REPLAY: ${audio.size} bytes")
        asrScope.launch {
            try {
                // The engine runs one native generation at a time — a tap
                // analysis may have started since; also honor session aborts.
                if (!voiceSessionWanted) {
                    Log.d(TAG, "Suspect audio replay: session aborted — dropping")
                    return@launch
                }
                if (core.isCurrentlyInferring()) {
                    Log.d(TAG, "Suspect audio replay: inference started — aborting replay")
                    finishRescueWithError("No speech detected.")
                    return@launch
                }
                val transcription = core.transcribeAudio(audio)?.trim()
                if (transcription.isNullOrBlank()) {
                    Log.w(TAG, "Suspect audio replay: nothing understood")
                    finishRescueWithError("No speech detected.")
                    return@launch
                }
                Log.i(TAG, "Suspect audio replay transcription: \"$transcription\"")
                CrashLogFile.log(TAG, "SUSPECT AUDIO REPLAY result: \"$transcription\"")
                runOnUiThread {
                    if (!voiceSessionWanted) {
                        Log.d(TAG, "Suspect audio replay result after session aborted — dropping")
                        return@runOnUiThread
                    }
                    pendingSuspectTranscript = null
                    localeFallbackIndex = 0
                    val replayLocale = detectLocaleFromText(transcription)
                    lastDetectedLocale = replayLocale
                    // Confidence 0: never chain a confirmation ask on top of
                    // the retries the transcript already went through.
                    onSpeechResult?.invoke(transcription, replayLocale, 0f)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Suspect audio replay crashed: ${e.message}")
                finishRescueWithError("No speech detected.")
            }
        }
        return true
    }

    /** Fall through to the original speech error after a failed rescue. */
    private fun finishRescueWithError(originalError: String) {
        runOnUiThread {
            // The user may have tapped away while the rescue was recording —
            // then this error belongs to the dead session and must not reach
            // the fragment (it would clobber the tap's analysis state).
            if (!voiceSessionWanted) {
                Log.d(TAG, "Model-ASR rescue failed after session aborted — dropping error")
                return@runOnUiThread
            }
            onPartialSpeechResult?.invoke("")
            onSpeechError?.invoke(originalError)
        }
    }

    /** True when [s] contains any CJK ideograph (Chinese text has no spaces). */
    private fun hasCjkCharacters(s: String): Boolean =
        s.any { ch ->
            val cp = ch.code
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
        }

    /** L2: decide whether a transcription is ambient conversation, not the user. */
    private fun isAmbientChat(text: String, confidence: FloatArray?): Boolean {
        // 1. Fragmentary single-word results ("yeah", "okay", "hi") — pass-over chatter
        val trimmed = text.trim()
        if (trimmed.split(Regex("\\s+")).size == 1 && trimmed.length < MIN_SINGLE_WORD_CHARS) {
            // Language FIX: CJK exempt — "你好" (2 chars) is a complete,
            // meaningful query. CJK characters carry several times the
            // information of Latin ones and the recognizer returns Chinese
            // WITHOUT spaces, so every short Chinese query looked like a
            // fragmentary single word and was dropped as ambient chatter
            // (the reported "Chinese → no response" symptom).
            if (!hasCjkCharacters(trimmed)) {
                return true
            }
        }
        // 2. Low recognition confidence — mumbles and mixed chatter score low.
        // Language FIX: ms/zh transcriptions are EXEMPT — on an English-default
        // phone the recognizer scores genuinely spoken Malay/Chinese low, and
        // dropping them reproduced the "no response" bug.
        if (confidence != null && confidence.isNotEmpty()) {
            val score = confidence.firstOrNull() ?: return false
            if (score in 0.0f..1.0f && score < MIN_CONFIDENCE &&
                detectLocaleFromText(text).language !in listOf("ms", "zh")
            ) {
                return true
            }
        }
        // 3. Unstable transcription: the final text shares no words with the
        //    partial stream — a sign the recognizer latched onto a different speaker.
        // Language FIX: CJK text has no spaces — word-splitting yields ONE
        // giant token per string, and a progressive recognizer's partial is
        // a PREFIX of the final ("这是" → "这是什么"), so the word-overlap
        // test rejected EVERY multi-stage Chinese recognition as unstable.
        // Compare characters for CJK instead of words.
        val partial = lastPartialText
        if (partial.isNotBlank() && partial.length >= 4 && trimmed.length >= 4) {
            if (hasCjkCharacters(trimmed)) {
                val finalChars = trimmed.toSet()
                if (partial.none { it in finalChars }) {
                    return true
                }
            } else {
                val finalWords = trimmed.lowercase().split(Regex("\\s+")).toSet()
                val partialWords = partial.lowercase().split(Regex("\\s+")).toSet()
                if (finalWords.none { it in partialWords }) {
                    return true
                }
            }
        }
        return false
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Start listening for voice input.
     * Barge-in: stops any active TTS before opening the microphone.
     */
    /**
     * Thread-safe method to start speech recognition.
     * Detect language from transcribed text using Unicode character ranges.
     * Fallback for devices where SpeechRecognizer doesn't return EXTRA_LANGUAGE.
     */
    private fun detectLocaleFromText(
        text: String,
        rescueImplausibleAsMsZh: Boolean = false,
    ): java.util.Locale {
        if (text.isBlank()) return java.util.Locale.US

        val lower = text.lowercase()
        val words = lower.split(Regex("\\s+"))

        // ── Step 1: CJK (Chinese) detection ────────────────────
        var cjkCount = 0
        var totalLetters = 0
        for (ch in text) {
            if (ch.isLetter()) totalLetters++
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF) {
                cjkCount++
            }
        }
        if (totalLetters > 0 && cjkCount.toFloat() / totalLetters > 0.3f) {
            Log.d(TAG, "detectLocaleFromText: CJK detected ($cjkCount/$totalLetters) → zh")
            return java.util.Locale.CHINESE
        }

        // ── Step 2: Malay detection (multi-signal scoring) ──────
        // Malay uses Latin script so it can't be distinguished from English
        // by script alone. We use a weighted scoring system:
        //
        // Signal A: High-frequency Malay function words (appear in almost
        //           every Malay sentence — the most reliable signal)
        // Signal B: Malay morphological suffixes (unique to Malay grammar)
        // Signal C: Malay-specific word patterns
        // Signal D: Malay reduplication (very common, e.g. "rumah-rumah")
        //
        // A score of 3+ is sufficient to identify Malay. English rarely
        // scores above 1 because Malay function words don't exist in English.

        var malayScore = 0

        // Signal A: High-frequency function words
        // These appear in virtually every Malay sentence and never in English.
        // (v2: followed by Signal A2 ASR-garble aliases and Signal A3
        // fused-word evidence — see the device audit findings.)
        val malayFunctionWords = setOf(
            "saya", "kami", "kita", "anda", "kamu", "mereka",  // pronouns
            "tidak", "tak", "bukan", "jangan", "belum",        // negation
            "dan", "atau", "tapi", "kerana", "sebab",          // conjunctions
            "ini", "itu", "sini", "situ", "sono",              // demonstratives
            "yang", "adalah", "ialah",                            // copula/relativizer
            "di", "ke", "dari", "dengan", "untuk",            // prepositions
            "dalam", "atas", "bawah", "antara", "sebelum",    // spatial/temporal
            "sudah", "sedang", "akan", "baru", "lagi",        // tense/aspect
            "boleh", "mahu", "nak", "perlu", "mesti",         // modals
            "ini", "apa", "siapa", "mana", "kenapa",          // question words
            "pula",                                              // follow-up marker ("X pula?" = "what about X?")
            "bila", "berapa", "mengapa",
            "ada", "depan", "belakang"                        // Phase 3: short-query coverage
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            if (cleaned in malayFunctionWords) {
                malayScore += 2  // high confidence signal
            }
        }

        // Signal A2: ASR-garble aliases (v2 — device audit ir_6).
        // On English-default phones the recognizer mangles Malay speech into
        // near-misses the exact-match lookup above cannot see: the real query
        // "inipula appa" (Ini pula apa?) scored ZERO — every Malay token was
        // corrupted — and the answer came back English. Aliases stay narrow
        // (exact garble forms observed in the wild); no fuzzy similarity, to
        // avoid false-positive English matches.
        val malayGarbleAliases = mapOf(
            "appa" to "apa",   // 'apa' through an English acoustic model (ir_6)
            "apah" to "apa",   // aspirated variant of the same failure
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            if (malayGarbleAliases.containsKey(cleaned)) {
                malayScore += 2
            }
        }

        // Signal A3: fused-word evidence (v2 — same audit row).
        // ASR often merges Malay words without spaces ("inipula" = "ini pula")
        // or transcribes run-together speech as one token. A Malay function
        // word found INSIDE a longer token scores +1 (capped at 2 total) —
        // enough to rescue "inipula" alongside an alias hit, too weak for a
        // single English word that merely contains a short Malay string.
        if (malayScore < 4) {
            for (word in words) {
                val cleaned = word.replace(Regex("[^a-z]"), "")
                if (cleaned.length < 5) continue
                val fused = malayFunctionWords.any { fn ->
                    fn.length >= 3 && cleaned != fn && cleaned.contains(fn)
                }
                if (fused) {
                    malayScore += 1
                    if (malayScore >= 4) break
                }
            }
        }

        // Signal B: Malay morphological suffixes
        // Malay is agglutinative — these suffixes are grammatical markers
        // that don't appear in English.
        // Language FIX: the original patterns included a leading '-'
        // ("-kan") which never matched — word.endsWith("-kan") is false for
        // "buatkan". Every Malay word scored 0 on this signal, so short
        // natural queries ("apa ini", "baca label") hovered below the old
        // threshold of 3 and fell through to English.
        val malaySuffixes = listOf(
            "kan", "an", "i", "lah", "kah", "tah",
            "nya", "pun"
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            // Suffixes like "an"/"i" also occur in English words; only
            // count them on words of realistic Malay root length.
            if (malaySuffixes.any { cleaned.length >= 4 && cleaned.endsWith(it) }) {
                malayScore += 1
            }
        }

        // Signal C: Malay-specific word patterns
        // These are words unique to Malay that don't exist in English.
        // Language FIX: split into DECISIVE action/noun words (+2) and
        // descriptive words (+1). Real Vyze queries are often verb-only
        // ("baca label", "analisis scene", "tolong tengok") — under the old
        // flat +1 they scored below the threshold and the answer came back
        // English, exactly the reported double-tap mirroring failure. Every
        // +2 word is impossible in ordinary English speech ("wang" was
        // deliberately EXCLUDED — it is also a common Chinese surname).
        val malayStrongPatterns = listOf(
            "baca", "tolong", "analisis", "tengok", "tunjuk", "tunjukkan",
            "lihat", "cari", "dengar", "cakap", "bagitahu",
            "hasil", "gambar", "kamera", "warna", "harga", "duit"
        )
        val malayPatterns = listOf(
            "selamat", "terima", "kasih",
            "macam", "bahasa", "malaysia",
            "rumah", "makan", "minum", "jalan",
            "kenal", "paham", "faham",
            "pergi", "datang", "balik",
            "besar", "kecil", "cantik", "bagus",
            "panas", "sejuk", "hujan", "cerah",
            "hari", "malam", "pagi", "petang",
            "orang", "anak", "ibu", "bapa",
            "objek", "benda", "senario", "teks",
            "semua", "sekarang", "sikit", "banyak", "sama", "ada"
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            when {
                cleaned in malayStrongPatterns -> malayScore += 2
                cleaned in malayPatterns -> malayScore += 1
            }
        }

        // Signal D: Malay reduplication
        // Very common in Malay (e.g., "rumah-rumah", "anak-anak",
        // "sikit-sikit", "satu-satu"). English almost never reduplicates.
        // Language FIX: the old pattern "(\\b\\w+)-(\\w+)\\b" ALSO matched
        // English hyphenations like "e-mail" / "check-in" (any two word
        // fragments around a hyphen), awarding +2 Malay points to English
        // text. Require both halves to be 2+ letters, alphabetic, and
        // (weak signal) share a stem — a real reduplication.
        if (Regex("(\\b[a-z]{2,})-([a-z]{2,})\\b").containsMatchIn(lower)) {
            malayScore += 2
        }

        Log.d(TAG, "detectLocaleFromText: Malay score=$malayScore (words=${words.size})")

        // Threshold: score >= 3 means confident Malay identification
        // This prevents false positives from occasional English words
        // that happen to match (e.g., "saya" could theoretically appear
        // in English speech-to-text as noise).
        // Language FIX: threshold lowered 3 → 2. A function word (2 pts) +
        // one suffix/pattern hit now qualifies, e.g. "baca ini untuk saya"
        // (ini+saya=4), "tolong baca label" (tolong=1). Under the old bar,
        // most short real queries fell through to the device-locale fallback
        // (English), which is exactly the reported mirroring failure.
        if (malayScore >= 2) {
            Log.d(TAG, "detectLocaleFromText: Malay detected (score=$malayScore) → ms")
            return java.util.Locale("ms", "MY")
        }

        // ── Step 3: Implausible-transcript rescue (garble, no English skeleton) ─
        // First-contact hole: the locale-protection tier downstream
        // (lastSpokenMsZhLocale) can only rescue a garbled transcript AFTER a
        // ms/zh query has already succeeded this session. On the VERY FIRST
        // query — the reported "broke again" case — there is no spoken ms/zh
        // history, so old Step 3 fell through to the DEVICE default (en-US on
        // English-default phones) and the answer came back English.
        // The suspect-ladder's implausibility gate already encodes exactly the
        // evidence we need (no ms/zh text signal AND no English function-word
        // skeleton ≤ 6 words = force-translated Malay phonemes), so reuse it
        // here: hand the ms locale onward instead of the device default. The
        // prompt directives make the model mirror the QUERY, so a Malay-intent
        // locale with garbled query text still yields a Malay answer (verified
        // by ir_7: ASR garble "APA Ini" → correct Malay output once the locale
        // said ms). English with an intact English skeleton is untouched.
        //
        // GATED to the main delivery path (rescueImplausibleAsMsZh=true):
        // other call sites must keep the old fallback. isAmbientChat relies on
        // this detector saying "not ms/zh" to drop low-confidence ENGLISH
        // chatter — a garble rescue there would wave real chatter through as
        // Malay. The Gemma model-ASR rescue/replay paths keep it too: Gemma's
        // transcription is itself the best language evidence, and changing
        // only the directive language on a garbled Gemma transcript helps
        // nothing.
        if (rescueImplausibleAsMsZh && isImplausibleEnglishTranscript(text)) {
            Log.d(TAG, "detectLocaleFromText: no ms/zh signal but implausible English " +
                "garble → last spoken ms/zh (${lastSpokenMsZhLocale()}) or ms-MY")
            return lastSpokenMsZhLocale() ?: java.util.Locale("ms", "MY")
        }

        // ── Step 4: Fallback — device default locale ────────────
        val deviceLocale = java.util.Locale.getDefault()
        Log.d(TAG, "detectLocaleFromText: no strong signal → device default $deviceLocale")
        return deviceLocale
    }

    /**
     * Wraps ALL calls inside a Main Handler block to satisfy Android's
     * strict requirement that SpeechRecognizer operations run on the Main UI thread.
     * Calls cancel() first to clear any stale session before starting a fresh one.
     */
    fun startListeningSafely() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    Log.w(TAG, "startListeningSafely: SpeechRecognizer is null")
                    return@post
                }

                // ── L3: NOISE PAUSE ──────────────────────────────
                // After repeated ambient-chatter rejections, stay quiet
                // until the user taps. Only an explicit tap re-opens the mic.
                if (noisePaused) {
                    Log.d(TAG, "startListeningSafely: noise pause active — staying quiet until tap")
                    return@post
                }

                // ── ANSWER PLAYBACK GUARD (voice session fix) ───────
                // A mic cycle scheduled by a stale error handler
                // (ERROR_RECOGNIZER_BUSY / ERROR_CLIENT retry, adaptive
                // backoff) can fire while the user's answer is still being
                // spoken. Starting recognition here BARGED IN and cut the
                // answer mid-sentence. Let the speech finish; the fragment
                // reopens the mic itself when the answer completes
                // (maybeOpenFollowUpWindow).
                // hasPendingSpeech() (deterministic utterance-ID tracking)
                // rather than isSpeaking(): the raw engine flag lingers true
                // during AudioTrack hardware drain AFTER onDone, which would
                // wrongly defer the fragment's legit follow-up reopen.
                if (ttsReady && ttsManager.hasPendingSpeech()) {
                    Log.d(TAG, "startListeningSafely: answer still queued/speaking — deferring mic start until it finishes")
                    return@post
                }

                // ALWAYS cancel first — clears stale audio buffer from previous
                // recognition session. Without this, the recognizer may carry
                // partial audio from the last session into the new one, causing
                // the second query to include stale speech data.
                speechRecognizer?.cancel()
                isListening = false

                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "startListeningSafely: Requesting RECORD_AUDIO permission")
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    return@post
                }

                // New user-initiated session — the language ladder starts
                // from the top. (Ladder RETRIES bypass this method and call
                // startListeningAfterTtsStop() directly, so they keep their
                // ladder position.)
                localeFallbackIndex = 0
                startListeningAfterTtsStop()
            } catch (e: Exception) {
                Log.e(TAG, "startListeningSafely error: ${e.message}")
                isListening = false
            }
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            Log.w(TAG, "startListening called but SpeechRecognizer is null")
            return
        }

        if (isListening) {
            Log.d(TAG, "Already listening — ignoring duplicate startListening()")
            return
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        startListeningInternal()
    }

    fun stopListening() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
                Log.d(TAG, "Stopped listening")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stopListening failed: ${e.message}")
            isListening = false
        }
    }

    /**
     * Open the TTS engine's "install voice data" screen so the user can
     * download a better (neural) voice pack. Returns false if no installer
     * is available on the device.
     */
    fun openTtsVoiceInstaller(): Boolean {
        return try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.i(TAG, "Opened TTS voice installer")
                true
            } else {
                Log.w(TAG, "No TTS voice installer available")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open TTS voice installer: ${e.message}")
            false
        }
    }

    fun isCurrentlyListening(): Boolean = isListening

    fun isTtsReady(): Boolean = ttsReady

    fun isTtsSpeaking(): Boolean = ttsReady && ttsManager.isSpeaking()

    /**
     * Which language the recognizer should be PINNED to, when known:
     * only a Malay or Chinese voice DECLARED in Vyze's voice settings —
     * the user explicitly chose that language, so recognition must match
     * it even when the phone itself is English (the device default
     * otherwise hears Malay as garbage / NO_MATCH).
     *
     * Deliberately NOT adaptive: pinning to the last DETECTED ms/zh
     * locale made back-and-forth code-switching fail (one Malay query
     * pinned every later session to ms-MY). Unpinned sessions with the
     * auto-detect extras follow the actual spoken language per query.
     * English (declared or detected) returns null → the recognizer uses
     * its own device default, which is English on English phones.
     */
    private fun resolveRecognitionLocale(): java.util.Locale? {
        // ONLY the user's DECLARED Vyze voice language pins recognition.
        // The previous adaptive pin (last detected ms/zh locale) broke
        // back-and-forth code-switching: after ONE Malay query every later
        // session was pinned ms-MY, so the next English query went through
        // the Malay acoustic model and came back garbled. Unpinned sessions
        // + auto-detect extras let the recognizer follow the ACTUAL spoken
        // language per query; per-query mirroring is driven by the result
        // locale + text detection, not by a lagging pin.
        val stored = TTSManager.storedLanguageLocale(this)
        return stored.takeIf { it.language == "ms" || it.language == "zh" }
    }

    /**
     * True when the device has no usable connectivity. Gates the cloud-only
     * recognizer extras (EXTRA_ENABLE_LANGUAGE_DETECTION / _SWITCH): those
     * are server-side features that fail the whole session offline (network
     * error → silence), so offline sessions run the on-device acoustic model
     * instead — with the app's own detectLocaleFromText as the language
     * authority. Requires VALIDATED, not just a connected interface: an
     * unvalidated network fails Google's cloud recognizer all the same.
     *
     * Conservative by design: any ConnectivityManager absence or anomaly
     * counts as offline — the on-device path is the safe fallback.
     */
    private fun isDeviceOffline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = try {
            cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        } catch (_: SecurityException) {
            return true
        }
        return !(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    // ── Transcript plausibility helpers (Phase 3) ─────────────────

    /**
     * Strong textual evidence of Malay that the main detector missed: any
     * STRONG_MALAY_RESCUE_WORDS token surviving word-boundary cleanup.
     * Returns true even for a single word ("apa" alone).
     */
    private fun hasStrongMalaySignal(text: String): Boolean {
        val words = text.lowercase().split(Regex("\\s+"))
            .map { w -> w.replace(Regex("[^a-z]"), "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.any { it in STRONG_MALAY_RESCUE_WORDS }
    }

    /**
     * PLAUSIBILITY GATE (Phase 3 — garble detection).
     *
     * True when the transcript looks like wrong-language force-translation:
     * the text detector found no ms/zh evidence, and the "English" transcript
     * itself is implausible — a short run of word-like chunks with no English
     * function-word skeleton ("any uppa", "inni apa"). Every real English
     * question carries at least one function word (what/where/this/the/is...);
     * phonetic garble usually does not. Applies ONLY to Latin transcripts —
     * CJK output can only come from a genuinely Chinese acoustic model.
     */
    private fun isImplausibleEnglishTranscript(text: String): Boolean {
        if (hasCjkCharacters(text)) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val words = trimmed.lowercase().split(Regex("\\s+"))
            .map { w -> w.replace(Regex("[^a-z]"), "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // Garble is rarely > 6 words: force-translation of a short Malay
        // query produces 1-4 word-like chunks.
        if (words.size > 6) return false
        return words.none { it in ENGLISH_FUNCTION_WORDS }
    }

    /**
     * True when the recognizer claimed ms/zh in its results bundle (many
     * devices omit the bundle entirely — a missing bundle is NOT English
     * evidence).
     */
    private fun bundleClaimsMsZh(detectedLang: String?): Boolean {
        if (detectedLang.isNullOrBlank()) return false
        val language = try {
            java.util.Locale.forLanguageTag(detectedLang).language
        } catch (e: Throwable) {
            return false
        }
        return language == "ms" || language == "zh"
    }

    /**
     * The ms/zh locale the user most recently SPOKE successfully (normal
     * results, model-ASR rescue, or a suspect retry). Used to order the
     * NO_MATCH ladder and to protect mirrored TTS voices when a garbled
     * transcript would otherwise re-pin the session to en_US.
     */
    private fun lastSpokenMsZhLocale(): java.util.Locale? =
        lastDetectedLocale?.takeIf { it.language == "ms" || it.language == "zh" }

    // ── Internal Listening ────────────────────────────────────────

    /**
     * Start listening. Barge-in: stops TTS before opening mic.
     */
    private fun startListeningInternal() {
        try {
            // Fresh voice session — allow one model-ASR rescue attempt and
            // one suspect-transcript ladder retry
            asrFallbackTried = false
            suspectLadderRetries = 0
            pendingSuspectTranscript = null
            // Stale audio from a previous session is useless for the
            // suspect-transcript replay — drop it here, BEFORE the
            // recognizer writes a fresh capture.
            AudioCapture.clearLastCapture()

            // Barge-in: stop TTS before opening the microphone
            if (ttsReady && ttsManager.isSpeaking()) {
                Log.d(TAG, "Barge-in: stopping TTS before speech recognition")
                ttsManager.stop()
                mainHandler.postDelayed({ startListeningAfterTtsStop() }, 200L)
                return
            }

            startListeningAfterTtsStop()

        } catch (e: Throwable) {
            Log.e(TAG, "startListeningInternal failed: ${e.javaClass.simpleName}: ${e.message}")
            isListening = false
            onSpeechError?.invoke("Failed to start voice input: ${e.message}")
        }
    }

    private fun startListeningAfterTtsStop() {
        try {
            // ── LOCALE FALLBACK LADDER ───────────────────────────
            // If a previous session failed and no ms/zh voice is declared,
            // transparently retry in the ladder's next language instead of
            // surfacing "No speech detected".
            // ── SESSION LANGUAGE SELECTION ───────────────────
            // 1. LADDER ACTIVE (a previous session NO_MATCHed while the user
            //    demonstrably spoke): pin the ladder's next language — the
            //    user's speech is real, the engine just heard the wrong
            //    language. Works even when a ms/zh locale is pinned by
            //    [resolveRecognitionLocale]: the previous session's language
            //    already failed, retrying it would reproduce the NO_MATCH.
            // 2. Pinned ms/zh (DECLARED voice only — never adaptive): honor
            //    it — but remember the pin so the ladder can skip it.
            // 3. Otherwise: UNPINNED with auto-detect extras (API 34+) — the
            //    engine follows the actual spoken language per query, which
            //    is what makes back-and-forth code-switching work.
            val pinned = resolveRecognitionLocale()
            val ladderLocale = if (localeFallbackIndex > 0 &&
                localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size
            ) {
                FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
            } else null
            val recognitionLocale = ladderLocale ?: pinned
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (recognitionLocale != null) {
                    val tag = recognitionLocale.toLanguageTag()
                    // Strong hint: listen for the user's actual language
                    // (en-US / ms-MY / zh-CN).
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    lastPinnedLocaleTag = tag
                    Log.d(TAG, "Recognition language forced to $tag" +
                        if (ladderLocale != null) " (fallback ladder step ${localeFallbackIndex + 1})" else "")
                } else {
                    // ── UNPINNED SESSION (mirroring-critical) ────────────
                    // Do NOT set EXTRA_LANGUAGE here. A hard en-US pin makes
                    // the engine transcribe Malay/Chinese speech through its
                    // ENGLISH acoustic model and report "en-US" in the
                    // results — setUserLocale(en-US) then reverts the mirrored
                    // TTS voice and the prompt language directives. The
                    // unpinned session (device default + auto-detect extras)
                    // is the configuration under which the recognizer reports
                    // the TRUE detected language, which is what mirroring
                    // depends on. First-contact coverage is provided by the
                    // NO_MATCH ladder (en-US → ms-MY → zh-CN) instead of a
                    // pin.
                    lastPinnedLocaleTag = null // unpinned — nothing to skip
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        SUPPORTED_RECOGNITION_LANGUAGES
                    )
                    // ── OFFLINE GATE ────────────────────────────────────
                    // The language auto-detect/switch extras are a SERVER-side
                    // feature of Google's recognizer: offline they fail the
                    // whole session (network/server error → silence). When
                    // the device has no connectivity, strip them and prefer
                    // the on-device acoustic model; the app's own
                    // detectLocaleFromText (which runs on every result anyway)
                    // is the language authority offline. This restores the
                    // pre-Jev-era offline behavior: English recognition via
                    // the device's downloaded offline pack, with the Gemma
                    // model-ASR rescue covering what the pack cannot.
                    val offline = isDeviceOffline()
                    if (offline) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        Log.i(TAG, "OFFLINE MODE: recognizer extras stripped to on-device path")
                        CrashLogFile.log(TAG, "OFFLINE RECOGNIZER: prefer-offline, auto-detect extras skipped")
                    }
                    // API 34+: ask the engine to auto-detect the spoken
                    // language from the supported set (en-US, ms-MY, zh-CN)
                    // and switch mid-session — this is what makes first-contact
                    // Malay/Chinese queries work on English-default phones.
                    if (android.os.Build.VERSION.SDK_INT >= 34 && !offline) {
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                            SUPPORTED_RECOGNITION_LANGUAGES
                        )
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                    }
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                // VAD FIX (Phase 3): the previous 300/400ms thresholds are
                // tuned for fast English — final Malay consonants ("apa" →
                // "ap") and short Chinese syllables were clipped, then the
                // session ended before the query completed. 600/800ms keeps
                // the session open long enough for short non-English queries
                // while the follow-up window watchdog still bounds latency.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    800L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    600L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    1000L
                )
            }

            isListening = true
            // Fresh session — clear the partial stream so the L2 stability
            // check never compares against a previous session's text, and
            // reset the speech-attempt flag (no speech heard yet).
            lastPartialText = ""
            speechAttempted = false
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "Speech recognition started — waiting for voice input")
            CrashLogFile.log(TAG, "Speech recognition started")

        } catch (e: Throwable) {
            Log.e(TAG, "startListeningAfterTtsStop failed: ${e.javaClass.simpleName}: ${e.message}")
            isListening = false
            onSpeechError?.invoke("Failed to start voice input: ${e.message}")
        }
    }

    /**
     * Create the RecognitionListener. Timeouts and errors reset to IDLE
     * without spoken error loops or automatic listening restart.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                // The user started talking — if recognition then fails, that is
                // a genuine "lost in noise" case (eligible for the model-ASR
                // rescue), not a silence pause.
                speechAttempted = true
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech — waiting for final results")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                // ── STALE-SESSION GATE ──────────────────────────────
                // The user may have aborted this session (a tap/double-tap
                // cancels the mic before the recognizer reports its result).
                // In that case the error belongs to the dead session — do NOT
                // restart the mic, do NOT run the model-ASR rescue, and do NOT
                // surface the error to the fragment (it would speak "I did not
                // catch that" right before the real answer arrives).
                if (!voiceSessionWanted) {
                    Log.d(TAG, "onError($error) after session aborted — dropping stale callback")
                    localeFallbackIndex = 0
                    return
                }
                // ── SUSPECT-RETRY FAILURE: deliver the original ──────
                // The retry session captured NEW audio; if it produced
                // nothing (silence — the user did not repeat — or another
                // failure), the ORIGINAL transcript is still a real query
                // that passed the chatter filter. Deliver it with its
                // original detected locale instead of an error or the
                // model-ASR rescue (which would nag "say that again" for a
                // query we already hold). The ladder index resets so the
                // NO_MATCH ladder does not chain more silent retries on top.
                val originalTranscript = pendingSuspectTranscript
                if (originalTranscript != null) {
                    // Attempt 2 available? Replay the captured PCM offline
                    // before giving up — no user re-listen needed.
                    if (suspectLadderRetries < SUSPECT_LADDER_MAX_RETRIES &&
                        attemptSuspectAudioReplay()
                    ) {
                        return
                    }
                    pendingSuspectTranscript = null
                    localeFallbackIndex = 0
                    Log.i(TAG, "Suspect retry failed (error=$error) — accepting original transcript")
                    CrashLogFile.log(TAG, "SUSPECT RETRY FAILED: accepting original transcript")
                    onSpeechResult?.invoke(originalTranscript, lastDetectedLocale, 0f)
                    return
                }
                // ── LOCALE FALLBACK LADDER RETRY ────────────────────
                // NO_MATCH on an English-pinned session usually means the
                // user spoke Malay/Chinese into an English acoustic model —
                // the "no response" bug. If the user DID speak, silently
                // retry the session in the ladder's next language (ms-MY →
                // zh-CN) before giving up. SPEECH_TIMEOUT is excluded: that
                // is silence, not a language mismatch. Exhausted ladder or a
                // declared/detected ms/zh voice falls through to normal
                // handling (including the model-ASR rescue).
                if (error == SpeechRecognizer.ERROR_NO_MATCH && speechAttempted &&
                    // No "accepted English" guard here: blocking retries after
                    // an English success also blocks the Malay/Chinese rescue
                    // in exactly the code-switch window where it is needed.
                    // The ladder is bounded (3 steps, resets on acceptance,
                    // abort, and every new session) so it cannot loop.
                    localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size
                ) {
                    // [localeFallbackIndex] points at the NEXT locale to
                    // try. Two skips, in order:
                    // (a) Unpinned session (no API 34+ auto-detect): the
                    //     device-default language just failed — start the
                    //     ladder at the LAST SUCCESSFULLY SPOKEN language
                    //     when known. Bounded retry-path heuristic, NOT a
                    //     pin: it only orders the retries after a failure,
                    //     every new session starts fresh and unpinned.
                    // (b) Skip the locale the failed session was pinned to —
                    //     re-running it would reproduce the same NO_MATCH.
                    if (lastPinnedLocaleTag == null && localeFallbackIndex == 0) {
                        val idx = FALLBACK_RECOGNITION_LOCALES.indexOfFirst {
                            it.language == lastDetectedLocale?.language
                        }
                        if (idx > 0) localeFallbackIndex = idx
                    }
                    if (FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
                        .toLanguageTag() == lastPinnedLocaleTag
                    ) {
                        localeFallbackIndex++
                    }
                    if (localeFallbackIndex >= FALLBACK_RECOGNITION_LOCALES.size) {
                        Log.i(TAG, "Ladder exhausted — falling through to normal error handling")
                    } else {
                        val nextLocale = FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
                        Log.i(TAG, "Ladder retry: NO_MATCH — retrying as ${nextLocale.toLanguageTag()} (step ${localeFallbackIndex + 1})")
                        CrashLogFile.log(TAG, "LADDER RETRY: recognition → ${nextLocale.toLanguageTag()}")
                        isListening = false
                        speechRecognizer?.cancel()
                        startListeningAfterTtsStop()
                        return
                    }
                }
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.d(TAG, "onError: NO_MATCH — no speech detected")
                        "No speech detected."
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Log.d(TAG, "onError: SPEECH_TIMEOUT — silence too long")
                        "Listening timed out."
                    }
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    else -> "Unknown error ($error)"
                }

                Log.w(TAG, "Speech error: $errorMsg (code=$error)")
                CrashLogFile.log(TAG, "Speech error: $errorMsg (code=$error)")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_AUDIO -> {
                        // ── TIER 2 RESCUE: model-native ASR ──────────────
                        // The recognizer heard nothing useful (the classic
                        // "query lost in a noisy room" case). Before ending
                        // the session, let Gemma's own audio encoder listen:
                        // record the user's speech and transcribe it offline.
                        // Only one attempt per session — no beep loops.
                        //
                        // GATE: the rescue only runs when the user actually
                        // ATTEMPTED speech (onBeginningOfSpeech fired). Pure
                        // silence pauses inside the follow-up window must end
                        // quietly (the fragment reopens the mic) — running the
                        // rescue on every quiet pause spoke "Please say that
                        // again" unprompted and consumed the session.
                        if (speechAttempted && attemptModelAsrRescue(errorMsg)) {
                            return
                        }
                        // Rescue failed / unavailable / not attempted — end the
                        // session cleanly (no auto-restart, no idle listening).
                        onPartialSpeechResult?.invoke("")
                        onSpeechError?.invoke(errorMsg)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Mic briefly busy — one short retry for the on-demand
                        // session, then the error surfaces and the session ends.
                        isListening = false
                        onPartialSpeechResult?.invoke("")
                        mainHandler.postDelayed({ startListeningSafely() }, 1000L)
                    }
                    else -> {
                        isListening = false
                        onSpeechError?.invoke(errorMsg)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                // ── STALE-SESSION GATE ──────────────────────────────
                // A transcription can arrive AFTER the user already tapped
                // away (SpeechRecognizer commits asynchronously). Re-queuing
                // it as a fresh query would cancel the user's in-flight tap
                // analysis — drop it instead.
                if (!voiceSessionWanted) {
                    Log.d(TAG, "onResults after session aborted — dropping stale transcription")
                    return
                }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestMatch = matches?.firstOrNull()

                if (bestMatch.isNullOrBlank()) {
                    Log.d(TAG, "onResults: empty — no speech recognized, ending session")
                    onPartialSpeechResult?.invoke("")
                    onSpeechError?.invoke("No speech detected.")
                    return
                }

                // ── L2: AMBIENT CHATTER FILTER ──────────────────────
                // In a noisy room, the recognizer commits background
                // conversation as if the user spoke it. Reject fragmentary,
                // low-confidence, or unstable transcriptions before they
                // become VLM queries.
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (isAmbientChat(bestMatch, confidence)) {
                    Log.d(TAG, "onResults: chatter filter dropped \"$bestMatch\"")
                    onPartialSpeechResult?.invoke("")
                    registerRejectedCycle()
                    return
                }

                // Real query — reset adaptive backoff + chatter counters
                resetRetryBackoff()
                rejectedCycleCount = 0

                // Extract detected language from SpeechRecognizer
                // Many devices don't return EXTRA_LANGUAGE in results — fall back to text-based detection
                val detectedLang = results?.getString(RecognizerIntent.EXTRA_LANGUAGE)
                val detectedLocale: java.util.Locale? = if (!detectedLang.isNullOrBlank()) {
                    try {
                        java.util.Locale.forLanguageTag(detectedLang)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to parse locale '$detectedLang': ${e.message}")
                        null
                    }
                } else null

                // Fallback: detect language from the transcribed text itself
                // This covers two cases:
                // 1. Devices where EXTRA_LANGUAGE is missing from results Bundle
                // 2. SpeechRecognizer returns 'en' even for Malay/Chinese speech
                //    (common on English-default phones where the recognizer ignores
                //     the EXTRA_LANGUAGE_PREFERENCE hint)
                val textDetectedLocale = detectLocaleFromText(bestMatch, rescueImplausibleAsMsZh = true)
                val finalLocale = if (detectedLocale != null &&
                    detectedLocale.language in listOf("ms", "zh")
                ) {
                    detectedLocale // Trust recognizer if it says Malay/Chinese
                } else {
                    textDetectedLocale // Use text detection for Malay/Chinese
                }

                // ── SUSPECT-TRANSCRIPT LADDER ────────────────────
                // A SUCCESSFUL session can still run the wrong language: on
                // engines without API 34+ auto-detect, an unpinned session
                // uses the device-default acoustic model, which transcribes
                // Malay/Chinese speech into garbled English-ish tokens —
                // success-with-wrong-language that the NO_MATCH ladder never
                // sees (verified on device: setUserLocale pinned en_US for
                // every language). Detection signal: no ms/zh evidence in
                // the text AND (recognizer confidence below threshold OR an
                // implausible English transcript — see the Phase 3 rework
                // below). Intact ms/zh text is exempt — genuine ms/zh
                // transcripts score low by nature and are handled by the
                // text detector, not this path.
                val conf = confidence?.firstOrNull()
                val textSaysMsZh = finalLocale.language == "ms" || finalLocale.language == "zh"
                val lowConfidence = conf != null && conf < SUSPECT_TRANSCRIPT_CONFIDENCE

                // (R0) strong Malay signal inside a failed detection - no
                // retry needed, the transcript carries the language itself.
                if (!textSaysMsZh && hasStrongMalaySignal(bestMatch)) {
                    Log.i(TAG, "Malay rescue words in transcript \"$bestMatch\" - delivering as ms-MY (conf=$conf)")
                    CrashLogFile.log(TAG, "MS RESCUE: strong Malay tokens in transcript")
                    lastDetectedLocale = java.util.Locale("ms", "MY")
                    onSpeechResult?.invoke(bestMatch, lastDetectedLocale, conf ?: 1f)
                    return
                }

                // (R1)/(R2) trigger: no ms/zh evidence AND (low confidence OR
                // an implausible English transcript).
                val implausible = isImplausibleEnglishTranscript(bestMatch)
                if (!textSaysMsZh && !bundleClaimsMsZh(detectedLang) &&
                    (lowConfidence || implausible) &&
                    suspectLadderRetries < SUSPECT_LADDER_MAX_RETRIES
                ) {
                    // Attempt 1: re-listen pinned to the ladder's next
                    // language. Attempt 2: offline audio replay - no user
                    // re-listen, so the ladder index stops gating here.
                    val useReplay = suspectLadderRetries >= 1
                    if (!useReplay && localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size) {
                        // Order the retry at the last successfully SPOKEN
                        // language when known, then skip the language the
                        // recognizer claimed in its bundle (the failed model).
                        if (lastPinnedLocaleTag == null && localeFallbackIndex == 0) {
                            val spokenMsZh = lastSpokenMsZhLocale()
                            if (spokenMsZh != null) {
                                val idx = FALLBACK_RECOGNITION_LOCALES.indexOfFirst {
                                    it.language == spokenMsZh.language
                                }
                                if (idx > 0) localeFallbackIndex = idx
                            }
                        }
                        if (!detectedLang.isNullOrBlank() &&
                            FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
                                .toLanguageTag() == detectedLang
                        ) {
                            localeFallbackIndex++
                        }
                        if (localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size) {
                            val nextLocale = FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
                            suspectLadderRetries++
                            pendingSuspectTranscript = bestMatch
                            Log.i(TAG, "Suspect transcript (conf=$conf${if (implausible) ", implausible" else ""}) - ladder retry as ${nextLocale.toLanguageTag()} (attempt $suspectLadderRetries)")
                            CrashLogFile.log(TAG, "SUSPECT LADDER RETRY: recognition -> ${nextLocale.toLanguageTag()}")
                            isListening = false
                            speechRecognizer?.cancel()
                            startListeningAfterTtsStop()
                            return
                        }
                    } else if (useReplay) {
                        // (R2) offline audio replay via Gemma's encoder.
                        suspectLadderRetries++
                        pendingSuspectTranscript = bestMatch
                        if (attemptSuspectAudioReplay()) return
                        // Replay unavailable - fall through and deliver the
                        // original transcript below.
                    }
                }

                // Delivery with LOCALE PROTECTION: a garbled transcript must
                // not re-pin the mirrored ms/zh TTS voice to en_US. If this
                // session failed detection (no ms/zh evidence, suspect signals
                // present) but a ms/zh locale was spoken earlier in the
                // conversation, hand THAT locale onward instead of en_US.
                val deliverLocale = if (!textSaysMsZh && (lowConfidence || implausible)) {
                    lastSpokenMsZhLocale() ?: finalLocale
                } else {
                    finalLocale
                }
                if (deliverLocale != finalLocale) {
                    Log.i(TAG, "Locale protection: garbled transcript keeps last spoken ms/zh locale $deliverLocale (bundle=$detectedLang)")
                    lastDetectedLocale = deliverLocale
                }

                Log.i(TAG, "onResults: \"$bestMatch\" lang=$finalLocale (bundle=$detectedLang)")
                CrashLogFile.log(TAG, "Speech result: \"$bestMatch\" lang=$finalLocale")
                lastDetectedLocale = finalLocale
                localeFallbackIndex = 0 // a session succeeded — ladder back to start
                suspectLadderRetries = 0 // a transcription was accepted — suspect budget restored
                onSpeechResult?.invoke(bestMatch, finalLocale, confidence?.firstOrNull() ?: 1f)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!voiceSessionWanted) {
                    return // stale partial from an aborted session
                }
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    lastPartialText = partial
                    Log.d(TAG, "onPartialResults: \"$partial\"")
                    onPartialSpeechResult?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Comma-separated BCP-47 languages hinted to the recognizer before any
         * user language is known — lets a Malay/Chinese speaker on an
         * English-default phone be heard on the very first session.
         */
        private const val SUPPORTED_RECOGNITION_LANGUAGES = "en-US,ms-MY,zh-CN"

        /**
         * Max suspect-transcript ladder retries per voice session — capped
         * at 2: attempt 1 re-listens pinned to the ladder's next language,
         * attempt 2 replays the captured PCM through Gemma's offline audio
         * encoder (language-agnostic, no user re-listen). Worst case is one
         * silent re-listen plus one offline transcription before the
         * original transcript is delivered as-is.
         */
        private const val SUSPECT_LADDER_MAX_RETRIES = 2

        /**
         * PLAUSIBILITY GATE (Phase 3): recognizer confidence below this —
         * when the text detector finds no ms/zh evidence — is treated as
         * probable wrong-language garble even though the transcript "looks
         * like English". The en-US acoustic model force-translates Malay
         * phonemes into PLAUSIBLE English words at HIGH confidence, which is
         * exactly why the previous 0.5 threshold never fired on device:
         * "ini apa?" became fluent-ish English scoring 0.6-0.9. The band
         * 0.5-0.65 is where force-translated garble lands most often;
         * genuine quiet-room English speech scores above it.
         */
        private const val PLAUSIBLE_TRANSCRIPT_CONFIDENCE = 0.65f

        /**
         * Malay function words strong enough to rescue a garbled-looking
         * transcript WITHOUT a re-listen. If the transcript itself contains
         * any of these (the recognizer can transliterate "apa" → "aba",
         * "ini" → "any", etc.), the query is real Malay that the text
         * detector's 2-point threshold missed — deliver it with the ms-MY
         * locale instead of running the ladder.
         */
        private val STRONG_MALAY_RESCUE_WORDS = setOf(
            "apa", "ini", "itu", "saya", "ada", "di", "depan",
            "belakang", "baca", "tolong", "mana", "siapa", "berapa",
            "apa ini", "ini apa"
        )

        /**
         * Function words ANY plausible English sentence uses (the, is, what,
         * where, this, ...). The garble plausibility test requires the
         * transcript to contain at least one of these — real questions do;
         * force-translated Malay phonemes ("any uppa", "inni apa") rarely do.
         *
         * NOTE (mirroring fix review): Vyze domain words ("read", "look",
         * "front", ...) deliberately STAY in this set. Removing them would
         * reclassify terse real-English commands ("read label") as garble;
         * garble that lands ON a domain word is instead caught by the
         * suspect ladder's low-confidence trigger (SUSPECT_TRANSCRIPT_CONFIDENCE
         * band — where English-model transcriptions of ms/zh speech land).
         */
        private val ENGLISH_FUNCTION_WORDS = setOf(
            "the", "is", "are", "what", "where", "when", "who", "how",
            "why", "this", "that", "there", "here", "can", "you", "i",
            "me", "my", "in", "on", "at", "of", "for", "and", "a", "an",
            "do", "does", "please", "read", "look", "see", "tell", "show",
            "front", "behind", "left", "right", "now", "time"
        )

        /**
         * Recognizer confidence below which a transcript with no ms/zh
         * text-detector signal is treated as suspected wrong-language garble.
         * Sits above the chatter filter floor (0.35): only the
         * mediocre-confidence band reaches this check, which is where
         * English-model transcriptions of Malay/Chinese speech typically
         * land. Tune on device logcat if the band proves too wide/narrow.
         */
        private const val SUSPECT_TRANSCRIPT_CONFIDENCE = 0.5f

        /**
         * Locale-fallback ladder for recognition failures. A session that
         * returns NO_MATCH while the user demonstrably SPOKE is retried in
         * the ladder's next language (en-US → ms-MY → zh-CN) before the
         * error surfaces. English first so English queries work on first
         * contact; ms/zh follow for first-contact non-English speech on
         * engines that ignore the auto-detection extras. The locale already
         * used by the failing session is skipped. Reset when any
         * transcription is accepted, on a user-aborted session, and on each
         * new user-initiated session.
         */
        private val FALLBACK_RECOGNITION_LOCALES = listOf(
            java.util.Locale.US,
            java.util.Locale("ms", "MY"),
            java.util.Locale("zh", "CN")
        )

        // ── Tier 1: Noise Robustness ──────────────────────────────
        /** L1: restart delays after failed recognition cycles (ms). */
        private val RETRY_RAMP_MS = longArrayOf(300L, 1000L, 3000L, 8000L)

        /** L3: consecutive rejected chatter cycles before pausing listening. */
        private const val NOISE_DETECTION_THRESHOLD = 5

        /** L2: reject transcriptions below this confidence (0.0–1.0). */
        private const val MIN_CONFIDENCE = 0.35f

        /** L2: reject one-word results shorter than this ("yeah", "okay"). */
        private const val MIN_SINGLE_WORD_CHARS = 5
    }
}

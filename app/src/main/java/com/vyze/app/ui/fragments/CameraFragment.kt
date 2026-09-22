package com.vyze.app.ui.fragments
import com.vyze.app.VyzeApplication
import com.vyze.app.agent.RouterSignal
import com.vyze.app.agent.RouterSnapshot
import com.vyze.app.agent.VyzeAgentRuntime
import com.vyze.app.agent.VyzeShadowRouter
import com.vyze.app.agent.student.PreGatePolicy
import com.vyze.app.core.ThermalPolicy
import com.vyze.app.core.ThermalPowerController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vyze.app.R
import com.vyze.app.util.CrashLogFile
import com.vyze.app.ui.MainViewModel
import com.vyze.app.device.FlashlightManager
import com.vyze.app.core.VyzeCoreController
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.ui.TtsViewModel
import com.vyze.app.ui.SplashViewModel
import com.vyze.app.speech.ReportManager
import com.vyze.app.vision.ColorAnalyzer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.Navigation
import com.vyze.app.*
import com.vyze.app.data.MemoryDao
import com.vyze.app.data.ScanRepository
import com.vyze.app.ui.delegates.CameraSetupDelegate
import com.vyze.app.ui.delegates.GestureRouter
import com.vyze.app.databinding.FragmentCameraBinding

/**
 * Main camera fragment for Vyze accessibility app — VLM Snapshot Mode.
 *
 * ## Accessibility Flow
 * - Onboarding fires after VLM model is ready (deferred from TTS init)
 * - Barge-in: touch events instantly silence any active TTS
 * - Single-output: speak VLM result once, return to IDLE (no infinite loop)
 * - Mic is CLOSED at IDLE — voice sessions open on demand (double-tap)
 *
 * ## Gesture Map
 * Single tap → look (scene description) | Double tap → ask (voice session) |
 * Long press → light check | Triple tap → color | Triple tap + hold → SOS
 *
 * ## State Machine
 * IDLE → (tap) → ANALYZING → SPEAKING → (follow-up window) → IDLE
 * IDLE → (double tap) → LISTENING → (speech) → ANALYZING → SPEAKING → IDLE
 *
 * Follow-up window: after every answer the mic reopens hands-free for
 * CONVERSATION_WINDOW_MS — keep talking, no gesture needed until silence.
 */
class CameraFragment : Fragment() {

    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val ttsViewModel: TtsViewModel by activityViewModels()
    private val splashViewModel: SplashViewModel by viewModels()

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var gestureRouter: GestureRouter

    /** One-time guard for the TalkBack overlay/delegate install. */
    private var talkbackDelegateInstalled = false

    /** Live touch-exploration listener; registered on install, removed in onDestroyView. */
    private var talkbackStateListener: AccessibilityManager.TouchExplorationStateChangeListener? = null

    /**
     * Fragment-owned repository for TalkBack color-scan persistence. A second
     * lightweight instance over the same Room DB (GestureRouter holds its own).
     */
    private val talkbackScanRepository by lazy {
        ScanRepository(requireContext().applicationContext)
    }

    private lateinit var coreController: VyzeCoreController
    private lateinit var memoryDao: MemoryDao

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private var systemVibrator: Vibrator? = null
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var reportManager: ReportManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class AppState { LOADING, IDLE, LISTENING, ANALYZING, SPEAKING, REPORTING }

    @Volatile
    private var appState = AppState.LOADING

    @Volatile
    private var isCameraActive = false

    /**
     * PHASE 6 MAINTENANCE TICKER — the production caller for
     * [VyzeAgentRuntime.maintenanceTick] (episode idle-eviction + orphan ADK
     * session sweep). Runs every [MAINTENANCE_TICK_MS] while the fragment is
     * STARTED; thermal status is supplied READ-ONLY from
     * ThermalPowerController (the sole thermal authority — never modified,
     * never bypassed; we only ask). Purely in-memory bookkeeping: no VLM,
     * TTS, camera, or hardware interaction, so the tick can never contend
     * with an active capture or generation.
     */
    private var maintenanceJob: Job? = null

    private fun startMaintenanceTicker() {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(MAINTENANCE_TICK_MS)
                try {
                    val thermallyConstrained =
                        thermalController().policy.tier >= ThermalPolicy.TIER_MODERATE
                    VyzeAgentRuntime.maintenanceTick { thermallyConstrained }
                    // Flip-review visibility: log the eval window + promote
                    // predicate so the shadow→live decision is observable
                    // on-device via `adb logcat -s CameraFragment`.
                    if (VyzeAgentRuntime.shadowEnabled) {
                        val s = VyzeAgentRuntime.evalSummary()
                        Log.d(
                            TAG,
                            "ADK eval: ${s.total} attempts, ${s.answered} answered " +
                                "(rate=${"%.2f".format(s.successRate)}), flip-candidate=" +
                                VyzeAgentRuntime.shouldPromoteToLive(),
                        )
                    }
                } catch (t: Throwable) {
                    // Never fatal: maintenance is hygiene, not pipeline.
                    CrashLogFile.log(TAG, "maintenance tick failed: ${t.message}")
                }
            }
        }
    }

    private fun stopMaintenanceTicker() {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }

    /**
     * PHASE 3 SHADOW ROUTER (approved migration): logs the agent-path routing
     * decision for every barge-in query. SHADOW-ONLY — nothing is executed,
     * the legacy dispatch below runs unchanged, and the flag gates the whole
     * block. Hardware invariants untouched: isCapturing and
     * ThermalPowerController are neither read nor modified here.
     */
    private fun logShadowRoute(query: String) {
        if (!VyzeAgentRuntime.shadowEnabled) return
        try {
            VyzeShadowRouter.logDecision(
                signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = query),
                decision = VyzeShadowRouter.decide(
                    signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = query),
                    snapshot = RouterSnapshot(
                        engineReady = coreController.isEngineReady(),
                        isInferring = coreController.isCurrentlyInferring(),
                        captureAvailable = isCapturing.get(),
                    ),
                ),
                snapshot = RouterSnapshot(
                    engineReady = coreController.isEngineReady(),
                    isInferring = coreController.isCurrentlyInferring(),
                    captureAvailable = isCapturing.get(),
                ),
            )
        } catch (t: Throwable) {
            CrashLogFile.log(TAG, "shadow route log failed: ${t.message}")
        }
    }

    /**
     * VLM PRE-GATE (latency lever #1) — flag-gated by
     * [VyzeAgentRuntime.preGateEnabled], ships dark.
     *
     * Returns true ONLY when the transcript is in a high-confidence IGNORE
     * family the student classifies (app-cue echo / greeting garble /
     * filler-only — see [PreGatePolicy]) AND the flag is on; the caller's
     * ladder is then bypassed entirely: no capture, no Gemma inference,
     * ~0ms instead of ~2s of GPU work for a question nobody asked.
     *
     * Response modes (PreGatePolicy.ResponseMode):
     *  - SILENT (app-cue echo): nothing is spoken — Vyze is already talking
     *    (the cue IS its own speech); interrupting itself to say "didn't
     *    catch that" would worsen the echo.
     *  - GENTLE_IGNORE (greeting garble / filler-only): speak the SAME
     *    localized "did not catch that" cue the empty-result path uses, and
     *    confirm with the standard tap haptic. No state flips: the mic
     *    session died with the speakQueued flush (same mechanism as the
     *    instant-answer lane), APP stays IDLE, and the next double tap
     *    starts fresh — identical to a rejected utterance, minus the wasted
     *    VLM inference.
     *
     * False is returned for everything else (positive intents, catch-all)
     * and whenever the flag is dark — the ladder proceeds unchanged.
     */
    private fun maybeHandlePreGate(spokenText: String): Boolean {
        if (!VyzeAgentRuntime.preGateEnabled) return false
        val mode = PreGatePolicy.evaluate(spokenText)
        if (mode == PreGatePolicy.ResponseMode.PASS_THROUGH) return false
        Log.i(TAG, "VLM pre-gate: $mode for \"$spokenText\" (VLM skipped)")
        when (mode) {
            PreGatePolicy.ResponseMode.SILENT -> {
                // App-cue echo: Vyze is already speaking this exact prompt;
                // stay silent rather than interrupt itself.
            }
            PreGatePolicy.ResponseMode.GENTLE_IGNORE -> {
                try {
                    ttsManager.speakQueued(
                        ttsManager.localized(
                            "I did not catch that. Double tap and try again.",
                            "Saya tidak dengar itu. Sentuh dua kali dan cuba lagi.",
                            "我没有听清，请双击屏幕再试一次。"
                        )
                    )
                } catch (_: Throwable) {}
                try {
                    systemVibrator?.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } catch (_: Throwable) {}
            }
            PreGatePolicy.ResponseMode.PASS_THROUGH -> {
                // Unreachable: PASS_THROUGH returned false above.
            }
        }
        return true
    }

    /**
     * Launch [block] in the fragment's lifecycle scope when started.
     * The block's result is consumed inside the coroutine.
     */
    private fun launchWhenStarted(block: suspend () -> Unit) {
        val lifecycle = viewLifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        lifecycleScope.launch { block() }
    }

    /**
     * PHASE 4 ROUTE ORIGIN (text-only branch): try the agent path for a
     * classified text-only query; on ANY decline (flag dark, engine busy/
     * not ready, thermal refusal, engine error) fall back to the exact
     * legacy text path ([VyzeCoreController.triggerTextQuery]). When the
     * flag is dark this is a straight synchronous call into the legacy
     * path — zero behavior change, zero coroutine hop.
     *
     * Caller has ALREADY set ANALYZING + "Answering..." before invoking
     * this (identical to the legacy branch), so the state machine is
     * consistent whether the ADK lane or the fallback answers.
     */
    private fun beginLiveRouteOrLegacyTextOnly(query: String) {
        if (!VyzeAgentRuntime.shadowEnabled) {
            coreController.triggerTextQuery(query)
            return
        }
        launchWhenStarted {
            val answered = try {
                tryLiveRouteTextOnly(query)
            } catch (t: Throwable) {
                CrashLogFile.log(TAG, "live route crashed (falling back): ${t.message}")
                false
            }
            if (!answered) {
                // Staleness guard: a newer gesture/speech may have claimed
                // the pipeline while the agent path was deciding — a stale
                // fallback must not barge in over it (same philosophy as
                // the fragment's existing noise/stale-error gates).
                if (appState == AppState.ANALYZING) {
                    coreController.triggerTextQuery(query)
                } else {
                    Log.d(TAG, "Live-route decline stale (state=$appState) — legacy fallback skipped")
                }
            }
        }
    }

    /**
     * PHASE 4 LIVE ROUTE: attempt to answer a text-only query end-to-end on
     * the agent path. The decision comes from the same pure router that
     * produced the shadow logs (same taxonomy, same reasons); execution is
     * declined unless the route is text-only, the engine is ready, the
     * current ThermalPowerController policy allows VLM inference (read-only
     * consultation), and no agent-path generation is in flight. On ANY
     * decline this returns false and the caller continues the legacy
     * dispatch ladder unchanged — the fallback is always safe.
     *
     * When it returns true, the answer has ALREADY been spoken and the
     * follow-up window reopened; the caller must not run the legacy
     * capture/query paths for this query.
     *
     * Frame acquisition is NEVER performed here: the ADK path is text-only
     * in Phase 4, and frame-dependent routes decline to the camera layer's
     * existing isCapturing-gated paths. ThermalPowerController is only read.
     */
    private suspend fun tryLiveRouteTextOnly(query: String): Boolean {
        if (!VyzeAgentRuntime.shadowEnabled) return false
        return try {
            val answer = VyzeAgentRuntime.tryLiveRouteTextOnly(
                textOnlyQuery = query,
                snapshotProvider = {
                    RouterSnapshot(
                        engineReady = coreController.isEngineReady(),
                        isInferring = coreController.isCurrentlyInferring(),
                        captureAvailable = isCapturing.get(),
                    )
                },
                vlmInferenceAllowed = { thermalController().policy.vlmInferenceAllowed },
                analyzeText = { prompt, sessionId ->
                    coreController.analyzeTextDirect(prompt, sessionId)
                },
            )
            if (answer == null) return false

            // Agent-path success — mirror the native answer lifecycle.
            Log.i(TAG, "Live route answered (agent path): \"$query\"")
            consecutiveConfirmationAsks = 0
            appState = AppState.SPEAKING
            updateStatus("Answering [agent]")
            ttsManager.speakQueued(answer)
            waitForTtsDrain {
                appState = AppState.IDLE
                maybeOpenFollowUpWindow()
            }
            true
        } catch (t: Throwable) {
            CrashLogFile.log(TAG, "live route failed (falling back): ${t.message}")
            false
        }
    }

    @Volatile
    private var onboardingSpoken = false

    /** True while the "better voice" install question awaits an answer. */
    @Volatile
    private var awaitingInstallVoiceAnswer = false

    /** True once the weak-voice prompt was handled this session (no repeat nags). */
    @Volatile
    private var voicePromptHandledInSession = false

    /** True right after the user chose to open the voice installer. */
    @Volatile
    private var pendingVoiceInstallConfirmation = false

    /** True right after the user asked to open Accessibility Settings. */
    @Volatile
    private var pendingAccessibilityReturn = false

    /** True while the hands-free voice-selection audition is running. */
    @Volatile
    private var voiceAuditionActive = false

    /**
     * True while the audition is asking which LANGUAGE to use (English / Malay /
     * Chinese). The voice cycle itself can only list voices of the CURRENT
     * language — so if the user is stuck on the English voice (e.g. English
     * phone default), there is no way to reach the Malay voice without first
     * switching the language. This step closes that gap hands-free.
     */
    @Volatile
    private var voiceAuditionLangStep = false

    /** Candidate voices for the audition — entry 0 is "automatic". */
    private val voiceAuditionVoices = ArrayList<android.speech.tts.Voice>()
    private var voiceAuditionIndex = 0

    // ── Speech Intelligence: low-confidence confirmation loop ─────
    // A transcription in the 0.35-0.6 grey band scores above the chatter
    // floor but is still often a mis-hear. While a "Did you say X?" is
    // outstanding, the next yes/no answer resolves it instead of running
    // the full query routing on a probable mistake.
    @Volatile
    private var pendingConfirmationText: String? = null

    @Volatile
    private var pendingConfirmationLocale: java.util.Locale? = null

    private var confirmationExpiryRunnable: Runnable? = null

    /** Confidence of the last accepted transcription (routing + confirmation). */
    @Volatile
    private var lastConfidence = 1f

    /**
     * Consecutive grey-band confirmation asks without a confirmed result.
     * Caps the loop: if the user's retry ALSO lands in the grey band, the
     * pipeline runs it anyway instead of asking forever — a probable mis-hear
     * is better than dead air for a blind user.
     */
    @Volatile
    private var consecutiveConfirmationAsks = 0

    /** Debounce: prevents duplicate triggers from gesture + click overlap or speech re-trigger. */
    private var lastTriggerTime = 0L
    private val TRIGGER_DEBOUNCE_MS = 1000L

    /**
     * Atomic capture lock — prevents re-entry during the async window
     * between takeSnapshot() start and onBitmap/onError callback.
     * Independent of appState which legitimately transitions during the flow.
     */
    private val isCapturing = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Continuous Auto-Snapshot Mode ────────────────────────────
    // When enabled, automatically captures and describes the scene
    // every AUTO_SNAPSHOT_INTERVAL_MS — similar to Gemini Live.

    @Volatile
    private var isContinuousMode = false

    /** Timestamp when continuous mode was last activated. Used for thermal throttling. */
    @Volatile
    private var continuousModeStartTime = 0L

    private val autoSnapshotRunnable = object : Runnable {
        override fun run() {
            if (!isContinuousMode || !isAdded) return

            // ── THERMAL SAFETY: throttle after CONTINUOUS_MODE_THROTTLE_AFTER_MS ──
            // After extended use, force a minimum interval between captures
            // to prevent SoC thermal throttling on mid-tier chipsets.
            val elapsed = System.currentTimeMillis() - continuousModeStartTime
            val effectiveInterval = if (elapsed > CONTINUOUS_MODE_THROTTLE_AFTER_MS) {
                Log.d(TAG, "Continuous mode: thermal throttle active (${elapsed / 1000}s elapsed)")
                THERMALTHROTTLE_INTERVAL_MS
            } else {
                AUTO_SNAPSHOT_INTERVAL_MS
            }

            // ── SAFETY GUARDS ──────────────────────────────────
            // Only trigger if ALL conditions are met:
            if (appState == AppState.IDLE
                && !coreController.isCurrentlyInferring()
                && !ttsManager.hasPendingSpeech()
                && !isCapturing.get()
                && isCameraActive
            ) {
                Log.d(TAG, "Auto-snapshot: triggering continuous capture")
                triggerContinuousSnapshot()
            } else {
                Log.d(TAG, "Auto-snapshot: skipped (state=$appState, inferring=${coreController.isCurrentlyInferring()}, pending=${ttsManager.hasPendingSpeech()})")
            }

            // Schedule next tick if still in continuous mode
            if (isContinuousMode) {
                mainHandler.postDelayed(this, effectiveInterval)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsManager = ttsViewModel.ttsManager
        hapticManager = HapticManager(requireContext().applicationContext)

        // System vibrator for instant capture acknowledgement (~5ms latency)
        systemVibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = requireContext().getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        flashlightManager = FlashlightManager()

        // ── Auto-Torch Audio Announcement ────────────────────────
        // When the environment darkens/brightens and the torch toggles,
        // announce the state change so blind users know what happened.
        flashlightManager.onTorchStateChanged = { isNowOn ->
            mainHandler.post {
                try {
                    if (isNowOn) {
                        ttsManager.speakQueued(
                            ttsManager.localized(
                                "It is dark. Flashlight is on.",
                                "Gelap. Lampu suluh dihidupkan.",
                                "光线很暗，已打开手电筒。"
                            )
                        )
                    } else {
                        ttsManager.speakQueued(
                            ttsManager.localized(
                                "Light is sufficient. Flashlight is off.",
                                "Cahaya mencukupi. Lampu suluh dimatikan.",
                                "光线充足，手电筒已关闭。"
                            )
                        )
                    }
                } catch (_: Throwable) {}
            }
        }

        reportManager = ReportManager(requireContext().applicationContext)

        val app = requireActivity().applicationContext as VyzeApplication
        memoryDao = app.memoryDao

        coreController = app.coreController ?: VyzeCoreController(
            context = requireContext().applicationContext,
            ttsManager = ttsManager,
            memoryDao = memoryDao,
            interactionDao = app.interactionDao
        )

        // Wire VLM completion → speak result once, go to IDLE
        coreController.onInferenceComplete = { response ->
            activity?.runOnUiThread {
                try {
                    if (isAdded && _fragmentCameraBinding != null) {
                        Log.d(TAG, "VLM response: ${response.take(100)}...")
                        appState = AppState.SPEAKING
                        updateStatus("Ready")

                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && response.isNotBlank()) {
                            if (coreController.isDuplicateDescription(response)) {
                                Log.d(TAG, "Duplicate description — skipping TTS, returning to IDLE")
                                appState = AppState.IDLE
                                maybeOpenFollowUpWindow()
                            } else if (coreController.isStreamingActive()) {
                                Log.d(TAG, "Streaming active — waiting for TTS queue to drain")
                                waitForTtsDrain {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "TTS drain complete — post-answer state")
                                    maybeOpenFollowUpWindow()
                                }
                            } else {
                                mainActivity.speakThenCallback(response) {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "Response spoken — post-answer state")
                                    maybeOpenFollowUpWindow()
                                }
                            }
                        } else {
                            appState = AppState.IDLE
                            endFollowUpWindow()
                        }

                        // SAFETY TIMEOUT: If TTS doesn't finish within 10 seconds,
                        // force-reset to IDLE. Prevents indefinite ANALYZING/SPEAKING
                        // state when speakThenCallback's onDone doesn't fire.
                        mainHandler.postDelayed({
                            if (appState == AppState.SPEAKING) {
                                Log.w(TAG, "SPEAKING timeout — forcing IDLE")
                                appState = AppState.IDLE
                                updateStatus("Ready")
                            }
                        }, SPEAKING_TIMEOUT_MS)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onInferenceComplete UI error: ${e.message}")
                    appState = AppState.IDLE
                }
            }
        }

        coreController.onProgressUpdate = { percent, step ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    updateStatus(step)
                }
            }
        }

        coreController.onError = { error ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    // ── SILENT DROP: if state has moved past ANALYZING/SPEAKING,
                    //    this error is from a stale session — don't interrupt the
                    //    current flow with error speech or state changes.
                    if (appState != AppState.ANALYZING && appState != AppState.SPEAKING) {
                        Log.d(TAG, "onError arrived in state=$appState — stale error, dropping")
                        return@runOnUiThread
                    }
                    appState = AppState.IDLE
                    updateStatus("Error: $error")
                    Log.e(TAG, "VLM error (not spoken to user): $error")
                    // ── VOICE-QUERY RECOVERY ────────────────────────
                    // A voice query that dies (null model response, timeout,
                    // crash) must NEVER end in silence — the user is waiting
                    // for an answer with no screen to look at. Speak a short
                    // recovery cue and reopen the follow-up mic. Tap flows stay
                    // silent (the original rapid-tap double-speak concern).
                    lastConfidence = 1f
                    consecutiveConfirmationAsks = 0
                    if (keepMicOpenAfterAnswer) {
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null) {
                            mainActivity.speakThenCallback(
                                ttsManager.localized(
                                    "Sorry, I did not get an answer. Please ask again.",
                                    "Maaf, tiada jawapan diterima. Sila tanya semula.",
                                    "抱歉，没有得到答案。请再问一次。"
                                )
                            ) {
                                appState = AppState.LISTENING
                                updateStatus("Listening...")
                                mainHandler.postDelayed(
                                    { startVoiceListening() },
                                    FOLLOW_UP_OPEN_DELAY_MS
                                )
                            }
                            return@runOnUiThread
                        }
                    }
                    // Don't speak errors for tap flows — rapid tapping caused
                    // "Failed to capture" double-speak.
                }
            }
        }

        coreController.onStatusUpdate = { msg ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    updateStatus(msg)
                    if (msg.startsWith("VLM ready") && !onboardingSpoken) {
                        onboardingSpoken = true
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && mainActivity.isTtsReady()) {
                            appState = AppState.IDLE
                            // Dual-script tutorial: TTS when TalkBack is off,
                            // TalkBack-native announcement when on (TTS bypassed).
                            playOnboardingTutorial(mainActivity)
                        }
                    }
                }
            }
        }

        try {
            val backend = coreController.getEngineBackend()
            if (coreController.isEngineReady()) {
                updateStatus("Ready [$backend]")
                if (!onboardingSpoken) {
                    onboardingSpoken = true
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && mainActivity.isTtsReady()) {
                        appState = AppState.IDLE
                        // Dual-script tutorial: TTS when TalkBack is off,
                        // TalkBack-native announcement when on (TTS bypassed).
                        playOnboardingTutorial(mainActivity)
                    }
                }
            } else {
                updateStatus("Model still loading...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine status UI error: ${e.message}")
        }

        wireSpeechCallbacks()

        cameraSetup = CameraSetupDelegate()
        cameraSetup.setContext(requireContext().applicationContext)

        gestureRouter = GestureRouter(
            context = requireContext(),
            ttsManager = ttsManager,
            hapticManager = hapticManager,
            colorAnalyzer = ColorAnalyzer(),
            scanRepository = ScanRepository(requireContext().applicationContext),
            mainHandler = mainHandler
        )

        // ── Gesture Map (v2) ────────────────────────────────────────
        // Single tap  → "look": describe the scene (absorbs the old single-tap
        //               hit-test + double-tap navigation query).
        // Double tap  → "ask": open an on-demand voice session — the user
        //               speaks a question to the VLM (OCR reading included
        //               automatically via reading keywords).
        // Long press  → check ambient light + flashlight status.
        // Triple tap  → color analysis. Triple tap + hold → SOS.
        gestureRouter.onSingleTapAction = { x, y ->
            bargeInAndCapture(
                "User tapped at position (${x.toInt()}, ${y.toInt()}). " +
                "Describe what is in front of me and around me for navigation, in 1-2 " +
                "complete, natural spoken sentences with a subject and a verb — the way " +
                "you would tell a person standing next to me. For each key object include " +
                "color, size, material, and state. " +
                "If the tapped object is a packaged product (packet, box, bottle, can), " +
                "first say its BRAND name and product type exactly as printed " +
                "(for example: Maggi instant noodle packet), then its details, then continue. " +
                "If the tapped object has text on it (a label, box, or sign), " +
                "read it aloud verbatim as whole words and sentences, never spelling letter by letter. " +
                "Read the ENTIRE text on the object in reading order; do not stop halfway."
            )
        }

        gestureRouter.onDoubleTapAction = {
            openVoiceQuery()
        }

        gestureRouter.onLongPressAction = {
            performLightCheck()
        }

        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        // ── TalkBack co-existence (overlay + dual-script tutorial) ──
        // Transparent Look/Ask overlay halves + color/SOS custom actions.
        // The overlay only intercepts touches while touch exploration is ON,
        // so the physical gesture map is untouched when TalkBack is off.
        installTalkbackAccessibilityActions()

        // Demand-driven capture (Phase 2 Step A): the fragment no longer
        // owns a background executor — CameraSetupDelegate's analysis
        // executor is the single camera-work thread (luminance sampling
        // idle, full YUV decode only when a snapshot is demanded).
        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        // NOTE: no viewFinder.setOnClickListener fallback — it fired on every
        // tap-up and would defeat single-vs-double disambiguation (a double
        // tap would also have triggered a scene analysis).
    }

    override fun onResume() {
        super.onResume()

        if (::coreController.isInitialized) {
            coreController.resetSessionState()
        }

        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Log.w(TAG, "Permissions missing — requesting via PermissionsFragment")
            try {
                Navigation.findNavController(requireActivity(), R.id.fragment_container)
                    .navigate(CameraFragmentDirections.actionCameraToPermissions())
            } catch (e: Exception) {
                Log.e(TAG, "Navigation to PermissionsFragment failed: ${e.message}")
            }
        }

        if (cameraSetup.cameraProvider != null) {
            cameraSetup.rebindCamera(
                requireContext(), this,
                fragmentCameraBinding.viewFinder
            )
        }

        isCameraActive = true

        if (coreController.isEngineReady() && onboardingSpoken) {
            // Mic stays CLOSED at IDLE — voice sessions open only on demand
            // (double-tap voice query, report mode).
            appState = AppState.IDLE
        } else {
            appState = AppState.LOADING
        }

        maybePromptBetterVoice()
        confirmVoiceInstallIfReturned()
        confirmAccessibilityReturn()
    }

    override fun onPause() {
        super.onPause()
        isCameraActive = false
        stopContinuousLoop()
        endFollowUpWindow()
        cameraSetup.releaseCamera()
    }

    override fun onStart() {
        super.onStart()
        // PHASE 6: periodic agent-lane hygiene (episodes + orphan sessions).
        // Paused while backgrounded so the app does no work it cannot see.
        startMaintenanceTicker()
    }

    override fun onDestroyView() {
        // TalkBack overlay hygiene: drop the touch-exploration listener and
        // release overlay handlers with the view hierarchy.
        try {
            val am = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            talkbackStateListener?.let { am?.removeTouchExplorationStateChangeListener(it) }
        } catch (_: Throwable) {}
        talkbackStateListener = null
        _fragmentCameraBinding = null
        super.onDestroyView()

        if (this::gestureRouter.isInitialized) gestureRouter.detach()

        val app = try {
            requireActivity().applicationContext as VyzeApplication
        } catch (e: Exception) { null }
        if (app?.coreController != coreController && this::coreController.isInitialized) {
            coreController.destroy()
        }

        // Session audio hygiene ONLY: stop() silences current speech WITHOUT
        // de-initializing the process-wide TTS singleton. Calling onDestroy()
        // here reset isInitialized on the shared engine while the stale engine
        // reference kept blocking reconstruction — after exit + reopen,
        // bootstrapEngine() no-op'd, onInit never fired again, and every
        // speak() buffered into a queue nobody drained → app relaunched mute.
        if (this::ttsManager.isInitialized) ttsManager.stop()
        if (this::hapticManager.isInitialized) hapticManager.cancel()

        cameraSetup.destroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraSetup.updateRotation(fragmentCameraBinding.viewFinder.display)
    }

    // ══════════════════════════════════════════════════════════════════
    // Camera Setup
    // ══════════════════════════════════════════════════════════════════

    private fun setUpCamera() {
        cameraSetup.setupCamera(
            context = requireContext(),
            lifecycleOwner = this,
            previewView = fragmentCameraBinding.viewFinder,
            flashlightMgr = flashlightManager
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Barge-In + Capture
    // ══════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════
    // TalkBack Co-Existence: 50/50 Gesture Overlay + Dual-Script Tutorial
    // ══════════════════════════════════════════════════════════════════
    // When touch exploration (TalkBack) is ON, the transparent overlay
    // halves intercept touches BEFORE TalkBack converts them to hovers:
    //   • Left half  → tap = Look (scene description), long-press = light check
    //   • Right half → tap = Ask (voice query)
    // TalkBack's double-tap-to-activate lands on the focused half and fires
    // the same handler, so every verb maps 1:1 with the physical gesture map
    // (which remains 100% active whenever touch exploration is OFF — the
    // overlay is passive then and never intercepts).
    // Infrequent utilities (color analysis, SOS) ride the two overlay nodes
    // as custom accessibility actions in TalkBack's actions menu.
    // The tutorial is dual-script: TTS narration when TalkBack is off,
    // announceForAccessibility (TalkBack-native queue) when on — the TTS
    // engine is bypassed entirely so the two audio paths never overlap.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Install the TalkBack gesture overlay and accessibility wiring.
     * Idempotent; safe to call multiple times. Adds no new permissions,
     * services, or manifest entries.
     */
    private fun installTalkbackAccessibilityActions() {
        if (talkbackDelegateInstalled) return
        talkbackDelegateInstalled = true

        val binding = _fragmentCameraBinding ?: return

        // ── Overlay tap/long-click handlers (same verbs as gestures) ──
        binding.overlayLook.setOnClickListener {
            systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(TAG, "Overlay[Look] click → scene description")
            performTalkbackLook()
        }
        binding.overlayLook.setOnLongClickListener {
            systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(TAG, "Overlay[Look] long-click → light check")
            performTalkbackLightCheck()
            true
        }
        binding.overlayAsk.setOnClickListener {
            systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(TAG, "Overlay[Ask] click → voice query")
            performTalkbackVoiceQuery()
        }

        // ── Infrequent utilities as custom actions on both overlay nodes ──
        val colorAction = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            R.id.action_color_analysis, "Analyze center color"
        )
        val sosAction = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            R.id.action_emergency_sos, "Trigger emergency SOS"
        )
        val overlayDelegate = object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(colorAction)
                info.addAction(sosAction)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                return when (action) {
                    R.id.action_color_analysis -> { performTalkbackColorAnalysis(); true }
                    R.id.action_emergency_sos -> { performTalkbackEmergencySos(); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        }
        ViewCompat.setAccessibilityDelegate(binding.overlayLook, overlayDelegate)
        ViewCompat.setAccessibilityDelegate(binding.overlayAsk, overlayDelegate)

        // ── Touch-exploration gating (live) ──────────────────────
        // Overlay touches are only consumed while exploration is ON. When
        // TalkBack is off the overlay does not intercept anything, so the
        // physical gesture map (GestureRouter) stays exactly as shipped.
        applyTouchExplorationGating()

        val am = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            mainHandler.post { applyTouchExplorationGating() }
            if (enabled) {
                // Live TalkBack activation: teach the split-screen model once.
                _fragmentCameraBinding?.overlayLook?.announceForAccessibility(
                    getString(R.string.script_tutorial_talkback_on)
                )
            }
        }
        talkbackStateListener = listener
        try {
            am?.addTouchExplorationStateChangeListener(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "TouchExploration listener registration failed: ${e.message}")
        }

        Log.i(TAG, "TalkBack overlay installed (Look | Ask + color/SOS custom actions)")
    }

    /**
     * Gate the overlay's touch interception on the CURRENT touch-exploration
     * state, re-read fresh on every call. While exploration is off every
     * overlay is not clickable, so touches fall through untouched and the
     * physical gesture map behaves exactly as before this feature existed.
     */
    private fun applyTouchExplorationGating() {
        val binding = _fragmentCameraBinding ?: return
        val am = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val exploring = try {
            am != null && am.isEnabled && am.isTouchExplorationEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Touch-exploration probe failed: ${e.message}")
            false
        }
        listOf(binding.overlayLook, binding.overlayAsk).forEach { overlay ->
            overlay.isClickable = exploring
            overlay.isLongClickable = exploring
            overlay.isFocusable = exploring
            overlay.importantForAccessibility =
                if (exploring) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        Log.d(TAG, "Touch-exploration gating: overlay intercept=$exploring")
    }

    /**
     * Dual-script onboarding tutorial (dispatch point for both engine-ready
     * callbacks). TalkBack OFF → [R.string.script_tutorial_talkback_off] via
     * TTS. TalkBack ON → [R.string.script_tutorial_talkback_on] announced
     * through TalkBack's native queue via announceForAccessibility — the TTS
     * engine is completely bypassed so the two audio paths never overlap.
     */
    private fun playOnboardingTutorial(mainActivity: MainActivity) {
        val exploring = try {
            val am = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am != null && am.isEnabled && am.isTouchExplorationEnabled
        } catch (e: Throwable) {
            false
        }
        if (exploring) {
            val host = _fragmentCameraBinding?.overlayLook
                ?: _fragmentCameraBinding?.cameraContainer
            if (host != null) {
                host.announceForAccessibility(getString(R.string.script_tutorial_talkback_on))
                Log.i(TAG, "Tutorial: TalkBack ON → announceForAccessibility (TTS bypassed)")
                return
            }
            // No view host available — fall through to TTS rather than stay silent.
        }
        mainActivity.speakThenCallback(getString(R.string.script_tutorial_talkback_off)) {
            Log.d(TAG, "Tutorial spoken — staying quiet (mic closed at IDLE)")
        }
    }

    // ── TalkBack action handlers (route into the existing verbs only) ──

    private fun performTalkbackLook() {
        systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.i(TAG, "TalkBack action: look (single-tap equivalent)")
        // Same prompt as the physical single-tap; coordinates are the view
        // center since a screen-reader activation carries no touch position.
        val container = _fragmentCameraBinding?.cameraContainer
        val x = (container?.width ?: 0) / 2f
        val y = (container?.height ?: 0) / 2f
        bargeInAndCapture(
            "User tapped at position (${x.toInt()}, ${y.toInt()}). " +
            "Describe what is in front of me and around me for navigation, in 1-2 " +
            "complete, natural spoken sentences with a subject and a verb — the way " +
            "you would tell a person standing next to me. For each key object include " +
            "color, size, material, and state. " +
            "If the tapped object is a packaged product (packet, box, bottle, can), " +
            "first say its BRAND name and product type exactly as printed " +
            "(for example: Maggi instant noodle packet), then its details, then continue. " +
            "If the tapped object has text on it (a label, box, or sign), " +
            "read it aloud verbatim as whole words and sentences, never spelling letter by letter. " +
            "Read the ENTIRE text on the object in reading order; do not stop halfway."
        )
    }

    private fun performTalkbackVoiceQuery() {
        systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.i(TAG, "TalkBack action: voice query (double-tap equivalent)")
        openVoiceQuery()
    }

    private fun performTalkbackLightCheck() {
        systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.i(TAG, "TalkBack action: light check (long-press equivalent)")
        performLightCheck()
    }

    private fun performTalkbackColorAnalysis() {
        systemVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.i(TAG, "TalkBack action: color analysis (triple-tap equivalent)")
        performColorAnalysisTalkback()
    }

    private fun performTalkbackEmergencySos() {
        systemVibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.i(TAG, "TalkBack action: emergency SOS (triple-tap-hold equivalent)")
        triggerEmergencySosFromTalkback()
    }

    /**
     * Color analysis for the TalkBack path: mirrors GestureRouter's
     * triple-tap pipeline — "analyzing" cue → container bitmap →
     * ColorAnalyzer.analyzeCenterColor → localized announcement, persisted
     * via the fragment-owned ScanRepository.
     */
    private fun performColorAnalysisTalkback() {
        val mainActivity = activity as? MainActivity ?: return
        val containerView = _fragmentCameraBinding?.cameraContainer ?: return
        ttsManager.speakImmediate(
            ttsManager.localized(
                mainActivity.getString(R.string.color_analyzing),
                "Menganalisis warna...",
                "正在分析颜色"
            )
        )
        try {
            val bitmap = Bitmap.createBitmap(
                containerView.width.coerceAtLeast(1),
                containerView.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            containerView.draw(canvas)

            val colorName = ColorAnalyzer().analyzeCenterColor(mainActivity, bitmap)
            bitmap.recycle()

            ttsManager.speak(
                mainActivity.getString(R.string.color_result, colorName)
            )

            lifecycleScope.launch {
                talkbackScanRepository.saveColorScan(colorName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TalkBack color analysis failed", e)
            ttsManager.speakImmediate(
                ttsManager.localized(
                    "Color analysis failed.",
                    "Analisis warna gagal.",
                    "颜色分析失败。"
                )
            )
        }
    }

    /**
     * Emergency SOS for the TalkBack path: localized announcement, then the
     * dialer on 999 (dial delay mirrors GestureRouter's SOS constant).
     */
    private fun triggerEmergencySosFromTalkback() {
        val mainActivity = activity as? MainActivity ?: return
        ttsManager.speakImmediate(
            ttsManager.localized(
                mainActivity.getString(R.string.sos_activated),
                "Mod kecemasan diaktifkan. Membuka dialer.",
                "紧急模式已激活，正在打开拨号器。"
            )
        )
        mainHandler.postDelayed({
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:999")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open dialer for SOS", e)
                ttsManager.speakImmediate("Could not open dialer.")
            }
        }, 1500L /* mirrors GestureRouter.SOS_DIAL_DELAY_MS (private) */)
    }

    private fun bargeInAndCapture(query: String) {
        // PHASE 3 SHADOW ROUTER: observe + log only (see logShadowRoute).
        logShadowRoute(query)

        // Any new gesture always ends an in-progress voice audition first.
        stopVoiceAuditionIfActive()

        // ── DEBOUNCE: reject if triggered too recently ───────────
        // Prevents double-fire from gesture+click overlap or speech re-trigger.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerTime < TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "Barge-in debounced (${now - lastTriggerTime}ms < ${TRIGGER_DEBOUNCE_MS}ms)")
            return
        }
        lastTriggerTime = now

        // ── ATOMIC STATE CLAIM: lock before any async work ────────
        // Must happen synchronously on the main thread BEFORE calling
        // interruptTtsWithMicPause() (which may restart speech recognition).
        // This prevents a secondary speech callback from re-entering.
        if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
            Log.d(TAG, "Barge-in: state=$appState — cancelling active work")
            coreController.resetForNewCapture()
        } else {
            coreController.resetForNewCapture()
        }
        // Immediately set ANALYZING — blocks any concurrent triggers
        // (speech callbacks, second taps) from passing the guard.
        appState = AppState.ANALYZING

        // ── TAP = TOUCH INPUT: end any hands-free follow-up window ──
        // A tap is a deliberate gesture. Close the conversation mic (and
        // revoke the mic grant in stopVoiceListening() so the cancelled
        // recognizer's late error/results are dropped at the source) and
        // mark this answer as one that must NOT reopen the mic on
        // completion. Previously the follow-up window stayed open under
        // tap analyses — its cancellation error surfaced as a phantom
        // "I did not catch that" before every result.
        keepMicOpenAfterAnswer = false
        endFollowUpWindow()

        val mainActivity = activity as? MainActivity
        mainActivity?.stopListening()
        // NOTE: do NOT reopen the mic here (interruptTtsWithMicPause would
        // restart listening ~100ms later). In a noisy room, other people's
        // conversation gets transcribed as a "query" while the VLM is still
        // analyzing the tap — cancelling the user's in-flight analysis
        // (resetForNewCapture) and replacing it with the overheard chat.
        // The mic stays closed for the whole ANALYZING/SPEAKING phase and
        // is reopened automatically when the app returns to IDLE.
        mainActivity?.interruptTts()
        // Explicit user action: clear any noise pause + backoff (Tier 1 L1/L3)
        // so the mic reopens normally once this analysis finishes.
        mainActivity?.resumeAfterNoisePause()

        // Now safe to extract frame — isInferring lock + ANALYZING state
        // will reject any duplicate triggerVlmSnapshot calls.
        triggerVlmSnapshot(query)
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Snapshot Trigger
    // ══════════════════════════════════════════════════════════════════

    /**
     * Capture a frame for [query], choosing the source by query type.
     *
     * OCR PIPELINE FIX (Phase 2): explicit text-reading queries go through
     * the full-resolution ImageCapture still (~2400px vs the 960x720
     * analyzer stream) — a page of print physically cannot resolve in the
     * analyzer stream, so long letters/documents previously read back
     * garbled or empty. Every other query type (scene, tap, pointing,
     * currency, continuous) keeps the analyzer snapshot: latency and
     * battery behavior unchanged.
     *
     * Fallback: if the high-res take fails (unbind race, hardware error),
     * degrade to the analyzer snapshot instead of failing the query — one
     * lower-quality OCR read beats no answer.
     */
    private fun captureFrameForQuery(
        query: String,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (coreController.isTextReadQuery(query)) {
            CrashLogFile.log(TAG, "Text read query — using high-res still capture")
            cameraSetup.takeHighResSnapshot(
                onBitmap = onBitmap,
                onError = { error ->
                    CrashLogFile.log(TAG, "High-res capture failed ($error) — falling back to analyzer snapshot")
                    cameraSetup.takeSnapshot(onBitmap = onBitmap, onError = onError)
                }
            )
        } else {
            cameraSetup.takeSnapshot(onBitmap = onBitmap, onError = onError)
        }
    }

    private fun triggerVlmSnapshot(query: String) {
        CrashLogFile.log(TAG, "triggerVlmSnapshot: state=$appState, engineReady=${coreController.isEngineReady()}, inferring=${coreController.isCurrentlyInferring()}, capturing=${isCapturing.get()}")

        // ── CAPTURE LOCK: reject during async frame extraction ────
        if (!isCapturing.compareAndSet(false, true)) {
            Log.d(TAG, "Capture already in progress — silently dropping duplicate trigger")
            return
        }

        // ── STATE GUARD ──────────────────────────────────────────
        // bargeInAndCapture already sets appState = ANALYZING, so we
        // also allow ANALYZING to pass (it means bargeIn claimed it).
        // Speech callbacks set appState = IDLE before calling us.
        if (appState != AppState.IDLE && appState != AppState.LISTENING && appState != AppState.ANALYZING) {
            Log.d(TAG, "State=$appState — rejecting snapshot trigger")
            isCapturing.set(false)
            updateStatus("Busy...")
            return
        }

        if (!coreController.isEngineReady()) {
            Log.d(TAG, "VLM not ready yet")
            isCapturing.set(false)
            updateStatus("Model still loading...")
            return
        }

        if (coreController.isCurrentlyInferring()) {
            Log.d(TAG, "Engine busy — ignoring")
            isCapturing.set(false)
            updateStatus("Already analyzing...")
            return
        }

        hapticManager.vibrateTap()
        updateStatus("Capturing...")
        CrashLogFile.log(TAG, "Calling cameraSetup.takeSnapshot()")

        captureFrameForQuery(query,
            onBitmap = { bitmap ->
                // ── SINGLE EXIT: guarantee isCapturing is cleared ──
                try {
                    // Validate bitmap — if recycled or corrupted, abort silently
                    if (bitmap.isRecycled) {
                        CrashLogFile.log(TAG, "Bitmap recycled — silently dropping")
                        // Do NOT speak error here — a newer trigger may have
                        // already started. Just reset state quietly.
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null && appState == AppState.ANALYZING) {
                                appState = AppState.IDLE
                                updateStatus("Ready")
                            }
                        }
                        return@captureFrameForQuery
                    }

                    CrashLogFile.log(TAG, "onBitmap callback: ${bitmap.width}x${bitmap.height}")

                    // ── THERMAL GATE (Phase 2 Step B) — CRITICAL tier ──
                    // VLM inference is halted; refuse the manual query with
                    // a spoken notice. OCR fast-path reads are exempt — the
                    // gate is enforced inside triggerSnapshot, so an OCR
                    // read can still complete here without Gemma.
                    if (!thermalController().policy.vlmInferenceAllowed &&
                        !coreController.isOcrFastPathQuery(query)
                    ) {
                        bitmap.recycle()
                        isCapturing.set(false)
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null) {
                                appState = AppState.IDLE
                                updateStatus("Too hot")
                            }
                        }
                        announceThermalNotice(
                            "Device is too hot for visual analysis. Text reading still works.",
                            "Peranti terlalu panas untuk analisis visual. Pembacaan teks masih berfungsi.",
                            "设备过热，无法进行视觉分析。文字识别仍可使用。"
                        )
                        return@captureFrameForQuery
                    }

                    // Double-check engine isn't already running a different inference
                    if (coreController.isCurrentlyInferring()) {
                        CrashLogFile.log(TAG, "Engine busy — recycling bitmap")
                        bitmap.recycle()
                        return@captureFrameForQuery
                    }

                    // ── INSTANT HAPTIC: acknowledge frame capture immediately ──
                    // This fires BEFORE VLM inference starts, giving the user
                    // tactile feedback that their input was registered (~5ms).
                    systemVibrator?.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    )

                    // State already ANALYZING from bargeInAndCapture — just update UI
                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null) {
                            if (appState != AppState.ANALYZING) appState = AppState.ANALYZING
                            updateStatus("Analyzing...")
                            (activity as? MainActivity)?.announceStatus(
                                // Language FIX: follow the TTS voice, not the device
                                // locale — hardcoded English under the Malay/Chinese
                                // voice is what produced "analising sin".
                                ttsManager.localized(
                                    "Analyzing scene...",
                                    "Menganalisis pemandangan...",
                                    "正在分析画面"
                                )
                            )
                        }
                    }

                    CrashLogFile.log(TAG, "Calling coreController.triggerSnapshot()")
                    coreController.triggerSnapshot(bitmap, query)
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "onBitmap error: ${e.javaClass.simpleName}: ${e.message}", e)
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null && appState == AppState.ANALYZING) {
                            appState = AppState.IDLE
                            updateStatus("Error: ${e.message}")
                        }
                    }
                } finally {
                    // ALWAYS release capture lock — even if bitmap was recycled
                    isCapturing.set(false)
                }
            },
            onError = { error ->
                CrashLogFile.logError(TAG, "Snapshot failed: $error")
                Log.e(TAG, "Snapshot failed: $error")
                isCapturing.set(false)
                // ── ALWAYS recover: reset state + speak error + restart mic.
                //    Never leave the user stuck on 'Capturing...' screen.
                activity?.runOnUiThread {
                    if (isAdded && _fragmentCameraBinding != null) {
                        appState = AppState.IDLE
                        updateStatus("Capture failed")
                        ttsManager.speakImmediate(
                            ttsManager.localized(
                                "Could not capture the scene. Please try again.",
                                "Tidak dapat merakam pemandangan. Sila cuba lagi.",
                                "无法拍摄画面，请再试一次。"
                            )
                        )
                    }
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Speech Recognition Integration
    // ══════════════════════════════════════════════════════════════════

    private fun wireSpeechCallbacks() {
        val activity = requireActivity() as? MainActivity ?: return

        activity.onSpeechResult = { spokenText, detectedLocale, recognizerConfidence ->
            // Recognizer confidence for the grey-band confirmation ask (D).
            // Model-ASR rescue passes 0 — flagged so it never chains a
            // second "did you say" on top of the repeat it already required.
            lastConfidence = recognizerConfidence
            if (spokenText.isNotBlank()) {
                Log.i(TAG, "Speech result: \"$spokenText\" lang=$detectedLocale")
                if (voiceAuditionActive) {
                    handleVoiceAuditionCommand(spokenText)
                } else if (awaitingInstallVoiceAnswer &&
                    !isVoiceSettingsRequest(spokenText) &&
                    !isAccessibilitySettingsRequest(spokenText)
                ) {
                    // A voice-settings or accessibility-settings request always
                    // wins over the pending "install a better voice?" yes/no
                    // question — the user is asking for something else, so
                    // route them to that instead of parsing a yes/no answer.
                    handleVoiceInstallAnswer(spokenText)
                } else {
                    // ── BARGE-IN GUARD (voice session fix) ──────────────
                    // Only a REAL user query (app idle or listening) may stop
                    // the current speech AND steer the language state. In
                    // ANALYZING/SPEAKING the noise gate below DROPS this
                    // result as ambient noise — stopping TTS there cut the
                    // answer off mid-sentence: the SpeechRecognizer's
                    // end-of-session drift kept capturing the tail of the
                    // just-spoken ANSWER, and that stale transcription
                    // re-entered here and barged in.
                    // setUserLocale is gated identically: a dropped result
                    // detected as device-English would otherwise REVERT the
                    // mirrored Malay/Chinese TTS voice mid-answer, and the
                    // rest of the answer would play with the wrong voice.
                    if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
                        Log.d(TAG, "Speech result during $appState — skipping barge-in + locale change (noise gate will drop)")
                    } else {
                        activity.interruptTts()
                        coreController.setUserLocale(detectedLocale)
                    }

                    when {
                        // ── REPORT MODE: speech is the report content ──
                        appState == AppState.REPORTING -> {
                            handleReportContent(spokenText)
                        }
                        // ── REPORT TRIGGER: enter report mode ─────────
                        reportManager.isReportTrigger(spokenText) -> {
                            enterReportMode()
                        }
                        // ── NORMAL VLM PIPELINE ──────────────────────
                        else -> {
                        // PHASE 3 SHADOW ROUTER (speech lane): every query
                        // reaching the speech-intelligence router is also
                        // observed by the shadow router — same log-only,
                        // flag-gated observation the tap lane gets in
                        // [bargeInAndCapture]. Nothing is executed here.
                        logShadowRoute(spokenText)

                        // ── ACCESSIBILITY SETTINGS COMMAND ───────
                        // "open accessibility settings" takes the user to
                        // the system screen to disable TalkBack.
                        if (isAccessibilitySettingsRequest(spokenText)) {
                            openAccessibilitySettingsFlow()
                        } else if (isVoiceSettingsRequest(spokenText)) {
                            // "voice settings" / "change your voice" starts
                            // the hands-free voice audition (no screen needed).
                            startVoiceAudition()
                        } else if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
                                // ── NOISE GATE ────────────────────────
                                // The recognizer can transcribe other people's
                                // conversation as a "query" in a noisy room.
                                // If the app is already analyzing or speaking,
                                // drop the result instead of cancelling the
                                // user's in-flight query — otherwise ambient
                                // chat makes the response come back "lost" and
                                // restarts the recognition beep loop.
                                // Voice session fix: a dropped result must NOT
                                // flip keepMicOpenAfterAnswer to false — the
                                // window flag belongs to the user's original
                                // gesture, not to the dropped noise.
                                Log.d(TAG, "Speech result during $appState — dropping (possible ambient noise)")
                            } else if (pendingConfirmationText != null) {
                                // ── CONFIRMATION ANSWER ─────────────────
                                // A "Did you say X?" is outstanding — the next
                                // utterance is a yes/no answer, NOT a new query.
                                handleConfirmationAnswer(spokenText)
                            } else {
                                // ── SPEECH-INTELLIGENCE ROUTING ────────
                                // 1. Conversation verbs ("tell me more") —
                                //    reuse the retained frame, no re-capture.
                                //    MUST be checked before resetForNewCapture:
                                //    the reset wipes the prior-answer anchor.
                                // 2. Instant answers (time/date/battery) —
                                //    local, no capture, no inference.
                                // 3. VLM PRE-GATE (latency lever #1, ships
                                //    dark): when enabled, high-confidence
                                //    IGNORE families (app-cue echo / greeting
                                //    garble / filler-only) skip the VLM — see
                                //    PreGatePolicy. Deliberately INSIDE this
                                //    branch: the settings/noise-gate/
                                //    confirmation handling above must never be
                                //    bypassed (a garble picked up mid-answer
                                //    must be dropped by the noise gate, not
                                //    flush the in-flight response).
                                // 4. Normal pipelines (existing behavior).
                                if (maybeHandlePreGate(spokenText)) {
                                    // Handled: no capture, no VLM, no state
                                    // change (see maybeHandlePreGate).
                                } else if (coreController.detectConversationVerb(spokenText)) {
                                    Log.d(TAG, "Conversation verb: \"$spokenText\" — expanding on retained frame")
                                    appState = AppState.ANALYZING
                                    updateStatus("Answering...")
                                    coreController.triggerExpandedAnswer()
                                } else if (coreController.detectInstantAnswer(spokenText)) {
                                    Log.d(TAG, "Instant answer: \"$spokenText\"")
                                    coreController.triggerInstantAnswer(spokenText)
                                    // Proper speech lifecycle: speakQueued flushed
                                    // the active recognizer session (tts.stop), so
                                    // "staying listening" left dead air. Run the
                                    // SPEAKING state, drain the utterance, then
                                    // reopen the follow-up mic like any answer.
                                    appState = AppState.SPEAKING
                                    updateStatus("Ready [instant]")
                                    consecutiveConfirmationAsks = 0
                                    waitForTtsDrain {
                                        appState = AppState.IDLE
                                        maybeOpenFollowUpWindow()
                                    }
                                } else if (lastConfidence in 0.35f..CONFIRM_ABOVE_CONFIDENCE &&
                                    consecutiveConfirmationAsks < MAX_CONFIRMATION_ASKS
                                ) {
                                    askQueryConfirmation(spokenText, detectedLocale)
                                } else if (coreController.isTextOnlyQuery(spokenText)) {
                                    // ── TEXT-ONLY Q&A ────────────────────
                                    // General-knowledge question ("what is
                                    // paracetamol used for?") — no camera frame
                                    // needed. Faster + cheaper than image inference.
                                    Log.d(TAG, "Text-only query: \"$spokenText\"")
                                    consecutiveConfirmationAsks = 0
                                    coreController.resetForNewCapture()
                                    appState = AppState.ANALYZING
                                    updateStatus("Answering...")
                                    // PHASE 4: agent-path live route (ships dark
                                    // behind the flag); triggerTextQuery runs when
                                    // declined, dark, or stale.
                                    beginLiveRouteOrLegacyTextOnly(spokenText)
                                } else {
                                    consecutiveConfirmationAsks = 0
                                    coreController.resetForNewCapture()
                                    appState = AppState.IDLE
                                    updateStatus("Heard: \"$spokenText\"")
                                    triggerVlmSnapshot(spokenText)
                                }
                            }
                        }
                    }
                }
            }
        }

        activity.onPartialSpeechResult = { partial ->
            if (isAdded && _fragmentCameraBinding != null) {
                if (partial.isNullOrBlank()) {
                    appState = AppState.LISTENING
                    updateStatus("Listening...")
                } else {
                    updateStatus("Listening: \"$partial\"")
                }
            }
        }

        activity.onSpeechError = { errorMsg ->
            if (isAdded && _fragmentCameraBinding != null) {
                Log.w(TAG, "Speech error: $errorMsg")

                if (voiceAuditionActive) {
                    // Voice audition: a silent/errored cycle just means the
                    // user didn't answer — re-hint and keep listening.
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null) {
                        val hint = if (voiceAuditionLangStep) {
                            ttsManager.localized(
                                "Say English, Malay, or Chinese, or say cancel.",
                                "Sebut English, Melayu, atau Chinese, atau sebut batal.",
                                "请说“英语”、“马来语”或“中文”，或说“取消”。"
                            )
                        } else {
                            ttsManager.localized(
                                "Say next, use this, or cancel.",
                                "Sebut seterusnya, guna ini, atau batal.",
                                "请说“下一个”、“用这个”或“取消”。"
                            )
                        }
                        mainActivity.speakThenCallback(hint) {
                            mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                        }
                    }
                } else if (appState != AppState.LISTENING && appState != AppState.REPORTING) {
                    // ── STALE-ERROR GUARD ──────────────────────────────
                    // A recognizer error can arrive AFTER the listening session
                    // it belonged to was superseded (a tap started an analysis,
                    // an answer is speaking, the window already closed). Such an
                    // error must never force state changes or speak "I did not
                    // catch that" around a fresh result.
                    Log.d(TAG, "Speech error during state=$appState — stale, ignoring")
                } else {
                    // ── CONVERSATION WINDOW: a silent recognizer cycle inside
                    // the follow-up window just means the user paused. Quietly
                    // reopen the mic while the deadline is still valid; the
                    // watchdog closes the window when it expires.
                    val pausedInWindow = inConversationWindow &&
                        (errorMsg.contains("No speech") || errorMsg.contains("timed out"))
                    if (pausedInWindow) {
                        // NEVER clobber an in-flight tap/analysis/answer: if the
                        // app is not actually LISTENING, this error belongs to a
                        // stale cycle (e.g. the mic was cancelled by a tap that
                        // started an analysis). Close the window state quietly
                        // and let the analysis/answer finish on its own.
                        if (appState != AppState.LISTENING) {
                            Log.d(TAG, "Speech error during $appState — stale, closing window only")
                            endFollowUpWindow()
                        } else if (android.os.SystemClock.elapsedRealtime() < conversationDeadlineMs) {
                            appState = AppState.LISTENING
                            updateStatus("Listening...")
                            mainHandler.postDelayed({ startVoiceListening() }, FOLLOW_UP_RETRY_DELAY_MS)
                        } else {
                            endFollowUpWindow()
                        }
                    } else {
                        val wasVoiceSession = appState == AppState.LISTENING
                        appState = AppState.IDLE
                        updateStatus("Ready")
                        // Voice sessions are on-demand — an empty/failed session
                        // ends quietly instead of auto-restarting the mic forever.
                        if (wasVoiceSession) {
                            try {
                                ttsManager.speakQueued(
                                    ttsManager.localized(
                                        "I did not catch that. Double tap and try again.",
                                        "Saya tidak dengar itu. Sentuh dua kali dan cuba lagi.",
                                        "我没有听清，请双击屏幕再试一次。"
                                    )
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }

        // ── TIER 1 L3: NOISY-ROOM AUTO-ADAPT ─────────────────────
        // The recognizer kept committing ambient conversation. Announce
        // it and stay quiet until the user taps to analyze.
        activity.onNoiseDetected = {
            if (isAdded && _fragmentCameraBinding != null) {
                Log.w(TAG, "Noisy room detected")
                stopVoiceAuditionIfActive()
                // Voice session fix: never kill a follow-up window while a
                // query is in flight — the noise rejection that increment
                // rejectedCycleCount was DROPPED ambient noise (see the
                // noise gate above), not a failed user attempt. Ending the
                // window here cut hands-free double-tap sessions after the
                // first answer while single-tap flow kept working.
                if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
                    Log.d(TAG, "Noise detected during $appState — window kept open until answer finishes")
                } else {
                    endFollowUpWindow()
                    updateStatus("Noisy — tap or double tap to continue")
                    // Announcement only when quiet: speakQueued() escalates to
                    // QUEUE_FLUSH, which would cut the in-flight answer.
                    try {
                        ttsManager.speakQueued(
                            ttsManager.localized(
                                "The room is noisy. Tap once to look, or double tap to ask.",
                                "Bunyi bising. Sentuh sekali untuk melihat, atau sentuh dua kali untuk bertanya.",
                                "周围很吵，单击看画面，或双击提问。"
                            )
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Voice-Driven Bug Reporting
    // ══════════════════════════════════════════════════════════════════

    /**
     * Enter report mode. The next speech result will be treated as
     * bug report content instead of a VLM query.
     */
    private fun enterReportMode() {
        stopVoiceAuditionIfActive()
        endFollowUpWindow()
        appState = AppState.REPORTING
        updateStatus("Report mode — speak your issue")
        Log.i(TAG, "Entering REPORTING mode")
        CrashLogFile.log(TAG, "Report mode activated")

        val mainActivity = activity as? MainActivity
        mainActivity?.speakThenCallback(
            "Report mode activated. Please describe the issue you want to report."
        ) {
            // After the prompt finishes, start listening for the report
            mainHandler.postDelayed({ startVoiceListening() }, 300L)
        }
    }

    /**
     * Handle the report content spoken by the user.
     * Saves to file, then launches email intent.
     */
    private fun handleReportContent(reportText: String) {
        Log.i(TAG, "Report content received: ${reportText.take(80)}...")
        CrashLogFile.log(TAG, "Report content: ${reportText.take(100)}")
        updateStatus("Saving report...")

        // Save report to local file
        val reportFile = reportManager.saveReport(reportText)
        if (reportFile == null) {
            appState = AppState.IDLE
            updateStatus("Report save failed")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Failed to save report. Please try again."
            ) {}
            return
        }

        // Launch email intent
        val emailSent = reportManager.sendReportEmail(reportFile)

        if (emailSent) {
            appState = AppState.IDLE
            updateStatus("Report saved & email ready")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Report saved. Email is ready — please tap Send to submit."
            ) {}
        } else {
            // Email client not available — report still saved locally
            appState = AppState.IDLE
            updateStatus("Report saved (no email client)")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Report saved to Downloads folder. No email app found."
            ) {}
        }
    }

    /**
     * Wait for the TTS engine to finish playing all queued utterances.
     *
     * KEY FIX: Initial delay of 500ms before first poll. The final
     * flushRemainingSentenceBuffer() posts speak() to mainHandler, and
     * then onInferenceComplete also posts to mainHandler. When
     * waitForTtsDrain runs, the TTS engine may not have registered the
     * utterance as "speaking" yet — isSpeaking() returns false on the
     * first check, causing premature exit and audio cutoff.
     *
     * The 500ms initial delay gives the TTS engine time to transition
     * from "queued" to "active playback" before we start polling.
     */
    private fun waitForTtsDrain(onDone: () -> Unit) {
        // Use deterministic utterance ID tracking instead of isSpeaking() polling.
        // hasPendingSpeech() returns true iff pendingUtteranceIds is non-empty.
        // Each speak() call adds an ID; onDone/onError removes it.
        //
        // NOTE: there is no silent tail utterance under the platform engine
        // (the old playSilentUtterance is removed). The grace period below is
        // what covers the hardware AudioTrack drain after the last onDone.
        //
        // After hasPendingSpeech() == false, an additional 600ms grace
        // period ensures the speaker has finished emitting the last
        // audible phoneme before we release audio focus and restart mic.
        val checkRunnable = object : Runnable {
            override fun run() {
                if (ttsManager.hasPendingSpeech()) {
                    mainHandler.postDelayed(this, 150L)
                } else {
                    // All utterances have completed.
                    // Add a final 600ms grace for AudioTrack hardware drain.
                    // Audio focus stays held for the entire session — only released on app destroy.
                    mainHandler.postDelayed({
                        onDone()
                    }, AUDIO_DRAIN_GRACE_MS)
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 300L)
    }

    // ══════════════════════════════════════════════════════════════════
    // Continuous Auto-Snapshot Mode
    // ══════════════════════════════════════════════════════════════════

    /**
     * Trigger a continuous-mode snapshot. Unlike [triggerVlmSnapshot],
     * this does NOT acquire the debounce or isCapturing locks — the
     * loop's own state guards prevent concurrent calls.
     */
    private fun triggerContinuousSnapshot() {
        if (!coreController.isEngineReady()) return
        if (coreController.isCurrentlyInferring()) return
        if (isCapturing.get()) return

        // ── THERMAL GATE (Phase 2 Step B) ─────────────────────────
        // MODERATE: continuous captures still run, but the controller
        // caps dimension/tokens at inference time. SEVERE+: continuous
        // mode must not run at all — drop this tick (the thermal
        // escalation hook has already paused the loop and spoken).
        val thermalPolicy = thermalController().policy
        if (!thermalPolicy.continuousModeAllowed) {
            Log.d(TAG, "Continuous capture skipped — thermal policy ${thermalPolicy.label}")
            return
        }

        if (!isCapturing.compareAndSet(false, true)) return

        appState = AppState.ANALYZING
        // Continuous mode is touch/auto driven — never reopen the mic after it.
        keepMicOpenAfterAnswer = false
        updateStatus("Scanning...")

        cameraSetup.takeSnapshot(
            onCaptureStart = { captureStartAtMs = System.currentTimeMillis() },
            onBitmap = { bitmap ->
                try {
                    if (bitmap.isRecycled) {
                        isCapturing.set(false)
                        appState = AppState.IDLE
                        return@takeSnapshot
                    }

                    if (coreController.isCurrentlyInferring()) {
                        bitmap.recycle()
                        isCapturing.set(false)
                        return@takeSnapshot
                    }

                    // Scene-unchanged gating: the controller skips the Gemma
                    // run and fires onContinuousSkip instead of a full answer.
                    // Return the state machine to IDLE so the auto-capture
                    // loop keeps running without a spoken response.
                    coreController.onContinuousSkip = {
                        activity?.runOnUiThread {
                            if (appState == AppState.ANALYZING) {
                                appState = AppState.IDLE
                                updateStatus("Scene unchanged")
                            }
                        }
                    }
                    coreController.triggerSnapshot(bitmap, null, continuousMode = true)
                } catch (e: Throwable) {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    appState = AppState.IDLE
                } finally {
                    isCapturing.set(false)
                }
            },
            onError = { _ ->
                isCapturing.set(false)
                if (appState == AppState.ANALYZING) appState = AppState.IDLE
            }
        )
    }

    /**
     * Toggle continuous auto-snapshot mode on/off.
     * When ON: captures and describes the scene every 4 seconds.
     * When OFF: returns to manual tap/voice triggers only.
     */
    fun toggleContinuousMode() {
        isContinuousMode = !isContinuousMode
        // ── THERMAL GUARD (Phase 2 Step B) ────────────────────────────
        // SEVERE+ forbids continuous mode. Auto-pause with a spoken notice
        // instead of letting the thermal escalation hook and the user's
        // toggle fight each other.
        if (isContinuousMode && !thermalController().policy.continuousModeAllowed) {
            isContinuousMode = false
            announceThermalNotice(
                "Continuous mode paused. Device is warm. Use tap or voice queries.",
                "Mod berterusan dijeda. Peranti panas. Gunakan ketukan atau suara.",
                "连续模式已暂停。设备发热。请使用点按或语音查询。"
            )
            return
        }
        if (isContinuousMode) {
            continuousModeStartTime = System.currentTimeMillis()
            Log.d(TAG, "Continuous mode ON — auto-snapshot every ${AUTO_SNAPSHOT_INTERVAL_MS}ms (throttle after ${CONTINUOUS_MODE_THROTTLE_AFTER_MS / 1000}s)")
            updateStatus("Continuous mode ON")
            mainHandler.postDelayed(autoSnapshotRunnable, AUTO_SNAPSHOT_INTERVAL_MS)
        } else {
            Log.d(TAG, "Continuous mode OFF")
            mainHandler.removeCallbacks(autoSnapshotRunnable)
            updateStatus("Continuous mode OFF")
        }
    }

    private fun startContinuousLoop() {
        if (isContinuousMode && !mainHandler.hasCallbacks(autoSnapshotRunnable)) {
            mainHandler.postDelayed(autoSnapshotRunnable, AUTO_SNAPSHOT_INTERVAL_MS)
        }
    }

    private fun stopContinuousLoop() {
        mainHandler.removeCallbacks(autoSnapshotRunnable)
    }

    // ── Thermal Governor Access (Phase 2 Step B) ──────────────────

    /** App-scoped thermal governor; NORMAL fallback if unavailable. */
    private fun thermalController(): ThermalPowerController =
        (requireActivity().application as? VyzeApplication)?.thermalPowerController
            ?: ThermalPowerController(requireContext().applicationContext)

    /**
     * Local capture-start timestamp for the CURRENT demand — set through
     * takeSnapshot's onCaptureStart hook. Reserved for capture-latency
     * telemetry when tuning the thermal tiers on device; no readers yet.
     */
    @Volatile
    private var captureStartAtMs: Long = 0L

    /**
     * Speak a thermal notice through the ACTIVE TTS voice language. Used by
     * the governor's escalation hook (fires once per tier escalation) and
     * by user-initiated actions refused by the current policy.
     */
    private fun announceThermalNotice(english: String, malay: String, chinese: String) {
        ttsManager.speakImmediate(ttsManager.localized(english, malay, chinese))
    }

    private fun startVoiceListening() {
        if (!isAdded) return
        try {
            val activity = requireActivity() as? MainActivity ?: return
            // Declare that the mic is genuinely wanted. MainActivity uses this
            // grant to drop stale recognizer callbacks once the user aborts the
            // session with a tap — otherwise the cancelled session's error would
            // surface as phantom "I did not catch that" speech around answers.
            activity.voiceSessionWanted = true
            activity.startListeningSafely()
        } catch (e: Throwable) {
            Log.w(TAG, "startVoiceListening failed: ${e.message}")
        }
    }

    /**
     * Double-tap "ask" session: barge-in, cue the user, then open the mic
     * for ONE question. The session ends when speech is recognized or the
     * recognizer errors — the mic never stays open at IDLE.
     */
    private fun openVoiceQuery() {
        if (!isAdded) return
        stopVoiceAuditionIfActive()

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerTime < TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "Voice query debounced")
            return
        }
        lastTriggerTime = now

        val mainActivity = activity as? MainActivity ?: return

        // Barge-in: cancel any in-flight analysis/response before asking
        coreController.resetForNewCapture()
        endFollowUpWindow()
        hapticManager.vibrateDoubleTap()
        mainActivity.interruptTts()
        mainActivity.resumeAfterNoisePause()

        // Open a fresh conversation session with a spoken cue. Answers to
        // voice queries reopen the mic automatically (maybeOpenFollowUpWindow)
        // so follow-ups flow hands-free until the user goes quiet. Single taps
        // flip this flag back to false — tap answers stay quiet at IDLE.
        keepMicOpenAfterAnswer = true
        startFollowUpWindow(cue = true)
    }

    /**
     * Long-press light check: report ambient light + flashlight status.
     * The torch itself is managed continuously by the auto-torch luminance
     * monitor, so this is a status readout on demand.
     */
    private fun performLightCheck() {
        if (!isAdded) return
        stopVoiceAuditionIfActive()
        hapticManager.vibrateTap()
        val dark = cameraSetup.isEnvironmentDark()
        val torchOn = flashlightManager.isTorchOn()
        Log.d(TAG, "Light check: dark=$dark torchOn=$torchOn")
        try {
            if (dark) {
                // If the auto-torch hasn't flipped yet, apply it now
                if (!torchOn) flashlightManager.toggleTorch(true)
                ttsManager.speakQueued(
                    ttsManager.localized(
                        "It is dark. Flashlight is on.",
                        "Gelap. Lampu suluh dihidupkan.",
                        "光线很暗，已打开手电筒。"
                    )
                )
            } else {
                ttsManager.speakQueued(
                    ttsManager.localized(
                        "Light is sufficient. Flashlight is off.",
                        "Cahaya mencukupi. Lampu suluh dimatikan.",
                        "光线充足，手电筒已关闭。"
                    )
                )
            }
        } catch (_: Throwable) {}
        updateStatus(if (dark) "Dark — flashlight on" else "Light sufficient")
    }

    // ══════════════════════════════════════════════════════════════════
    // Better Voice Prompt (Tier 1)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Post-onboarding prompt (first run only): if the installed voice pack is
     * the robotic base quality, offer to open Google's voice installer.
     * Asked once per session; marked resolved forever once answered.
     */
    private fun maybePromptBetterVoice() {
        if (!isAdded || voicePromptHandledInSession || !onboardingSpoken) return
        if (awaitingInstallVoiceAnswer) return
        if (appState != AppState.IDLE) return
        voicePromptHandledInSession = true

        try {
            if (!ttsManager.isReady() || !ttsManager.isVoiceQualityLow()) return
            val prefs = requireContext()
                .getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(TTSManager.KEY_VOICE_PROMPT_RESOLVED, false)) return

            Log.d(TAG, "Weak voice detected — offering better voice install")
            awaitingInstallVoiceAnswer = true
            val mainActivity = activity as? MainActivity ?: return
            mainActivity.speakThenCallback(
                "Your current voice sounds basic. A better voice is available for free from Google. " +
                "Say yes to open the voice installer, or say skip."
            ) {
                // Open the mic after the prompt so the user can answer
                mainHandler.postDelayed({
                    startVoiceListening()
                    // Close quietly if they never answer
                    mainHandler.postDelayed({
                        if (awaitingInstallVoiceAnswer) {
                            awaitingInstallVoiceAnswer = false
                            stopVoiceListening()
                        }
                    }, VOICE_INSTALL_WAIT_MS)
                }, VOICE_INSTALL_PROMPT_GAP_MS)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "maybePromptBetterVoice failed: ${e.message}")
            awaitingInstallVoiceAnswer = false
        }
    }

    /** Parse the user's yes/no answer to the better-voice question. */
    private fun handleVoiceInstallAnswer(spokenText: String) {
        awaitingInstallVoiceAnswer = false
        stopVoiceListening()
        val text = spokenText.trim().lowercase()
        val yes = listOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
            "install", "better", "ya", "boleh", "好", "是", "要"
        ).any { text.contains(it) }
        val no = listOf(
            "no", "nope", "skip", "not now", "later", "cancel",
            "tak", "tidak", "jangan", "不用", "不要", "跳过"
        ).any { text.contains(it) }

        val prefs = requireContext()
            .getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(TTSManager.KEY_VOICE_PROMPT_RESOLVED, true).apply()

        val mainActivity = activity as? MainActivity ?: return
        if (yes && !no) {
            Log.i(TAG, "User chose to install a better voice")
            pendingVoiceInstallConfirmation = true
            if (mainActivity.openTtsVoiceInstaller()) {
                ttsManager.speakQueued("Opening the voice installer. Pick a higher quality voice, then come back.")
            } else {
                pendingVoiceInstallConfirmation = false
                ttsManager.speakQueued("The voice installer is not available on this device. You can manage voices in Android TTS settings.")
            }
        } else {
            Log.i(TAG, "User skipped the better voice prompt")
            ttsManager.speakQueued("No problem. You can change my voice anytime in Voice Settings.")
        }
    }

    /**
     * After returning from the Google voice installer, re-check the voice
     * and confirm whether a better pack was installed.
     */
    private fun confirmVoiceInstallIfReturned() {
        if (!isAdded || !pendingVoiceInstallConfirmation) return
        pendingVoiceInstallConfirmation = false
        mainHandler.postDelayed({
            try {
                ttsManager.applySettings(requireContext())
                if (!ttsManager.isVoiceQualityLow()) {
                    ttsManager.speakQueued("Better voice installed. I will use it from now on.")
                } else {
                    ttsManager.speakQueued("I did not detect a new voice. You can pick one in Voice Settings.")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "confirmVoiceInstallIfReturned failed: ${e.message}")
            }
        }, VOICE_INSTALL_RECHECK_DELAY_MS)
    }

    private fun stopVoiceListening() {
        try {
            val activity = requireActivity() as? MainActivity ?: return
            activity.stopListening()
            // Revoke the mic grant: any recognizer callback still in flight
            // from this session is now stale and must be dropped at the source.
            activity.voiceSessionWanted = false
        } catch (e: Throwable) {
            Log.w(TAG, "stopVoiceListening failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Voice Settings Command
    // ══════════════════════════════════════════════════════════════════

    /** Spoken command phrases that start the hands-free voice audition. */
    private fun isVoiceSettingsRequest(text: String): Boolean {
        val t = text.trim().lowercase()
        if (VOICE_SETTINGS_PHRASES.any { t.contains(it) }) return true

        // Fallback 1 — recognizer variants the direct list can miss
        // ("voice setting" without the trailing s, "sound setting",
        // "set my voice"): a voice-word plus an intent-word anywhere.
        val hasVoiceWord = listOf("voice", "suara", "语音", "声音").any { t.contains(it) }
        val hasIntentWord = listOf(
            "setting", "settings", "setup", "set ", "change", "changing",
            "choose", "pick", "select", "option", "preference", "preferences",
            "tetapan", "tukar", "ganti", "ubah", "设置", "设定", "选择", "更换"
        ).any { t.contains(it) }
        if (hasVoiceWord && hasIntentWord) return true

        // Fallback 2 — a very short utterance that is basically the command
        // itself ("voice", "suara"), typically spoken right after the
        // listening cue that just mentioned "voice settings".
        val wordCount = t.split(Regex("\\s+")).size
        return wordCount <= 2 && hasVoiceWord
    }

    // ══════════════════════════════════════════════════════════════════
    // Accessibility Settings Command (disable TalkBack flow)
    // ══════════════════════════════════════════════════════════════════

    /**
     * True when the user asks to open the system Accessibility Settings
     * ("open accessibility settings", "turn off talkback", …). Vyze can't
     * pause TalkBack itself, so this is the guided path to disable it.
     */
    private fun isAccessibilitySettingsRequest(text: String): Boolean {
        val t = text.trim().lowercase()
        if (ACCESSIBILITY_SETTINGS_PHRASES.any { t.contains(it) }) return true

        // Fallback — a talkback-word plus an intent-word anywhere
        // ("talkback off", "turn talkback", "screen reader settings").
        val hasTalkBackWord = listOf(
            "talkback", "talk back", "screen reader", "screenreader",
            "kebolehaksesan", "aksesibiliti", "辅助", "无障碍"
        ).any { t.contains(it) }
        val hasIntentWord = listOf(
            "off", "disable", "turn", "stop", "close", "setting", "settings",
            "tutup", "matikan", "buka", "设置", "关闭"
        ).any { t.contains(it) }
        if (hasTalkBackWord && hasIntentWord) return true

        // Very short utterance that is basically the command itself
        // ("talkback"), spoken right after the listening cue.
        val wordCount = t.split(Regex("\\s+")).size
        return wordCount <= 2 && hasTalkBackWord
    }

    /**
     * Voice command handler: open the system Accessibility Settings screen
     * so the user can turn TalkBack off (TalkBack still works there, so they
     * can navigate it). On return, [confirmAccessibilityReturn] re-checks.
     */
    private fun openAccessibilitySettingsFlow() {
        if (!isAdded) return
        Log.i(TAG, "Voice command: opening Accessibility Settings")
        stopVoiceAuditionIfActive()
        stopVoiceListening()
        endFollowUpWindow()
        // Supersede the pending "install a better voice?" question if any.
        awaitingInstallVoiceAnswer = false
        coreController.resetForNewCapture()
        hapticManager.vibrateTap()

        val mainActivity = activity as? MainActivity ?: return
        pendingAccessibilityReturn = true
        if (mainActivity.openAccessibilitySettings()) {
            ttsManager.speakQueued(
                "Opening accessibility settings. Turn TalkBack off, then come back."
            )
        } else {
            pendingAccessibilityReturn = false
            ttsManager.speakQueued(
                "Accessibility settings are not available on this device. " +
                "Hold both volume keys for three seconds to turn TalkBack off."
            )
        }
    }

    /**
     * After returning from Accessibility Settings, re-check TalkBack and
     * confirm the outcome (or gently remind if still enabled).
     */
    private fun confirmAccessibilityReturn() {
        if (!isAdded || !pendingAccessibilityReturn) return
        pendingAccessibilityReturn = false
        mainHandler.postDelayed({
            try {
                val mainActivity = activity as? MainActivity ?: return@postDelayed
                if (!mainActivity.isTalkBackEnabled()) {
                    mainActivity.clearTalkBackDetected()
                    ttsManager.speakQueued("TalkBack is off. Enjoy hands-free use.")
                } else {
                    ttsManager.speakQueued(
                        "TalkBack is still on. Hold both volume keys for three seconds " +
                        "to turn it off, or say open accessibility settings to try again."
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "confirmAccessibilityReturn failed: ${e.message}")
            }
        }, ACCESSIBILITY_RETURN_RECHECK_DELAY_MS)
    }

    // ══════════════════════════════════════════════════════════════════
    // Hands-free Voice Audition (choose my voice by speaking)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Voice-driven voice picker. No screen, no tapping: the app cycles
     * through the installed voices for the current language, speaking a
     * sample in each one, and the user replies with voice commands.
     *
     * Say "next" → hear the next voice | "use this" → keep it | "cancel" → stop.
     */
    private fun startVoiceAudition() {
        if (!isAdded) return
        Log.i(TAG, "Voice command: starting hands-free voice audition")
        stopVoiceAuditionIfActive()
        stopVoiceListening()
        endFollowUpWindow()
        // If the "install a better voice?" question was still awaiting an
        // answer, this explicit request supersedes it (without resolving it).
        awaitingInstallVoiceAnswer = false
        coreController.resetForNewCapture()
        hapticManager.vibrateTap()

        voiceAuditionActive = true
        appState = AppState.LISTENING
        // The model-ASR rescue's "Please say that again" cue must never
        // interrupt the audition samples.
        (activity as? MainActivity)?.modelAsrRescueAllowed = false

        // A non-English language is already in effect — the user is refining
        // THAT voice, so go straight to the voice cycle. An English language is
        // ambiguous (it is usually the device default, not a deliberate choice),
        // so ASK first: the audition can only list voices of the current
        // language, and without this step a Malay speaker stuck on the English
        // voice could never reach the Malay voice hands-free.
        if (ttsManager.getCurrentLanguageKey() != TTSManager.LANGUAGE_ENGLISH) {
            auditionStartVoiceCycle()
        } else {
            auditionAskLanguage()
        }
    }

    /**
     * Ask which language the user speaks, then run the voice cycle in that
     * language. Choosing a language PERSISTS it (KEY_LANGUAGE), which also
     * drives speech-recognition language and prompt output language.
     */
    private fun auditionAskLanguage() {
        if (!isAdded) return
        Log.d(TAG, "Audition: asking for language")
        voiceAuditionLangStep = true
        appState = AppState.LISTENING
        updateStatus("Voice settings — choose language")
        val mainActivity = activity as? MainActivity ?: run {
            voiceAuditionActive = false
            return
        }
        mainActivity.speakThenCallback(
            "Voice settings. Which language should I speak? Say English, Malay, or Chinese."
        ) {
            mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
        }
    }

    /**
     * Start the classic voice cycle for the CURRENT language — entry 0 is
     * "automatic" (best available), then each installed voice in turn.
     */
    private fun auditionStartVoiceCycle() {
        if (!isAdded) return
        val mainActivity = activity as? MainActivity ?: run {
            voiceAuditionActive = false
            return
        }
        voiceAuditionLangStep = false
        voiceAuditionVoices.clear()
        voiceAuditionVoices.addAll(ttsManager.getInstalledVoicesForCurrentLanguage())
        voiceAuditionIndex = 0 // 0 = automatic (best available)
        appState = AppState.LISTENING
        updateStatus("Voice selection — say next / use this / cancel")

        val total = voiceAuditionVoices.size + 1
        ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
        mainActivity.speakThenCallback(auditionIntroText(total)) {
            mainHandler.postDelayed({ if (voiceAuditionActive) auditionSpeakCurrent() }, 300L)
        }
    }

    /** Audition intro prompt — localized so commands are given in the chosen language. */
    private fun auditionIntroText(total: Int): String = when (ttsManager.getCurrentLanguageKey()) {
        TTSManager.LANGUAGE_MALAY ->
            "Pemilihan suara. $total pilihan, termasuk automatik. Saya akan sebut contoh dalam setiap suara. " +
            "Sebut seterusnya untuk suara berikut, guna ini untuk kekalkan suara, atau batal untuk berhenti."
        TTSManager.LANGUAGE_CHINESE ->
            "选择语音。共 $total 个选项，包括自动。我将用每种声音朗读示例。说 下一个 听下一种声音，" +
            "说 用这个 保留当前声音，说 取消 停止。"
        else ->
            "Voice selection. $total choices, including automatic. I will speak a sample in each voice. " +
            "Say next for the next voice, use this to keep the voice you just heard, or cancel to stop."
    }

    /** Speak the current candidate's sample (in its own voice), then listen. */
    private fun auditionSpeakCurrent() {
        if (!isAdded || !voiceAuditionActive) return
        val mainActivity = activity as? MainActivity ?: return
        val total = voiceAuditionVoices.size + 1

        // Apply the candidate so the sample plays in THAT voice
        if (voiceAuditionIndex == 0) {
            ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
        } else {
            voiceAuditionVoices.getOrNull(voiceAuditionIndex - 1)?.name
                ?.let { ttsManager.setVoiceByName(it) }
        }

        val voiceLabel = if (voiceAuditionIndex == 0) {
            "Automatic voice, best available quality"
        } else {
            "Voice ${voiceAuditionIndex} of $total"
        }
        appState = AppState.LISTENING
        updateStatus(if (voiceAuditionIndex == 0) "Audition: Automatic" else "Audition: voice $voiceAuditionIndex of $total")
        mainActivity.speakThenCallback(
            "${auditionSampleText()} $voiceLabel. Say next, use this, or cancel."
        ) {
            mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
        }
    }

    /**
     * Parse the user's spoken answer to the language question. Choosing a
     * language whose voice pack is not installed opens the installer instead
     * of silently falling back to English (setLanguage would persist that
     * fallback as the "choice").
     */
    private fun handleVoiceAuditionLangCommand(t: String) {
        when {
            // "next" / "skip" at the language question = keep the CURRENT
            // language and go straight to its voices (skip must NOT cancel —
            // it is in AUDITION_CANCEL_PHRASES, so it is checked first).
            containsAny(t, AUDITION_NEXT_PHRASES) || containsAny(t, listOf("skip", "teruskan")) -> {
                auditionStartVoiceCycle()
            }
            containsAny(t, AUDITION_CANCEL_PHRASES) -> {
                stopVoiceListening()
                voiceAuditionActive = false
                voiceAuditionLangStep = false
                (activity as? MainActivity)?.modelAsrRescueAllowed = true
                appState = AppState.IDLE
                updateStatus("Ready")
                try {
                    ttsManager.speakImmediate(
                        ttsManager.localized(
                            "Voice selection cancelled.",
                            "Pemilihan suara dibatalkan.",
                            "已取消语音选择。"
                        )
                    )
                } catch (_: Throwable) {}
            }
            else -> {
                val key = languageKeyForSpoken(t)
                val mainActivity = activity as? MainActivity
                if (key == null || mainActivity == null) {
                    mainActivity?.speakThenCallback(
                        "I did not catch that. Say English, Malay, or Chinese, or say cancel."
                    ) {
                        mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                    }
                    return
                }

                if (!ttsManager.hasInstalledVoicesFor(key)) {
                    // No voice pack for that language — the app cannot speak it.
                    // Open the engine's voice installer instead of choosing a
                    // language the TTS engine will silently fall back from.
                    Log.i(TAG, "Audition: no installed voice for '$key' — opening voice installer")
                    stopVoiceListening()
                    voiceAuditionActive = false
                    voiceAuditionLangStep = false
                    (activity as? MainActivity)?.modelAsrRescueAllowed = true
                    appState = AppState.IDLE
                    updateStatus("Ready")
                    val langDisplay = when (key) {
                        TTSManager.LANGUAGE_MALAY -> "Malay"
                        TTSManager.LANGUAGE_CHINESE -> "Chinese"
                        else -> "English"
                    }
                    if (mainActivity.openTtsVoiceInstaller()) {
                        ttsManager.speakQueued(
                            "The $langDisplay voice is not installed yet. Opening the voice installer. " +
                            "After it finishes, say voice settings again and choose $langDisplay."
                        )
                    } else {
                        ttsManager.speakQueued(
                            "The $langDisplay voice is not installed and the installer is unavailable. " +
                            "You can add voices in Android text to speech settings."
                        )
                    }
                    return
                }

                // Persist the language (drives TTS voice + recognition + prompts),
                // auto-pick its best voice, then audition the voices of that language.
                Log.i(TAG, "Audition: language chosen = $key")
                ttsManager.setLanguage(key, requireContext())
                ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
                ttsManager.speakImmediate(auditionSampleText())
                auditionStartVoiceCycle()
            }
        }
    }

    /** Map a spoken language answer to a language key (en / ms / zh), or null. */
    private fun languageKeyForSpoken(t: String): String? {
        return when {
            containsAny(t, listOf("bahasa inggeris", "english", "inggeris", "英语", "英文", "英式")) -> TTSManager.LANGUAGE_ENGLISH
            containsAny(t, listOf("bahasa melayu", "bahasa malaysia", "bahasa", "melayu", "malay", "malaysia", "马来语")) -> TTSManager.LANGUAGE_MALAY
            containsAny(t, listOf("chinese", "mandarin", "中文", "华语", "普通话", "汉语", "国语", "中国话")) -> TTSManager.LANGUAGE_CHINESE
            else -> null
        }
    }

    /** Parse the user's spoken command during the audition. */
    private fun handleVoiceAuditionCommand(text: String) {
        if (!voiceAuditionActive) return
        val t = text.trim().lowercase()

        // Language-selection step has its own command set.
        if (voiceAuditionLangStep) {
            handleVoiceAuditionLangCommand(t)
            return
        }

        val total = voiceAuditionVoices.size + 1

        when {
            containsAny(t, AUDITION_CHANGE_LANGUAGE_PHRASES) -> {
                // "change language" returns to the language question so the
                // user can switch back (e.g. Malay → English) hands-free.
                auditionAskLanguage()
            }
            containsAny(t, AUDITION_USE_PHRASES) -> {
                // Save the currently auditioned voice (or automatic)
                stopVoiceListening()
                val name = if (voiceAuditionIndex == 0) TTSManager.VOICE_AUTO
                else voiceAuditionVoices[voiceAuditionIndex - 1].name
                requireContext().getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(TTSManager.KEY_VOICE_NAME, name).apply()
                voiceAuditionActive = false
                (activity as? MainActivity)?.modelAsrRescueAllowed = true
                appState = AppState.IDLE
                updateStatus("Voice selected")
                try {
                    ttsManager.speakImmediate(
                        if (voiceAuditionIndex == 0) {
                            "Automatic voice selected."
                        } else {
                            "Voice selected. You will hear me in this voice."
                        }
                    )
                } catch (_: Throwable) {}
            }
            containsAny(t, AUDITION_NEXT_PHRASES) -> {
                voiceAuditionIndex = ((voiceAuditionIndex + 1) % total + total) % total
                auditionSpeakCurrent()
            }
            containsAny(t, AUDITION_CANCEL_PHRASES) -> {
                stopVoiceListening()
                voiceAuditionActive = false
                (activity as? MainActivity)?.modelAsrRescueAllowed = true
                appState = AppState.IDLE
                updateStatus("Ready")
                try {
                    ttsManager.speakImmediate(
                        ttsManager.localized(
                            "Voice selection cancelled.",
                            "Pemilihan suara dibatalkan.",
                            "已取消语音选择。"
                        )
                    )
                } catch (_: Throwable) {}
            }
            else -> {
                // Unclear — re-hint inside the audition
                val mainActivity = activity as? MainActivity
                mainActivity?.speakThenCallback(
                    ttsManager.localized(
                        "I did not catch that. Say next, use this, or cancel.",
                        "Saya tidak dengar itu. Sebut seterusnya, guna ini, atau batal.",
                        "我没有听清。说“下一个”、“用这个”或“取消”。"
                    )
                ) {
                    mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                }
            }
        }
    }

    /** End the audition quietly when another action takes over. */
    private fun stopVoiceAuditionIfActive() {
        if (voiceAuditionActive) {
            voiceAuditionActive = false
            voiceAuditionLangStep = false
            stopVoiceListening()
            (activity as? MainActivity)?.modelAsrRescueAllowed = true
            if (appState == AppState.LISTENING) {
                appState = AppState.IDLE
                updateStatus("Ready")
            }
        }
    }

    /** Short localized sample used during the audition. */
    private fun auditionSampleText(): String = when (ttsManager.getCurrentLanguageKey()) {
        TTSManager.LANGUAGE_MALAY -> "Ini adalah bagaimana saya berbunyi."
        TTSManager.LANGUAGE_CHINESE -> "这是我说话的声音。"
        else -> "This is how I sound."
    }

    private fun containsAny(text: String, phrases: List<String>): Boolean =
        phrases.any { text.contains(it) }

    // ══════════════════════════════════════════════════════════════════
    // Conversation Window (hands-free follow-ups)
    // ══════════════════════════════════════════════════════════════════
    // After a double-tap "ask", the mic reopens for CONVERSATION_WINDOW_MS
    // so follow-up questions flow hands-free without repeating the gesture.
    // Single-tap and continuous-mode answers deliberately do NOT reopen the
    // mic — the user chose touch input, so the mic stays closed at IDLE.
    // Each accepted query resets the deadline; the window closes quietly
    // after the deadline passes with no speech, or when a new action
    // (tap/report) takes over.

    /** True while the hands-free follow-up window is open. */
    @Volatile
    private var inConversationWindow = false

    /** Rolling deadline (elapsedRealtime) — extended on every accepted query. */
    @Volatile
    private var conversationDeadlineMs = 0L

    /**
     * True while the answer being produced came from a VOICE session
     * (double-tap or a hands-free follow-up question). Only voice answers
     * reopen the mic on completion. Tap/continuous answers keep the mic
     * closed at IDLE — an open mic left running after a tap was what let the
     * NEXT tap cancel an active recognizer, which surfaced its cancellation
     * error as phantom "I did not catch that" speech before the real answer.
     */
    @Volatile
    private var keepMicOpenAfterAnswer = false

    /** Periodically checks the deadline and closes the window when it expires. */
    private val conversationWatchdog = object : Runnable {
        override fun run() {
            if (!inConversationWindow || !isAdded) return
            // FIX 2 — WATCHDOG EXPIRY RACE: while an answer is being produced
            // (ANALYZING) or spoken (SPEAKING), a long inference could outlive
            // the 12s deadline; closing here wiped the dialogue history
            // MID-ANSWER, so the just-finished exchange was lost to the next
            // follow-up. Defer closure instead: the deadline is pushed forward
            // and the window closes once the answer is done (or on the next
            // expiry check after it).
            if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
                conversationDeadlineMs = android.os.SystemClock.elapsedRealtime() +
                    CONVERSATION_WINDOW_MS
                mainHandler.postDelayed(this, CONVERSATION_WINDOW_TICK_MS)
                return
            }
            if (android.os.SystemClock.elapsedRealtime() >= conversationDeadlineMs) {
                Log.d(TAG, "Conversation window expired — closing mic")
                endFollowUpWindow()
            } else {
                mainHandler.postDelayed(this, CONVERSATION_WINDOW_TICK_MS)
            }
        }
    }

    /**
     * Open (or extend) the hands-free conversation window and reopen the mic.
     * @param cue true when the user explicitly asked (double-tap) — a spoken
     *            "Listening" prompt is played; follow-up reopens stay silent.
     */
    /**
     * Ask "Did you say X?" for a grey-band transcript (0.35-0.6): above the
     * chatter floor but still often a mis-hear. Confirming costs one short
     * exchange; guessing wrong costs a multi-second inference on garbage plus
     * a wrong answer the user may act on.
     */
    private fun askQueryConfirmation(text: String, locale: java.util.Locale?) {
        val mainActivity = activity as? MainActivity ?: return
        pendingConfirmationText = text
        pendingConfirmationLocale = locale
        consecutiveConfirmationAsks++
        appState = AppState.LISTENING
        val preview = text.take(60)
        Log.d(TAG, "Low-confidence transcript — asking confirmation: \"$preview\"")
        mainActivity.speakThenCallback(
            ttsManager.localized(
                "Did you say, $preview ?",
                "Adakah anda kata, $preview ?",
                "你是说，$preview 吗？"
            )
        ) {
            // Mic stays open inside the follow-up window so the yes/no lands
            // without another gesture. Expire after a short silence.
            confirmationExpiryRunnable = Runnable { cancelConfirmation() }
            mainHandler.postDelayed(confirmationExpiryRunnable!!, CONFIRMATION_WINDOW_MS)
            // CRITICAL: speakThenCallback called tts.stop() before speaking the
            // ask, which ended the active recognizer session. Without reopening
            // the mic there is NO session to catch the yes/no — dead air until
            // the window expires and the original query is lost entirely.
            mainHandler.postDelayed({ startVoiceListening() }, FOLLOW_UP_OPEN_DELAY_MS)
        }
    }

    /** Resolve the outstanding confirmation with the user's yes/no answer. */
    private fun handleConfirmationAnswer(spokenText: String) {
        val mainActivity = activity as? MainActivity ?: return
        val original = pendingConfirmationText ?: return
        val originalLocale = pendingConfirmationLocale
        val text = spokenText.trim().lowercase()
        val yes = listOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "correct",
            "ya", "betul", "好", "是", "对"
        ).any { text.contains(it) }
        val no = listOf(
            "no", "nope", "wrong", "tak", "tidak", "salah", "bukan",
            "不用", "不对", "不是"
        ).any { text.contains(it) }
        val confirmed = yes && !no
        cancelConfirmation()
        if (confirmed) {
            Log.d(TAG, "Confirmation: user confirmed \"$original\"")
            // Re-enter the normal routing with trusted confidence.
            lastConfidence = 1f
            consecutiveConfirmationAsks = 0
            mainActivity.onSpeechResult?.invoke(original, originalLocale, 1f)
        } else {
            Log.d(TAG, "Confirmation: user rejected \"$original\" — re-asking")
            mainActivity.speakThenCallback(
                ttsManager.localized(
                    "Okay, please say it again.",
                    "Baik, sila sebut lagi.",
                    "好的，请再说一遍。"
                )
            ) {
                // Reopen the mic — the retry prompt's stop() ended the session.
                mainHandler.postDelayed({ startVoiceListening() }, FOLLOW_UP_OPEN_DELAY_MS)
            }
        }
    }

    /** Clear any outstanding confirmation ask (expiry or window close). */
    private fun cancelConfirmation() {
        confirmationExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
        confirmationExpiryRunnable = null
        pendingConfirmationText = null
        pendingConfirmationLocale = null
    }

    private fun startFollowUpWindow(cue: Boolean) {
        if (!isAdded) return
        inConversationWindow = true
        conversationDeadlineMs = android.os.SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        mainHandler.removeCallbacks(conversationWatchdog)
        mainHandler.postDelayed(conversationWatchdog, CONVERSATION_WINDOW_TICK_MS)
        coreController.onConversationWindowOpened()

        stopVoiceListening() // clear any stale recognition session first
        appState = AppState.LISTENING
        updateStatus("Listening...")

        if (cue) {
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                // Fresh conversation session — restore the one-shot model-ASR
                // rescue budget and re-enable it (it is consumed when a
                // noisy-room rescue runs, and disabled during the audition).
                mainActivity.resetModelAsrBudget()
                mainActivity.modelAsrRescueAllowed = true
                // Short spoken cue, then the mic opens right after it ends so
                // the cue is never captured. Kept to one word on purpose: a
                // long cue (e.g. the old "...or say voice settings..." hint)
                // delayed the mic by ~5s, so users who spoke early lost their
                // query and heard nothing back.
                mainActivity.speakThenCallback(
                    ttsManager.localized("Analyzing.", "Menganalisis.", "分析中。")
                ) {
                    // Open the mic AFTER the cue finishes so it is never captured.
                    mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                }
                return
            }
        }
        // Follow-up reopens (and cue fallback): open the mic after a short
        // settle so the answer's last words are never captured as the query.
        mainHandler.postDelayed(
            { startVoiceListening() },
            FOLLOW_UP_OPEN_DELAY_MS
        )
    }

    /** Close the conversation window and the mic (stays closed at IDLE). */
    private fun endFollowUpWindow() {
        inConversationWindow = false
        mainHandler.removeCallbacks(conversationWatchdog)
        cancelConfirmation()
        stopVoiceListening()
        // Dialogue memory expires with the window — the next voice query
        // after idle starts a fresh conversation, not a stale follow-up.
        coreController.onConversationWindowClosed()
        if (appState == AppState.LISTENING) {
            appState = AppState.IDLE
            updateStatus("Ready")
        }
    }

    /**
     * After an answer finishes, reopen the hands-free follow-up mic ONLY for
     * voice-session answers. Tap and continuous answers return to IDLE with
     * the mic closed — the user is on touch input, and an open mic at rest is
     * what made switching between gestures noisy (stray "I did not catch that"
     * from cancelled listening sessions, ambient chat picked up after taps).
     */
    private fun maybeOpenFollowUpWindow() {
        if (!isAdded) return
        if (keepMicOpenAfterAnswer) {
            Log.d(TAG, "Voice answer done — reopening follow-up window")
            startFollowUpWindow(cue = false)
        } else {
            Log.d(TAG, "Tap answer done — staying IDLE, mic closed")
            endFollowUpWindow()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Status Bar
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatus(text: String) {
        activity?.runOnUiThread {
            if (isAdded && _fragmentCameraBinding != null) {
                fragmentCameraBinding.statusText.text = text
            }
        }
    }

    companion object {
        /**
         * Grace period (ms) after all pending utterances complete before
         * releasing audio focus. Compensates for Android AudioTrack
         * hardware drain latency after onDone() fires.
         */
        private const val AUDIO_DRAIN_GRACE_MS = 600L

        /** Spoken phrases that open Accessibility Settings (disable TalkBack). */
        private val ACCESSIBILITY_SETTINGS_PHRASES = listOf(
            "open accessibility settings", "accessibility settings", "accessibility setting",
            "talkback settings", "talkback setting", "talk back settings",
            "turn off talkback", "turn talkback off", "disable talkback",
            "stop talkback", "close talkback", "turn off talk back",
            "tetapan kebolehaksesan", "buka tetapan kebolehaksesan",
            "tetapan aksesibiliti", "tutup talkback", "matikan talkback",
            "辅助功能设置", "打开辅助功能设置", "无障碍设置", "关闭talkback",
            "关闭语音播报", "talkback设置"
        )

        /** Spoken phrases that open the voice audition ("double tap, then say…"). */
        private val VOICE_SETTINGS_PHRASES = listOf(
            "voice settings", "voice setting", "voice setup", "change your voice",
            "change my voice", "change voice", "change the voice", "set my voice",
            "set your voice", "voice selection", "choose a voice", "choose voice",
            "pick a voice", "pick my voice", "how you sound", "how i sound",
            "sound settings", "sound setting", "speech settings", "tts settings",
            "text to speech settings", "voice preferences", "tetapan suara",
            "tukar suara", "ubah suara", "语音设置", "设置语音", "更换语音",
            "换语音", "声音设置", "换声音", "选择语音"
        )

        /** Audition: move to the next voice. */
        private val AUDITION_NEXT_PHRASES = listOf(
            "next", "forward", "continue", "another", "other", "seterusnya",
            "下一个", "下一个语音", "下一种"
        )

        /** Audition: keep the voice that was just heard. */
        private val AUDITION_USE_PHRASES = listOf(
            "use this", "this one", "this voice", "keep it", "keep this",
            "select", "choose", "pick this", "yes", "ok", "guna", "pilih", "ini",
            "用这个", "就要这个", "好", "是", "要"
        )

        /** Audition: stop without changing the voice. */
        private val AUDITION_CANCEL_PHRASES = listOf(
            "cancel", "stop", "exit", "quit", "back", "skip", "done", "finish",
            "batal", "tamat", "取消", "停止", "退出", "完成"
        )

        /** Voice cycle: go back to the language question (switch language). */
        private val AUDITION_CHANGE_LANGUAGE_PHRASES = listOf(
            "change language", "switch language", "choose language",
            "language settings", "another language", "other language",
            "tukar bahasa", "ubah bahasa", "ganti bahasa", "pilih bahasa",
            "换语言", "更换语言", "改语言", "选择语言", "语言设置", "切换语言"
        )

        /** Interval (ms) between auto-snapshot captures in continuous mode. */
        private const val AUTO_SNAPSHOT_INTERVAL_MS = 4000L

        /**
         * Safety timeout (ms) — force IDLE if TTS doesn't finish in time.
         * Voice session fix: 10s truncated the state machine under long
         * read-backs (an OCR label paragraph runs well past 10s, Malay TTS
         * is slower still), so the app flipped to IDLE while the answer was
         * still speaking and follow-up window logic acted on stale state.
         * 30s still bounds a dead onDone callback but never fires under
         * legitimate long answers.
         */
        private const val SPEAKING_TIMEOUT_MS = 30_000L

        /**
         * After continuous mode runs for this long, throttle the capture interval
         * to prevent SoC thermal throttling on mid-tier chipsets.
         */
        private const val CONTINUOUS_MODE_THROTTLE_AFTER_MS = 180_000L  // 3 minutes

        /**
         * Throttled capture interval (ms) after CONTINUOUS_MODE_THROTTLE_AFTER_MS.
         * Doubles the interval to reduce sustained GPU load.
         */
        private const val THERMALTHROTTLE_INTERVAL_MS = 8000L

        /** Delay (ms) between the "Listening" cue and opening the mic. */
        private const val VOICE_SESSION_OPEN_DELAY_MS = 500L

        /** Window (ms) for the user to confirm/reject a low-confidence query. */
        private const val CONFIRMATION_WINDOW_MS = 6_000L

        /** Transcriptions at or above this confidence are trusted — no confirmation ask. */
        private const val CONFIRM_ABOVE_CONFIDENCE = 0.6f

        /**
         * Max consecutive grey-band confirmation asks before the pipeline
         * runs the query anyway — dead air is worse than a probable mis-hear.
         */
        private const val MAX_CONFIRMATION_ASKS = 1

        /** Hands-free follow-up window: mic stays open this long after each answer. */
        private const val CONVERSATION_WINDOW_MS = 12_000L

        /** How often the conversation-window watchdog checks the deadline (ms). */
        private const val CONVERSATION_WINDOW_TICK_MS = 2_000L

        /** Delay before a silent follow-up reopen listens again (ms). */
        private const val FOLLOW_UP_RETRY_DELAY_MS = 500L

        /** Delay before the mic opens after an answer ends (ms). */
        private const val FOLLOW_UP_OPEN_DELAY_MS = 450L

        /** Delay between the better-voice prompt and opening the mic (ms). */
        private const val VOICE_INSTALL_PROMPT_GAP_MS = 600L

        /** How long to wait for the user's yes/skip answer (ms). */
        private const val VOICE_INSTALL_WAIT_MS = 12_000L

        /** Delay before re-checking the voice after returning from the installer (ms). */
        private const val VOICE_INSTALL_RECHECK_DELAY_MS = 1_800L

        /** Delay before re-checking TalkBack after returning from Accessibility Settings (ms). */
        private const val ACCESSIBILITY_RETURN_RECHECK_DELAY_MS = 1_800L

        /** PHASE 6: agent-lane maintenance period (episode eviction + orphan session sweep). */
        private const val MAINTENANCE_TICK_MS = 60_000L
    }
}

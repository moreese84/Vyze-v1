package com.vyze.app.ui.fragments
import com.vyze.app.util.CrashLogFile
import com.vyze.app.core.VyzeCoreController

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.vyze.app.R
import com.vyze.app.VyzeApplication
import com.vyze.app.databinding.FragmentLoadingBinding
import com.vyze.app.speech.TTSManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loading screen — the ONLY place VLM engine initialization runs.
 *
 * ## Strict Navigation Rule
 * This fragment NEVER navigates to CameraFragment unless
 * [VyzeCoreController] signals "VLM ready" (100% success).
 * If GPU fails → tries CPU. If both fail → red error + Retry button.
 * NO navigation happens on failure.
 *
 * ## Blind-first UX
 * The whole screen is invisible to a blind user, so every meaningful
 * state has an audio counterpart:
 * - a short startup motif plays on entry,
 * - the TTS announces the start of loading,
 * - a rising chime + spoken onboarding mark readiness (CameraFragment),
 * - errors are SPOKEN (never visual-only) and the WHOLE screen becomes
 *   the retry target (tap anywhere — the app's gesture language).
 * The 60s safety timeout speaks before navigating, never dumps the user
 * into a dead camera in silence.
 */
class LoadingFragment : Fragment() {

    private val TAG = "LoadingFragment"

    private var _binding: FragmentLoadingBinding? = null
    private val binding get() = _binding!!

    private var coreController: VyzeCoreController? = null
    private var pulseAnimator: ObjectAnimator? = null

    /** Guard: prevents duplicate init or premature navigation. */
    private var initStarted = false

    /** True once an init error is showing — root taps then trigger retry. */
    @Volatile
    private var showErrorState = false

    /** Safety timeout posted to the root view; cancelled on success/error. */
    private var safetyTimeoutRunnable: Runnable? = null

    // ── Audio cues (blind-first feedback) ──────────────────────────
    private var soundPool: SoundPool? = null
    private var startupSoundId = 0
    private var readySoundId = 0
    private var errorSoundId = 0
    private var soundsLoaded = false

    // ══════════════════════════════════════════════════════════════════
    // Permission Launchers
    // ══════════════════════════════════════════════════════════════════

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            safeRun {
                if (!permissions.values.all { it }) {
                    Log.w(TAG, "Permissions denied: ${permissions.filter { !it.value }.keys}")
                }
            }
            safeRun { afterPermissions() }
        }

    private val storageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            safeRun {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    Environment.isExternalStorageManager()
                ) {
                    updateStatus(getString(R.string.loading_storage_granted), 5)
                } else {
                    updateStatus(getString(R.string.loading_storage_denied), 5)
                }
            }
            safeRun { initVlm() }
        }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        CrashLogFile.log(TAG, "onViewCreated")

        initSoundPool()

        // Show logo pulse immediately
        safeRun { startPulseAnimation() }

        // Wire retry button (visual affordance for sighted users)
        safeRun {
            binding.retryButton.setOnClickListener {
                resetErrorState()
                safeRun { startInitPipeline() }
            }
        }

        // Tap-anywhere retry once an error is showing — the whole screen
        // is the button, matching the app's tap-anywhere gesture language.
        safeRun {
            binding.root.setOnClickListener {
                if (showErrorState) {
                    CrashLogFile.log(TAG, "Tap-anywhere retry")
                    resetErrorState()
                    safeRun { startInitPipeline() }
                }
            }
        }

        // Safety timeout: if stuck for 60s, speak a warning and navigate
        // anyway (previously a SILENT dump into a dead camera).
        safeRun {
            val timeout = Runnable {
                if (isAdded && _binding != null && !initStarted) {
                    CrashLogFile.log(TAG, "Safety timeout — speaking warning, navigating to camera")
                    playCue(errorSoundId)
                    appTts()?.speakQueued(
                        appTts()?.localized(
                            getString(R.string.loading_still_working),
                            getString(R.string.loading_still_working_ms),
                            getString(R.string.loading_still_working_zh)
                        ) ?: ""
                    )
                    navigateToCamera()
                }
            }
            safetyTimeoutRunnable = timeout
            binding.root.postDelayed(timeout, 60_000L)
        }

        // Startup feedback: sound cue + spoken announcement. The TTS may
        // not be initialized yet — speak() buffers until it is.
        safeRun { playCue(startupSoundId) }
        safeRun {
            appTts()?.speakQueued(
                appTts()?.localized(
                    getString(R.string.loading_spoken_start),
                    getString(R.string.loading_spoken_start_ms),
                    getString(R.string.loading_spoken_start_zh)
                ) ?: ""
            )
        }

        // ═══ DEFER ALL INIT 500ms ═══
        // Ensures the layout is fully attached before any heavy work.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500L)
            CrashLogFile.log(TAG, "Deferred init starting")
            safeRun { startInitPipeline() }
        }
    }

    override fun onDestroyView() {
        stopPulseAnimation()
        pulseAnimator?.cancel()
        pulseAnimator = null
        safetyTimeoutRunnable?.let { binding.root.removeCallbacks(it) }
        safetyTimeoutRunnable = null
        releaseSoundPool()
        _binding = null
        super.onDestroyView()
    }

    // ══════════════════════════════════════════════════════════════════
    // Audio cues
    // ══════════════════════════════════════════════════════════════════

    private fun initSoundPool() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
            soundPool?.setOnLoadCompleteListener { _, _, status -> if (status == 0) soundsLoaded = true }
            startupSoundId = soundPool?.load(requireContext(), R.raw.startup, 1) ?: 0
            readySoundId = soundPool?.load(requireContext(), R.raw.ready, 1) ?: 0
            errorSoundId = soundPool?.load(requireContext(), R.raw.error, 1) ?: 0
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "SoundPool init failed: ${e.message}", e)
        }
    }

    private fun releaseSoundPool() {
        try {
            soundPool?.release()
        } catch (_: Throwable) {
        }
        soundPool = null
        soundsLoaded = false
    }

    private fun playCue(soundId: Int) {
        try {
            if (soundId != 0 && soundsLoaded) soundPool?.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
        } catch (_: Throwable) {
        }
    }

    private fun appTts(): TTSManager? =
        (activity?.applicationContext as? VyzeApplication)?.ttsManager

    // ══════════════════════════════════════════════════════════════════
    // Init Pipeline (called once, deferred 500ms)
    // ══════════════════════════════════════════════════════════════════

    private fun startInitPipeline() {
        if (initStarted) return
        initStarted = true
        CrashLogFile.log(TAG, "startInitPipeline")
        checkCameraPermissions()
    }

    private fun afterPermissions() {
        CrashLogFile.log(TAG, "afterPermissions (SDK=${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            updateStatus(getString(R.string.loading_requesting_storage), 3)
            Toast.makeText(
                requireContext(),
                "Grant 'All files access' to load the VLM model from Downloads",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                storageSettingsLauncher.launch(intent)
            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "Storage settings intent failed: ${e.message}", e)
                initVlm()
            }
        } else {
            updateStatus(getString(R.string.loading_storage_ok), 4)
            initVlm()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Permission Pipeline
    // ══════════════════════════════════════════════════════════════════

    private fun checkCameraPermissions() {
        CrashLogFile.log(TAG, "checkCameraPermissions")
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            updateStatus(getString(R.string.loading_permissions_ok), 2)
            afterPermissions()
        } else {
            updateStatus(getString(R.string.loading_requesting_permissions), 1)
            cameraPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Initialization — ONLY navigates on full SUCCESS
    // ══════════════════════════════════════════════════════════════════

    private fun initVlm() {
        CrashLogFile.log(TAG, "initVlm")
        try {
            val app = requireActivity().applicationContext as VyzeApplication

            // REUSE the app-scoped controller across retries (do NOT rebuild it
            // per attempt). Why: VlmEngineManager's 3-strike circuit breaker and
            // failure counter live on the manager instance — a fresh controller
            // per retry reset them to zero, so the breaker could NEVER trip and
            // every tap re-ran the identical failing GPU init, re-allocating the
            // engine's multi-GB memory each time (observed on Infinix Hot 60i as
            // an endless error/retry loop until the OS killed the process).
            // Reusing the instance keeps failure history, lets the circuit
            // breaker open after 3 strikes, and the manager's early-return paths
            // (already failed / already degraded) speak the friendly fallback
            // instead of retrying.
            coreController = app.coreController ?: VyzeCoreController(
                context = requireContext().applicationContext,
                ttsManager = app.ttsManager,
                memoryDao = app.memoryDao,
                interactionDao = app.interactionDao
            ).also { app.coreController = it }

            // Progress callback → update UI
            coreController?.onProgressUpdate = { percent, step ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.progressBar.progress = percent
                        binding.progressText.text = "$percent%"
                        binding.statusText.text = localizeStepText(step)
                    }
                }
            }

            // Status callback → ONLY navigate on "VLM ready" (SUCCESS)
            coreController?.onStatusUpdate = { msg ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.statusText.text = localizeStepText(msg)

                        if (msg.startsWith("VLM ready")) {
                            // ═══ SUCCESS: navigate to camera ═══
                            onInitSuccess()
                        } else if (msg.startsWith("Error") || msg.contains("not found") ||
                            msg.contains("crashed") || msg.contains("failed")
                        ) {
                            // ═══ FAILURE: show error, NO navigation ═══
                            showError(msg)
                        }
                    }
                }
            }

            // Error callback → show error, NO navigation
            coreController?.onError = { error ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        showError(error)
                    }
                }
            }

            updateStatus(getString(R.string.loading_waking_up), 10)
            coreController?.initialize()

        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "initVlm crashed: ${e.message}", e)
            activity?.runOnUiThread {
                if (isAdded && _binding != null) {
                    showError(getString(R.string.loading_init_failed, e.message ?: ""))
                }
            }
        }
    }

    /**
     * Map the controller's English status strings onto localized display
     * strings. The controller's English text remains the log/spoken
     * source of truth; this only localizes what the SCREEN shows
     * (sighted users / TalkBack) so EN/MS/ZH stay in the res system.
     */
    private fun localizeStepText(raw: String): String = when {
        raw.startsWith("Copying model") -> getString(R.string.loading_copying_model)
        raw.startsWith("Error") -> getString(R.string.loading_prefix_error) +
            raw.removePrefix("Error:")
        else -> raw
    }

    // ══════════════════════════════════════════════════════════════════
    // Success / Error — navigation ONLY in onInitSuccess
    // ══════════════════════════════════════════════════════════════════

    private fun onInitSuccess() {
        CrashLogFile.log(TAG, "VLM init SUCCESS — navigating")
        stopPulseAnimation()
        cancelSafetyTimeout()
        safeRun {
            binding.progressBar.progress = 100
            binding.progressText.text = "100%"
            binding.statusText.text = getString(R.string.loading_ready)
        }
        playCue(readySoundId)
        binding.root.postDelayed({ navigateToCamera() }, 500L)
    }

    private fun showError(message: String) {
        CrashLogFile.log(TAG, "ERROR: $message")
        stopPulseAnimation()
        cancelSafetyTimeout()
        showErrorState = true
        safeRun {
            binding.statusText.text = localizeStepText(message)
            binding.statusText.setTextColor(0xFFFF4444.toInt())
            binding.retryButton.visibility = View.VISIBLE
            binding.retryButton.text = getString(R.string.loading_retry)
            if (message.contains("not found")) {
                binding.hintText.visibility = View.VISIBLE
                binding.hintText.text = "adb push gemma-4-E2B-it.litertlm /sdcard/Download/"
            }
        }
        // Blind-first: errors are SPOKEN, and tap-anywhere now retries.
        safeRun {
            playCue(errorSoundId)
            appTts()?.speakQueued(
                appTts()?.localized(
                    getString(R.string.loading_spoken_error),
                    getString(R.string.loading_spoken_error_ms),
                    getString(R.string.loading_spoken_error_zh)
                ) ?: ""
            )
        }
    }

    private fun resetErrorState() {
        showErrorState = false
        safeRun {
            binding.statusText.setTextColor(0xFF666666.toInt())
            binding.statusText.text = getString(R.string.loading_retrying)
            binding.retryButton.visibility = View.GONE
            binding.hintText.visibility = View.GONE
            binding.progressBar.progress = 0
            binding.progressText.text = "0%"
            initStarted = false
            startPulseAnimation()
        }
    }

    private fun cancelSafetyTimeout() {
        safetyTimeoutRunnable?.let { binding.root.removeCallbacks(it) }
        safetyTimeoutRunnable = null
    }

    // ══════════════════════════════════════════════════════════════════
    // Navigation
    // ══════════════════════════════════════════════════════════════════

    private fun navigateToCamera() {
        if (!isAdded) return
        try {
            CrashLogFile.log(TAG, "Navigating to CameraFragment")
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_loading_to_camera)
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "Navigation failed: ${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Pulse Animation
    // ══════════════════════════════════════════════════════════════════

    private fun startPulseAnimation() {
        stopPulseAnimation()
        try {
            pulseAnimator = ObjectAnimator.ofFloat(
                binding.logoImage, "scaleX", 0.95f, 1.05f, 0.95f
            ).apply {
                duration = 2000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(
                binding.logoImage, "scaleY", 0.95f, 1.05f, 0.95f
            ).apply {
                duration = 2000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopPulseAnimation() {
        try {
            pulseAnimator?.cancel()
        } catch (_: Throwable) {
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatus(text: String, progress: Int) {
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                binding.statusText.text = text
                binding.progressBar.progress = progress
                binding.progressText.text = "$progress%"
            }
        }
    }

    private fun safeRun(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "safeRun: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
}

package com.vyze.app.ui.fragments
import com.vyze.app.VyzeApplication
import com.vyze.app.speech.TTSManager
import com.vyze.app.ui.TtsViewModel

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vyze.app.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Accessible TTS settings screen for visually impaired users.
 *
 * Provides three large, high-contrast sliders for:
 *  - **Speech Rate** (0.5x to 2.0x)
 *  - **Pitch** (0.5 to 1.5)
 *  - **Audio Volume** (0% to 100%)
 *
 * All values are persisted to [SharedPreferences] under [TTSManager.PREFS_NAME]
 * and applied to the shared [TTSManager] instance immediately.
 *
 * A "Test Voice" button speaks a sample phrase so the user can instantly
 * hear the effect of their changes.
 *
 * ## Accessibility
 * - All sliders use `android:minHeight="48dp"` for touch target compliance.
 * - Large text labels (22sp) and value readouts (36sp) for low-vision users.
 * - Content descriptions on every interactive element for TalkBack.
 * - High-contrast yellow-on-black color scheme.
 */
class TtsSettingsFragment : Fragment() {

    private val ttsViewModel: TtsViewModel by activityViewModels()
    private lateinit var ttsManager: TTSManager
    private lateinit var prefs: android.content.SharedPreferences

    // Views
    private lateinit var valueLanguage: TextView
    private lateinit var spinnerLanguage: Spinner
    private lateinit var valueSpeechRate: TextView
    private lateinit var sliderSpeechRate: SeekBar
    private lateinit var valuePitch: TextView
    private lateinit var sliderPitch: SeekBar
    private lateinit var valueVolume: TextView
    private lateinit var sliderVolume: SeekBar
    private lateinit var btnTestVoice: Button

    // Voice picker views
    private lateinit var valueVoice: TextView
    private lateinit var btnVoicePrev: Button
    private lateinit var btnVoiceUse: Button
    private lateinit var btnVoiceNext: Button

    // P2 views
    private lateinit var valueNightMode: TextView
    private lateinit var spinnerNightMode: Spinner
    private lateinit var btnErrorLog: Button

    // Debounce for slider changes — speak sample only after 500ms of inactivity
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSpeakRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tts_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Use singleton TTSManager from ViewModel
        ttsManager = ttsViewModel.ttsManager
        prefs = requireContext().getSharedPreferences(
            TTSManager.PREFS_NAME, android.content.Context.MODE_PRIVATE
        )

        // Apply saved settings immediately
        ttsManager.applySettings(requireContext())

        // Bind views
        valueLanguage = view.findViewById(R.id.value_language)
        spinnerLanguage = view.findViewById(R.id.spinner_language)
        valueSpeechRate = view.findViewById(R.id.value_speech_rate)
        sliderSpeechRate = view.findViewById(R.id.slider_speech_rate)
        valuePitch = view.findViewById(R.id.value_pitch)
        sliderPitch = view.findViewById(R.id.slider_pitch)
        valueVolume = view.findViewById(R.id.value_volume)
        sliderVolume = view.findViewById(R.id.slider_volume)
        btnTestVoice = view.findViewById(R.id.btn_test_voice)
        valueVoice = view.findViewById(R.id.value_voice)
        btnVoicePrev = view.findViewById(R.id.btn_voice_prev)
        btnVoiceUse = view.findViewById(R.id.btn_voice_use)
        btnVoiceNext = view.findViewById(R.id.btn_voice_next)

        // Load saved values and set initial positions
        loadSavedSettings()

        // Setup listeners
        setupLanguageSpinner()
        setupSpeechRateSlider()
        setupPitchSlider()
        setupVolumeSlider()
        setupTestButton()
        setupVoicePicker()

        // P2 views
        valueNightMode = view.findViewById(R.id.value_night_mode)
        spinnerNightMode = view.findViewById(R.id.spinner_night_mode)
        btnErrorLog = view.findViewById(R.id.btn_error_log)
        setupNightModeSpinner()
        setupModeButtons()
    }

    /**
     * Loads saved settings from SharedPreferences and positions sliders accordingly.
     */
    private fun loadSavedSettings() {
        // Language label only — the spinner position is set in
        // setupLanguageSpinner AFTER its adapter exists. Selecting here
        // (pre-adapter) drops the position: the spinner sits at 0
        // (English) and the initial onItemSelected then overwrites a
        // saved ms/zh choice and speaks on entry (audit P2 #45).
        val savedLang = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
            ?: TTSManager.LANGUAGE_ENGLISH
        valueLanguage.text = languageDisplayName(savedLang)

        // Rate
        val savedRate = prefs.getFloat(TTSManager.KEY_SPEECH_RATE, TTSManager.DEFAULT_SPEECH_RATE)
        sliderSpeechRate.progress = ((savedRate - RATE_MIN) / RATE_STEP).toInt()
        valueSpeechRate.text = formatRate(savedRate)

        // Pitch
        val savedPitch = prefs.getFloat(TTSManager.KEY_PITCH, TTSManager.DEFAULT_PITCH)
        sliderPitch.progress = ((savedPitch - PITCH_MIN) / PITCH_STEP).toInt()
        valuePitch.text = String.format("%.2f", savedPitch)

        // Volume
        val savedVolume = prefs.getFloat(TTSManager.KEY_VOLUME, TTSManager.DEFAULT_VOLUME)
        sliderVolume.progress = (savedVolume * 100).toInt()
        valueVolume.text = "${(savedVolume * 100).toInt()}%"
    }

    /**
     * Speech Rate slider: 0.5x to 2.0x.
     * SeekBar range 0–150, each step = 0.01.
     */
    private fun setupSpeechRateSlider() {
        sliderSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = RATE_MIN + (progress * RATE_STEP)
                valueSpeechRate.text = formatRate(rate)
                seekBar?.contentDescription = "Speech speed slider, current value ${formatRate(rate)}"
                saveAndApply(rateKey = TTSManager.KEY_SPEECH_RATE, value = rate)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Pitch slider: 0.5 to 1.5.
     * SeekBar range 0–100, each step = 0.01.
     */
    private fun setupPitchSlider() {
        sliderPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = PITCH_MIN + (progress * PITCH_STEP)
                valuePitch.text = String.format("%.2f", pitch)
                seekBar?.contentDescription = "Voice pitch slider, current value ${String.format("%.2f", pitch)}"
                saveAndApply(pitchKey = TTSManager.KEY_PITCH, value = pitch)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Volume slider: 0% to 100%.
     * SeekBar range 0–100.
     */
    private fun setupVolumeSlider() {
        sliderVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val volume = progress / 100f
                valueVolume.text = "${progress}%"
                seekBar?.contentDescription = "Audio volume slider, current value $progress percent"
                saveAndApply(volumeKey = TTSManager.KEY_VOLUME, value = volume)
                scheduleSampleSpeak()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelPendingSpeak()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speakSample()
            }
        })
    }

    /**
     * Sets up the test voice button to speak a sample phrase on demand.
     */
    private fun setupTestButton() {
        btnTestVoice.setOnClickListener {
            speakSample()
        }
    }

    // ── Persistence & Application ───────────────────────────────────────────

    /**
     * Saves a setting to SharedPreferences and applies it to TTSManager.
     * Only the changed parameter is updated.
     */
    private fun saveAndApply(
        rateKey: String? = null,
        pitchKey: String? = null,
        volumeKey: String? = null,
        value: Float = 0f
    ) {
        val editor = prefs.edit()

        if (rateKey != null) {
            editor.putFloat(rateKey, value)
            ttsManager.setSpeechRate(value)
        }
        if (pitchKey != null) {
            editor.putFloat(pitchKey, value)
            ttsManager.setPitch(value)
        }
        if (volumeKey != null) {
            editor.putFloat(volumeKey, value)
            ttsManager.setVolume(value)
        }

        editor.apply()
    }

    // ── Language Selector ───────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val languageLabels = TTSManager.SUPPORTED_LANGUAGES.map { languageDisplayName(it) }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            languageLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        // Position AFTER the adapter exists (#45): the initial
        // onItemSelected then carries the saved position and the
        // selected==current equality check below no-ops it — no reset,
        // no speech on entry.
        val savedLang = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)
            ?: TTSManager.LANGUAGE_ENGLISH
        spinnerLanguage.setSelection(
            TTSManager.SUPPORTED_LANGUAGES.indexOf(savedLang).coerceAtLeast(0)
        )

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = TTSManager.SUPPORTED_LANGUAGES[position]
                val currentKey = prefs.getString(TTSManager.KEY_LANGUAGE, TTSManager.LANGUAGE_ENGLISH)

                if (selectedKey != currentKey) {
                    ttsManager.setLanguage(selectedKey, requireContext())
                    valueLanguage.text = languageDisplayName(selectedKey)
                    // Voices differ per language — rebuild the picker list
                    refreshVoiceChoices()
                    speakSample()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { /* no op */ }
        }
    }

    private fun languageDisplayName(key: String): String {
        return when (key) {
            TTSManager.LANGUAGE_MALAY  -> requireContext().getString(R.string.tts_lang_malay)
            TTSManager.LANGUAGE_CHINESE -> requireContext().getString(R.string.tts_lang_chinese)
            else           -> requireContext().getString(R.string.tts_lang_english)
        }
    }

    // ── Sample Speaking ─────────────────────────────────────────────────────

    /**
     * Speaks a sample phrase so the user can hear the current TTS settings.
     */
    private fun speakSample() {
        ttsManager.speakImmediate(samplePhraseForLanguage())
    }

    private fun samplePhraseForLanguage(): String = when (ttsManager.getCurrentLanguageKey()) {
        TTSManager.LANGUAGE_MALAY  -> SAMPLE_PHRASE_MS
        TTSManager.LANGUAGE_CHINESE -> SAMPLE_PHRASE_ZH
        else           -> SAMPLE_PHRASE_EN
    }

    // ── Voice Picker (Tier 1) ──────────────────────────────────────────

    /** Installed voices for the current language. Index 0 = automatic. */
    private val voiceChoices = ArrayList<android.speech.tts.Voice>()
    private var voiceIndex = 0

    private fun setupVoicePicker() {
        btnVoicePrev.setOnClickListener { stepVoice(-1) }
        btnVoiceNext.setOnClickListener { stepVoice(+1) }
        btnVoiceUse.setOnClickListener { useCurrentVoice() }
        refreshVoiceChoices()
    }

    /**
     * Rebuild the voice list for the current language, restoring the
     * persisted pick when it belongs to this language (the manager
     * already applied it at engine init — this only aligns the label
     * and the Use button). A new language's list never contains the
     * old pick, so switching falls back to automatic. (#45 family.)
     */
    private fun refreshVoiceChoices() {
        voiceChoices.clear()
        voiceChoices.addAll(ttsManager.getInstalledVoicesForCurrentLanguage())
        val savedVoice = prefs.getString(TTSManager.KEY_VOICE_NAME, TTSManager.VOICE_AUTO)
        val savedIndex = voiceChoices.indexOfFirst { it.name == savedVoice }
        voiceIndex = if (savedIndex >= 0) savedIndex + 1 else 0 // 0 = automatic
        updateVoiceLabel()
    }

    private fun updateVoiceLabel() {
        valueVoice.text = if (voiceIndex == 0) {
            requireContext().getString(R.string.tts_voice_automatic)
        } else {
            voiceChoices[voiceIndex - 1].name
        }
    }

    /** Move to the next/previous voice and audition it (sample in that voice). */
    private fun stepVoice(delta: Int) {
        val total = voiceChoices.size + 1 // +1 for the automatic entry
        voiceIndex = ((voiceIndex + delta) % total + total) % total

        if (voiceIndex == 0) {
            ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
        } else {
            ttsManager.setVoiceByName(voiceChoices[voiceIndex - 1].name)
        }
        updateVoiceLabel()

        // Audition: the sample speaks in the candidate voice itself
        val phrase = samplePhraseForLanguage()
        val announcement = if (voiceIndex == 0) {
            "$phrase Automatic voice, best available quality."
        } else {
            "$phrase Voice ${voiceIndex} of ${voiceChoices.size}."
        }
        ttsManager.speakImmediate(announcement)
    }

    /** Persist the currently auditioned voice (automatic or a specific one). */
    private fun useCurrentVoice() {
        val name = if (voiceIndex == 0) TTSManager.VOICE_AUTO else voiceChoices[voiceIndex - 1].name
        prefs.edit().putString(TTSManager.KEY_VOICE_NAME, name).apply()
        updateVoiceLabel()
        ttsManager.speakImmediate(
            if (voiceIndex == 0) "Automatic voice selected." else "Voice selected."
        )
    }

    /**
     * Schedules a delayed sample speak after 500ms of slider inactivity.
     * This provides continuous feedback while dragging without spamming TTS.
     */
    private fun scheduleSampleSpeak() {
        cancelPendingSpeak()
        pendingSpeakRunnable = Runnable { speakSample() }
        handler.postDelayed(pendingSpeakRunnable!!, SAMPLE_DELAY_MS)
    }

    /**
     * Cancels any pending delayed sample speak.
     */
    private fun cancelPendingSpeak() {
        pendingSpeakRunnable?.let { handler.removeCallbacks(it) }
        pendingSpeakRunnable = null
    }

    // ── Night Mode ─────────────────────────────────────────────────────

    private fun setupNightModeSpinner() {
        val modeLabels = listOf(
            requireContext().getString(R.string.night_mode_light),
            requireContext().getString(R.string.night_mode_dark),
            requireContext().getString(R.string.night_mode_system)
        )
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            modeLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNightMode.adapter = adapter

        // Set current selection to system default
        spinnerNightMode.setSelection(2, false)
        valueNightMode.text = requireContext().getString(R.string.night_mode_system)

        spinnerNightMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
                val modeName = when (position) {
                    0 -> requireContext().getString(R.string.night_mode_light)
                    1 -> requireContext().getString(R.string.night_mode_dark)
                    else -> requireContext().getString(R.string.night_mode_system)
                }
                valueNightMode.text = modeName
                ttsManager.speakImmediate(modeName)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────
    // (Reading/medicine/tutorial placebo buttons removed 2026-11-09,
    // audit #46: they spoke "activated" and set no flag — no such modes
    // exist. If real modes are ever designed, add them with flags here.)

    private fun setupModeButtons() {
        btnErrorLog.setOnClickListener {
            val app = requireContext().applicationContext as com.vyze.app.VyzeApplication
            kotlinx.coroutines.MainScope().launch {
                val errors = app.errorLogRepository.getRecentErrors(10)
                if (errors.isEmpty()) {
                    ttsManager.speakImmediate(requireContext().getString(R.string.error_log_empty))
                } else {
                    ttsManager.speakImmediate(
                        requireContext().getString(R.string.error_log_count, errors.size) +
                            ". Latest: ${errors.first().message.take(100)}"
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        cancelPendingSpeak()
        // Do NOT call ttsManager.onDestroy() — it's owned by TtsViewModel
        super.onDestroyView()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun formatRate(rate: Float): String {
        return String.format("%.1fx", rate)
    }

    companion object {
        private const val TAG = "TtsSettingsFragment"

        /** Sample phrase spoken on slider change and test button. */
        const val SAMPLE_PHRASE_EN = "This is how I will sound with your chosen settings."
        const val SAMPLE_PHRASE_MS = "Ini adalah bagaimana saya akan berbunyi dengan tetapan pilihan anda."
        const val SAMPLE_PHRASE_ZH = "这是我使用您选择的设置时的声音。"

        /** Delay before speaking sample after slider stops moving (ms). */
        const val SAMPLE_DELAY_MS = 500L

        // ── Slider Range Constants ──────────────────────────────────────────

        /** Minimum speech rate (0.5x). */
        const val RATE_MIN = 0.5f
        /** Maximum speech rate (2.0x). */
        const val RATE_MAX = 2.0f
        /** Step size for speech rate (each SeekBar step = 0.01x). */
        const val RATE_STEP = 0.01f

        /** Minimum pitch (0.5). */
        const val PITCH_MIN = 0.5f
        /** Maximum pitch (1.5). */
        const val PITCH_MAX = 1.5f
        /** Step size for pitch (each SeekBar step = 0.01). */
        const val PITCH_STEP = 0.01f
    }
}

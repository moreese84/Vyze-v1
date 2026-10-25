package com.vyze.app.performance

/**
 * E0 PER-FRAME BUDGET PROBE (plan §E0, MNN steal-list) — the instrument
 * that unblocks the E2 (walking lane) vs E3 (tiny-model lane) fork.
 *
 * WHAT IT MEASURES: on the CONTINUOUS auto-capture lane, the wall-clock
 * from **frame capture start (T0)** to **first TTS audio actually
 * starting** — the ANSWER's first audio, never the dead-air filler. The
 * middle segments (preprocess / jpeg / payload / PREFILL / DECODE) are
 * already logged per run by `VlmEngineManager`'s `[PIPELINE-BREAKDOWN]`
 * and `[ANALYZE-BREAKDOWN]` lines; this probe supplies the two endpoints
 * that were missing and pairs them, so one debug session reads:
 *
 * ```
 * FRAME-BUDGET open t0=… (thermal=…)      ← CameraFragment onCaptureStart
 * [PIPELINE-BREAKDOWN] … PREFILL=… DECODE=… ← the engine run in between
 * FRAME-BUDGET close frameToAudio=…ms …    ← TTSManager.onStart (ANSWER's first audio)
 * ```
 *
 * VERDICT (decision rule, plan §E0): mean frame→first-audio
 *  - **≤ 2.5 s** → [Verdict.E2_VIABLE] — the walking lane is viable with
 *    the current brain as-is (ambient cadence, not demo-realtime).
 *  - **≥ 5 s**  → [Verdict.E3_PREREQUISITE] — the tiny-model lane becomes
 *    a prerequisite, not an option.
 *  - between    → [Verdict.AMBIGUOUS] — spend the E1 prompt-weight lever
 *    (already landed: narration budget) and re-measure before either.
 *  - < [MIN_SAMPLES_FOR_VERDICT] answered frames → [Verdict.INSUFFICIENT_DATA].
 * When reading a verdict, cite the brain from `[ANALYZE-BREAKDOWN]`
 * (production = MiniCPM-V-4.7-1B) — the §E0 thresholds were written in
 * the Gemma-E2B era but are wall-clock, so they still apply; the brain
 * name travels with the number.
 *
 * WHAT IT DELIBERATELY DOES NOT MIX:
 *  - **Scene-gate skips** (cosine ≥ 0.9 → no inference) close as
 *    [Outcome.SCENE_SKIPPED] and are counted, never averaged — folding
 *    skipped ticks into the latency mean would fake a fast verdict.
 *  - **Abandoned frames** (captured but no audio ever arrived — error,
 *    recycle, cancel) close as [Outcome.ABANDONED] and are counted, never
 *    averaged — silently dropping them would fake a fast verdict the
 *    other way. Both counts sit next to the latency stats in [Summary].
 *  - Non-continuous speech (tap/voice/tutorial TTS) with **no open frame**
 *    is ignored ([onFirstAudio] returns null) — only the continuous lane
 *    opens a window, so other lanes can never pollute these numbers.
 *
 * PAIRING INVARIANT (why one open frame is enough): the auto-snapshot
 * loop only ticks when `!isCurrentlyInferring() && !hasPendingSpeech()`,
 * so frame N+1's capture cannot start before frame N's audio finished —
 * any audio arriving while a frame is open belongs to that frame. The
 * known exception (documented, accepted): a barge-in voice answer
 * speaking while a continuous frame is open would pair to that frame —
 * the metric stays honest as "first audio after capture".
 *
 * PURE: no Android, no I/O, no content — timings, counts and a thermal
 * tier label only (logcat is not a private channel). Thread-safe
 * (@Synchronized): called from the capture path, the controller's skip
 * callback, and the TTS progress listener.
 *
 * Provenance of the thresholds: plan §E0 (2026-09-26). One open-frame
 * window at a time; ring-bounded like [com.vyze.app.agent.ShadowEvalRecorder].
 */
class FrameBudgetProbe(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    /** How a captured frame ended. Only [ANSWERED] frames carry latency. */
    enum class Outcome {
        /** Capture → answer's first TTS audio observed (the E0 latency sample). */
        ANSWERED,
        /** Scene-unchanged gate skipped inference — counted, not averaged. */
        SCENE_SKIPPED,
        /** Captured but no audio ever arrived (superseded by the next frame). */
        ABANDONED,
    }

    /** §E0 decision-rule outcome. */
    enum class Verdict {
        INSUFFICIENT_DATA,
        E2_VIABLE,
        AMBIGUOUS,
        E3_PREREQUISITE,
    }

    /** One closed frame window. Content-free: timings + thermal label only. */
    data class FrameSample(
        /** 1-based window counter within this probe instance. */
        val index: Long,
        /** Capture start (T0). */
        val t0Ms: Long,
        /** First-audio timestamp; null unless [outcome] is [Outcome.ANSWERED]. */
        val firstAudioAtMs: Long?,
        /** firstAudio − T0; null unless [outcome] is [Outcome.ANSWERED]. */
        val frameToAudioMs: Long?,
        /** Thermal tier label at capture time (latency is thermally coupled). */
        val thermalTier: String,
        val outcome: Outcome,
    )

    /** Aggregated window for logging / the flip-style readout. */
    data class Summary(
        val answered: Int,
        val sceneSkipped: Int,
        val abandoned: Int,
        /** Mean frame→first-audio over [answered]; 0 when none. */
        val avgMs: Long,
        /** Nearest-rank median over [answered]; 0 when none. */
        val p50Ms: Long,
        /** Nearest-rank 95th percentile over [answered] — thermal-decay read. */
        val p95Ms: Long,
        val maxMs: Long,
        val verdict: Verdict,
        /** Non-null while a frame is awaiting audio (stuck-window debug). */
        val openFrameT0Ms: Long?,
    )

    private var openT0Ms: Long? = null
    private var openThermalTier: String = ""
    private var frameCounter: Long = 0
    private val samples = ArrayDeque<FrameSample>()

    /**
     * Open the window at capture start. Returns the T0 it stamped so the
     * caller can log it content-free. Any previous frame still awaiting
     * audio is superseded and closed as [Outcome.ABANDONED] — the loop's
     * guards mean this only happens on an error path, and the honest
     * count beats a silently vanished sample.
     *
     * @param thermalTier thermal tier label at capture (e.g. "GREEN").
     */
    @Synchronized
    fun beginFrame(thermalTier: String): Long {
        closeOpen(Outcome.ABANDONED)
        val t0 = nowMs()
        frameCounter++
        openT0Ms = t0
        openThermalTier = thermalTier
        return t0
    }

    /**
     * The scene-unchanged gate refused inference for the open frame:
     * close it as [Outcome.SCENE_SKIPPED]. Returns the closed sample, or
     * null when no frame was open (defensive — cannot occur in the
     * production order: capture → gate).
     */
    @Synchronized
    fun onSceneSkip(): FrameSample? = closeOpen(Outcome.SCENE_SKIPPED)

    /**
     * Audio just started (non-filler — TTSManager never reports the capture-time dead-air "Let me look.", which would close every window at ~90 ms and fake an E2 verdict). If a continuous frame is open this closes it as
     * [Outcome.ANSWERED] and returns the sample (the caller logs
     * frame→audio); with no open frame this is another lane's speech —
     * returns null and records nothing.
     */
    @Synchronized
    fun onFirstAudio(): FrameSample? =
        if (openT0Ms == null) null else closeOpen(Outcome.ANSWERED)

    /** Closed-sample ring (oldest first). Debug/test reads. */
    @Synchronized
    fun snapshot(): List<FrameSample> = samples.toList()

    /**
     * Aggregate over the ring + the §E0 verdict. Latency stats span
     * [Outcome.ANSWERED] only; skip/abandon counts sit beside them so a
     * suspiciously fast average is visibly suspicious.
     */
    @Synchronized
    fun summary(): Summary {
        val answered = samples.filter { it.outcome == Outcome.ANSWERED }
        val skipped = samples.count { it.outcome == Outcome.SCENE_SKIPPED }
        val abandoned = samples.count { it.outcome == Outcome.ABANDONED }
        val latencies = answered.mapNotNull { it.frameToAudioMs }.sorted()

        val avg = if (latencies.isEmpty()) 0L else latencies.average().toLong()
        return Summary(
            answered = answered.size,
            sceneSkipped = skipped,
            abandoned = abandoned,
            avgMs = avg,
            p50Ms = percentile(latencies, 0.50),
            p95Ms = percentile(latencies, 0.95),
            maxMs = latencies.lastOrNull() ?: 0L,
            verdict = verdictFor(latencies),
            openFrameT0Ms = openT0Ms,
        )
    }

    /** Close the open window; records + returns the sample, or null if none. */
    private fun closeOpen(outcome: Outcome): FrameSample? {
        val t0 = openT0Ms ?: return null
        val at = nowMs()
        openT0Ms = null
        val answered = outcome == Outcome.ANSWERED
        val sample = FrameSample(
            index = frameCounter,
            t0Ms = t0,
            firstAudioAtMs = if (answered) at else null,
            frameToAudioMs = if (answered) at - t0 else null,
            thermalTier = openThermalTier,
            outcome = outcome,
        )
        samples.addLast(sample)
        while (samples.size > capacity) samples.removeFirst()
        return sample
    }

    /** §E0 decision rule over sorted ANSWERED latencies. */
    private fun verdictFor(sorted: List<Long>): Verdict {
        if (sorted.size < MIN_SAMPLES_FOR_VERDICT) return Verdict.INSUFFICIENT_DATA
        val avg = sorted.average()
        return when {
            avg <= E2_VIABLE_MAX_AVG_MS -> Verdict.E2_VIABLE
            avg >= E3_PREREQUISITE_MIN_AVG_MS -> Verdict.E3_PREREQUISITE
            else -> Verdict.AMBIGUOUS
        }
    }

    /** Nearest-rank percentile (ceil(p·n)-th value) over a sorted list. */
    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    companion object {
        /**
         * Production wiring singleton — [com.vyze.app.ui.fragments.CameraFragment]
         * opens windows, [com.vyze.app.speech.TTSManager] closes them.
         * Tests construct fresh instances with an injected clock instead.
         */
        val shared = FrameBudgetProbe()

        /** Ring bound, mirroring ShadowEvalRecorder (a debug-session window). */
        const val DEFAULT_CAPACITY = 200

        /** §E0: mean ≤ 2.5 s per frame → the walking lane is viable as-is. */
        const val E2_VIABLE_MAX_AVG_MS = 2_500L

        /** §E0: mean ≥ 5 s per frame → the tiny-model lane is prerequisite. */
        const val E3_PREREQUISITE_MIN_AVG_MS = 5_000L

        /** Fewer answered frames than this → INSUFFICIENT_DATA, no verdict. */
        const val MIN_SAMPLES_FOR_VERDICT = 5
    }
}

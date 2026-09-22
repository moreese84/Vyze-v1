package com.vyze.app.agent

import android.util.Log
import com.vyze.app.core.VyzeCoreController

/**
 * VYZE MIGRATION PLAN v3 — COMPATIBILITY FACADE.
 *
 * CameraFragment's PHASE 3–6 call sites (DO-NOT-TOUCH file) reference
 * `VyzeAgentRuntime.*` and [VyzeShadowRouter] directly. With the sub-agent
 * tree gone, this facade keeps those call sites compiling and behaving
 * EXACTLY as before — but every EXECUTION now funnels through
 * [AdkAgentManager.execute] against the single [VyzeMasterAgent] instead
 * of the old router→sub-agent ladder.
 *
 * SHADOW FALLBACK CONTRACT (preserved): [tryLiveRouteTextOnly] returns
 * null on ANY master-agent decline — flag dark, engine busy/not ready,
 * thermal refusal, timeout, exception, or empty answer. The caller
 * (fragment) falls back to the native legacy dispatch
 * ([com.vyze.app.core.VyzeCoreController.triggerTextQuery]) on null.
 *
 * Hardware invariants: no frame capture here; ThermalPowerController is
 * consulted read-only through the caller's gates; the native engine's
 * generationMutex/session discipline stay internal to VlmEngineManager.
 */
object VyzeAgentRuntime {

    private const val TAG = "VyzeAgentRuntime"

    /**
     * Shadow→live flag. Ships false; when dark, every route declines and
     * the caller runs the exact legacy dispatch (zero behavior change).
     */
    @Volatile
    var shadowEnabled: Boolean = false

    /**
     * VLM pre-gate flag ([PreGatePolicy]). Ships FALSE — when dark, every
     * transcript takes the exact legacy dispatch (zero behavior change).
     * Like [shadowEnabled], the runtime never enables itself. Default-dark
     * on every app start holds for RELEASE by construction (no toggle
     * component exists); DEBUG builds additionally persist the last
     * toggled state (PreGateStickyProvider) so the OEM memory manager
     * killing the app between test cycles doesn't re-darken it mid-test.
     * When enabled, only the three high-confidence IGNORE families
     * (app-cue echo / greeting garble / filler-only) bypass the VLM; all
     * positive intents and the catch-all pass through untouched.
     */
    @Volatile
    var preGateEnabled: Boolean = false

    /** Session episode store (shared with the maintenance tick). */
    val episodes = SessionEpisodeManager()

    /** Bounded shadow→live evaluation evidence for the flip review. */
    private val evalRecorder = ShadowEvalRecorder()

    /** True while a master-agent execution is in flight (busy/decline gate). */
    val isLiveGenerationActive: Boolean
        get() = AdkAgentManager.isExecuting

    /**
     * The production controller bound by [bindProduction] — the app's
     * authoritative owner of the real VlmEngineManager. App-lifetime
     * singleton, so a strong reference is safe.
     */
    @Volatile
    private var productionController: VyzeCoreController? = null

    /**
     * PRODUCTION BINDING (single-master migration, wiring directive):
     * binds the master-agent lane to the REAL engine and native pipelines
     * through [VyzeCoreController] — the app's authoritative owner of the
     * [com.vyze.app.core.VlmEngineManager]. No mocks, no stubs:
     *
     *  - analyzeText → [VyzeCoreController.analyzeTextDirect] →
     *    VlmEngineManager.analyzeText (local LiteRT-LM, gemma-4-E2B-it),
     *    with the controller's isInferring CAS + watchdog + cancel
     *    discipline as the decline path.
     *  - analyzeImage → [VyzeCoreController.analyzeImageDirect] →
     *    VlmEngineManager.analyzeImage (GPU vision encoder path).
     *  - ocrFrame   → the controller's real ML Kit OcrHelper pipeline.
     *  - speakImmediate → TTSManager.speakImmediate (existing bypass lane).
     *  - hapticPattern   → HapticManager pattern library.
     *
     * The frame provider stays NULL-BINDING by design: the camera pipeline
     * is demand-driven (CameraSetupDelegate.takeSnapshot decodes the next
     * frame exclusively for a waiter), so the agent lane can never pull a
     * frame synchronously. A visual query without a caller-attached frame
     * FAILS SAFE ("No camera frame available right now") — a sightless
     * answer to a sight question is never allowed. Frame acquisition stays
     * exclusively inside the camera layer's isCapturing-gated paths; the
     * `isCapturing` CAS gate is not read, written, or bypassed here.
     *
     * Called from [VyzeCoreController.initialize] the moment the real
     * engine reports ready; safe to call repeatedly (rebinding is cheap).
     */
    fun bindProduction(controller: VyzeCoreController) {
        productionController = controller
        AdkAgentManager.initialize(
            bridgeProvider = {
                val ctrl = productionController
                if (ctrl != null) {
                    VyzeToolWiring.engineBridge(
                        analyzeText = { prompt, sessionId ->
                            ctrl.analyzeTextDirect(prompt, sessionId)
                        },
                        analyzeImage = { frame, prompt, sessionId ->
                            ctrl.analyzeImageDirect(frame, prompt, sessionId)
                        },
                    )
                } else {
                    error("bindProduction called with no controller")
                }
            },
            frameProvider = { null },
            ocrFrame = { frame -> controller.ocrFrameDirect(frame) },
            speakImmediate = { text -> controller.speakImmediateDirect(text) },
            hapticPattern = { pattern -> controller.hapticPatternDirect(pattern) },
            // Force rebuild if a weaker (test-path) runner already exists,
            // so the PRODUCTION native tool bindings always win.
            forceRebuild = true,
        )
        productionBound = true
        Log.i(TAG, "Master-agent lane bound to PRODUCTION engine + native pipelines")
 }

    /** True when [bindProduction] has wired the real engine ops. */
    @Volatile
    var productionBound: Boolean = false
        private set

    /**
     * Attempt a LIVE agent-path route for a TEXT-ONLY query through the
     * single [VyzeMasterAgent]. All hardware/thermal/native-engine state
     * arrives as INJECTED gates — this runtime never touches isCapturing,
     * never overrides ThermalPowerController, and never imports
     * VlmEngineManager.
     *
     * @param textOnlyQuery the user's text-only question.
     * @param snapshotProvider reads engine/inferring/capture state for the
     *   decision + logs (read-only).
     * @param vlmInferenceAllowed read-only consultation of the current
     *   ThermalPowerController policy.
     * @param analyzeText the engine op bound to
     *   VyzeCoreController.analyzeTextDirect by the caller — its internal
     *   isInferring CAS + watchdog + cancelInference discipline is the
     *   decline path. When production binding exists, the bound op wins
     *   (test/preview callers may still inject a fake op).
     * @return the structured answer string, or null when the route was
     *   declined (see class doc). Declining is ALWAYS safe: the caller
     *   falls back to the legacy dispatch.
     */
    suspend fun tryLiveRouteTextOnly(
        textOnlyQuery: String,
        snapshotProvider: () -> RouterSnapshot,
        vlmInferenceAllowed: () -> Boolean,
        analyzeText: suspend (prompt: String, sessionId: String) -> String?,
        queryContext: VyzeQueryContext = VyzeQueryContext(),
    ): String? {
        val snapshot = snapshotProvider()
        val signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = textOnlyQuery)
        val decision = VyzeShadowRouter.decide(signal, snapshot)
        VyzeShadowRouter.logDecision(signal, decision, snapshot)

        // Live grant: general-knowledge voice queries ONLY. Reading intents
        // (VLM_TEXT_READ) and scene descriptions require the camera frame,
        // so they always decline to the camera layer's isCapturing-gated
        // paths — a sightless answer to a sight question is never allowed.
        if (!shadowEnabled ||
            decision.action != RouterDecision.Action.VLM_VOICE_QUERY ||
            !snapshot.engineReady ||
            !vlmInferenceAllowed()
        ) {
            // The decline tally is the eval baseline — a decline is NOT a
            // legacy mismatch; it just means the agent lane passed.
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = null,
                    outcome = ShadowEvalRecord.Outcome.DECLINED,
                )
            )
            return null // decline → caller falls back to the legacy dispatch
        }

        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()

        // DIRECT master-agent invocation (plan Phase 3) — no router hop.
        // PRODUCTION: use the REAL engine ops from the controller bound by
        // [bindProduction] (VlmEngineManager + native pipelines, no stubs).
        // Tests/preview callers without production binding may inject a
        // fake analyzeText op; the image op then fails safe to null.
        val boundController = productionController
        val bridge = if (boundController != null) {
            VyzeToolWiring.engineBridge(
                analyzeText = { prompt, sessionId ->
                    boundController.analyzeTextDirect(prompt, sessionId)
                },
                analyzeImage = { frame, prompt, sessionId ->
                    boundController.analyzeImageDirect(frame, prompt, sessionId)
                },
            )
        } else {
            VyzeToolWiring.engineBridge(
                analyzeText = analyzeText,
                analyzeImage = { _, _, _ ->
                    // No production binding (JVM tests only): the image op
                    // cannot reach the engine, so fail safe to null.
                    null
                },
            )
        }
        AdkAgentManager.initializeIfAbsent(
            bridgeProvider = { bridge },
            frameProvider = { null },
            ocrFrame = { null },
            speakImmediate = { false },
            hapticPattern = {},
        )
        val answer = try {
            val result = AdkAgentManager.executeWithBridge(
                bridge = bridge,
                query = textOnlyQuery,
                queryContext = queryContext,
            )
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = if (result != null) "adk_master_direct" else null,
                    outcome = if (result != null) ShadowEvalRecord.Outcome.ANSWERED
                    else ShadowEvalRecord.Outcome.FAILED,
                    latencyMs = if (result != null) startedAt.elapsedNow().inWholeMilliseconds else null,
                )
            )
            result
        } catch (t: Throwable) {
            Log.w(TAG, "Master-agent execution crashed: ${t.message}")
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = null,
                    outcome = ShadowEvalRecord.Outcome.FAILED,
                )
            )
            null
        }
        return answer
    }

    // ── Eval evidence + manual flip review (unchanged semantics) ──────

    /** Immutable summary of the bounded eval window (pure data). */
    data class EvalSummary(
        val total: Int,
        val answered: Int,
        val failed: Int,
        val declined: Int,
        val declinedBusy: Int,
        val avgLatencyMs: Long,
        val successRate: Double,
    )

    /** Current bounded eval evidence (a snapshot; cheap to read). */
    fun evalSummary(): EvalSummary {
        val s = evalRecorder.summarize()
        return EvalSummary(
            total = s.total,
            answered = s.answered,
            failed = s.failed,
            declined = s.declined,
            declinedBusy = s.declinedBusy,
            avgLatencyMs = s.avgLatencyMs,
            successRate = s.successRate,
        )
    }

    /**
     * Whether the bounded eval window meets the promotion bar for a HUMAN
     * to flip [shadowEnabled]. The runtime NEVER flips the flag itself and
     * NEVER disables the legacy path — this predicate only informs review.
     */
    fun shouldPromoteToLive(): Boolean {
        val s = evalRecorder.summarize()
        if (s.total < ShadowEvalRecorder.PROMOTION_MIN_ATTEMPTS) return false
        if (s.answered < ShadowEvalRecorder.PROMOTION_MIN_ANSWERED) return false
        if (s.answered.toDouble() / s.total < ShadowEvalRecorder.PROMOTION_ANSWER_RATE) return false
        if (s.declined + s.declinedBusy > s.total / 2) return false
        return true
    }

    /** Clears the eval window (after a flip review is recorded elsewhere). */
    @Synchronized
    fun resetEvalForTests() {
        evalRecorder.clear()
    }

    /**
     * PERIODIC MAINTENANCE (call from a lifecycle-aware scope, e.g. every
     * 60 s while the fragment is started): episode eviction with a
     * thermal-shrunk window. The master lane deletes its ADK session in a
     * `finally` on every turn, so no orphan sweep is needed anymore — the
     * InMemory session store stays bounded by construction.
     *
     * No VLM/TTS/hardware interaction; safe to call at any time. Returns
     * the number of idle episodes evicted (0 when none).
     */
    suspend fun maintenanceTick(
        isThermallyConstrained: () -> Boolean = { false },
    ): Int {
        val evicted = episodes.evictIdle(isThermallyConstrained())
        if (evicted.isNotEmpty()) {
            Log.d(TAG, "Maintenance: evicted ${evicted.size} idle episode(s)")
        }
        return evicted.size
    }

    // ── Test isolation hooks (same surface as the previous runtime) ──

    /** Forces runner reconstruction with the NEXT bound op — test isolation only. */
    @Synchronized
    fun resetLiveRunnerForTests() {
        AdkAgentManager.resetForTests()
    }

    /** Full release of the lane state — test isolation only. */
    @Synchronized
    fun releaseForTests() {
        AdkAgentManager.resetForTests()
        evalRecorder.clear()
        shadowEnabled = false
        preGateEnabled = false
    }
}

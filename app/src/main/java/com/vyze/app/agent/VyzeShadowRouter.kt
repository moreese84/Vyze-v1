package com.vyze.app.agent

import android.util.Log
import com.vyze.app.agent.student.StudentRouter
import java.util.concurrent.atomic.AtomicLong

/**
 * VYZE MIGRATION PLAN v3 — PURE DECISION TAXONOMY (sub-agents removed).
 *
 * This file now holds ONLY the pure, unit-testable decision machinery:
 * the legacy-mirroring route taxonomy ([RouterSignal] / [RouterDecision] /
 * [VyzeShadowRouter.decide]), the session-episode store, and the bounded
 * shadow→live evaluation evidence. The ADK-native sub-agents that used to
 * live here ([VyzeRouterAgent], [VyzeLiveQueryAgent]) are DELETED per the
 * single-master plan: every query entry point now goes directly through
 * [VyzeMasterAgent] via [AdkAgentManager.execute], and execution-side
 * state (runner, busy gate, timeout, fallback) lives in [AdkAgentManager].
 *
 * Hardware invariants (binding constraints, unchanged):
 *  - `isCapturing` (CameraFragment AtomicBoolean CAS gate) is NEVER read,
 *    written, or bypassed here — frame acquisition stays exclusively in
 *    the camera layer.
 *  - [com.vyze.app.core.ThermalPowerController] remains the sole thermal
 *    authority: callers only *ask* it for the current thermal status and
 *    never override its decisions.
 */

// ── Pure decision taxonomy ───────────────────────────────────────────
// Mirrors the legacy gesture map (CameraFragment v2) + barge-in speech
// queries. Ordered: first match wins, so more specific intents must
// precede generic ones.

/** An incoming request signal (from a gesture or the ASR pipeline). */
data class RouterSignal(
    val kind: Kind,
    val spokenText: String = "",
    val tapX: Int = -1,
    val tapY: Int = -1,
) {
    enum class Kind { SINGLE_TAP, DOUBLE_TAP, LONG_PRESS, SPEECH }
}

/** The routing decision — what the legacy pipeline would do. */
data class RouterDecision(
    val action: Action,
    val reason: String,
    /** True when this must go to the heavyweight VLM engine path. */
    val requiresVlm: Boolean,
    /** True when the answer should include the current camera frame. */
    val includeCameraFrame: Boolean,
    /**
     * Pre-gate family for IGNORE decisions (#75) — typed, so rewording
     * [reason] can never silently drop a family back to PASS_THROUGH.
     */
    val ignoreFamily: IgnoreFamily = IgnoreFamily.NONE,
) {
    enum class Action {
        VLM_SCENE_DESCRIBE,   // single tap → "look"
        VLM_TEXT_READ,        // speech: read/OCR keywords
        VLM_VOICE_QUERY,      // speech: general question
        FAST_COLOR_ANALYSIS,  // triple tap (reserved; not a signal source yet)
        LIGHT_CHECK,          // long press
        SOS,                  // triple-tap-hold (reserved)
        IGNORE,               // empty/nonsensical input
    }

    /** IGNORE taxonomy shared by the student router and the pre-gate. */
    enum class IgnoreFamily {
        /** Not an IGNORE, or an unclassified one — passes through. */
        NONE,
        EMPTY_TRANSCRIPT,
        APP_CUE_ECHO,
        APP_CUE_HYBRID,
        GREETING_GARBLE,
        FILLER_ONLY,
        OUT_OF_DOMAIN,
    }
}

/** Pure decision input snapshot — everything needed, nothing more. */
data class RouterSnapshot(
    val engineReady: Boolean,
    val isInferring: Boolean,
    val captureAvailable: Boolean,
)

object VyzeShadowRouter {

    private const val TAG = "VyzeShadowRouter"

    // Reading-intent keywords, aligned with the legacy voice-query path
    // (OCR reading included automatically via reading keywords).
    // Document terms (letter, surat, 信, 信件) keep the shadow taxonomy in
    // parity with the native TEXT_KEYWORDS list — a "read this letter" ask
    // is a reading intent, not a generic visual query.
    // Audit P2 #22: latin keywords are WORD-BOUNDARY matched — bare
    // contains("read") stole bread/thread/already into the OCR-still
    // lane. CJK stays substring (no word boundaries there). "reading"
    // keeps its legacy routing explicitly.
    private val READ_KEYWORDS_LATIN =
        listOf("read", "reading", "text", "label", "sign", "baca", "teks",
            "letter", "surat", "document")
    private val READ_KEYWORDS_CJK = listOf("字", "读", "念", "信", "信件")
    private val READ_LATIN_REGEX =
        Regex("\\b(${READ_KEYWORDS_LATIN.joinToString("|")})\\b")

    /** Monotonic count of logged decisions (observability hook). */
    val decisionCount = AtomicLong(0)

    /**
     * The routing decision for [signal] under [snapshot]. PURE — no I/O,
     * no engine calls, no state mutation; fully unit-testable.
     */
    fun decide(signal: RouterSignal, snapshot: RouterSnapshot): RouterDecision {
        // SOS is never shadow-routed or intercepted — legacy path is exact.
        // (SOS fires from the gesture layer before any router involvement.)

        return when (signal.kind) {
            RouterSignal.Kind.SINGLE_TAP ->
                RouterDecision(
                    action = RouterDecision.Action.VLM_SCENE_DESCRIBE,
                    reason = "single-tap look (legacy: bargeInAndCapture scene prompt)",
                    requiresVlm = true,
                    includeCameraFrame = true,
                )

            RouterSignal.Kind.LONG_PRESS ->
                RouterDecision(
                    action = RouterDecision.Action.LIGHT_CHECK,
                    reason = "long-press ambient light check (legacy: performLightCheck)",
                    requiresVlm = false,
                    includeCameraFrame = false,
                )

            RouterSignal.Kind.DOUBLE_TAP ->
                RouterDecision(
                    action = RouterDecision.Action.IGNORE,
                    reason = "double-tap opens the on-demand voice session in the UI layer; router defers",
                    requiresVlm = false,
                    includeCameraFrame = false,
                )

            RouterSignal.Kind.SPEECH -> decideSpeech(signal, snapshot)
        }
    }

    private fun decideSpeech(signal: RouterSignal, snapshot: RouterSnapshot): RouterDecision {
        val text = signal.spokenText.trim()
        if (text.isEmpty()) {
            return RouterDecision(
                action = RouterDecision.Action.IGNORE,
                reason = "empty transcript",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        val lower = text.lowercase()
        val isReadIntent = READ_LATIN_REGEX.containsMatchIn(lower) ||
            READ_KEYWORDS_CJK.any { lower.contains(it) }

        val decision: RouterDecision = if (isReadIntent) {
            RouterDecision(
                action = RouterDecision.Action.VLM_TEXT_READ,
                reason = "reading keyword (legacy: OCR pipeline still-capture path)",
                requiresVlm = true,
                includeCameraFrame = true,
            )
        } else {
            // STUDENT ROUTER (Phase 3 distillation of the Phase 0 Jev
            // harness labels): the legacy read check has already run, so
            // the student only classifies the previously-unclassifiable
            // middle (scene openers, color/light asks, out-of-domain).
            // Its catch-all is VLM_VOICE_QUERY — the exact legacy
            // fallback — so unseen queries keep legacy behavior, and the
            // agent-lane grant gate (VLM_VOICE_QUERY only) still declines
            // every non-catch-all decision to the native dispatch.
            StudentRouter.decide(text).let { d ->
                if (d.action == RouterDecision.Action.VLM_VOICE_QUERY) d.copy(
                    reason = "general voice query (legacy: openVoiceQuery session)",
                ) else d
            }
        }
        return decision.let { d ->
            if (!snapshot.engineReady) d.copy(
                reason = d.reason + " | engine NOT ready → legacy error-announce path",
            ) else d
        }
    }

    /**
     * Log one decision. Kept as the single funnel so routing stays
     * observable in logcat while execution happens in [AdkAgentManager].
     */
    fun logDecision(signal: RouterSignal, decision: RouterDecision, snapshot: RouterSnapshot) {
        decisionCount.incrementAndGet()
        Log.i(
            TAG,
            "ROUTE: signal=${signal.kind} text=\"${signal.spokenText.take(40)}\" " +
                "→ ${decision.action} (vlm=${decision.requiresVlm}, " +
                "frame=${decision.includeCameraFrame}) | engineReady=${snapshot.engineReady} " +
                "inferring=${snapshot.isInferring} | ${decision.reason}"
        )
    }
}

// NOTE (P4 #105, 2026-11-09): SessionEpisodeManager lived here —
// production never opened an episode (the master lane deletes its ADK
// session per turn, so orphans are impossible by construction) and the
// thermalStatusProvider was never read. Deleted with its tick.

// ── Shadow→live evaluation evidence ──────────────────────────────────

/** One bounded eval observation (pure data; no Android deps). */
data class ShadowEvalRecord(
    val query: String,
    /** The adk_master_ session id when the query EXECUTED; null on declines. */
    val sessionId: String?,
    val outcome: Outcome,
    val latencyMs: Long? = null,
    val recordedAtMs: Long = System.currentTimeMillis(),
) {
    enum class Outcome {
        /** Executed through the master agent; a non-empty answer returned. */
        ANSWERED,
        /** Executed but the engine returned null/empty or threw. */
        FAILED,
        /** A grant gate refused (flag/action/readiness/thermal). */
        DECLINED,
        /** Refused because another agent-path generation was in flight. */
        DECLINED_BUSY,
    }
}

/**
 * Bounded ring of eval observations + pure summary math. NOT a general
 * telemetry sink: its only consumer is the flip review
 * ([ShadowEvalRecorder] promotion bar) and JVM tests.
 */
class ShadowEvalRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Summary(
        val total: Int,
        val answered: Int,
        val failed: Int,
        val declined: Int,
        val declinedBusy: Int,
        val avgLatencyMs: Long,
        val successRate: Double,
    )

    private val ring = ArrayDeque<ShadowEvalRecord>(capacity)

    @Synchronized
    fun record(record: ShadowEvalRecord) {
        if (ring.size >= capacity) ring.removeFirst()
        ring.addLast(record)
    }

    @Synchronized
    fun snapshot(): List<ShadowEvalRecord> = ring.toList()

    @Synchronized
    fun clear() = ring.clear()

    @Synchronized
    fun summarize(): Summary {
        if (ring.isEmpty()) {
            return Summary(0, 0, 0, 0, 0, 0L, 0.0)
        }
        var answered = 0
        var failed = 0
        var declined = 0
        var declinedBusy = 0
        var latencySum = 0L
        var latencyCount = 0
        for (r in ring) {
            when (r.outcome) {
                ShadowEvalRecord.Outcome.ANSWERED -> {
                    answered++
                    r.latencyMs?.let { latencySum += it; latencyCount++ }
                }
                ShadowEvalRecord.Outcome.FAILED -> failed++
                ShadowEvalRecord.Outcome.DECLINED -> declined++
                ShadowEvalRecord.Outcome.DECLINED_BUSY -> declinedBusy++
            }
        }
        val total = ring.size
        return Summary(
            total = total,
            answered = answered,
            failed = failed,
            declined = declined,
            declinedBusy = declinedBusy,
            avgLatencyMs = if (latencyCount == 0) 0L else latencySum / latencyCount,
            successRate = answered.toDouble() / total,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 200

        /** Promotion bar: at least this many EXECUTED-or-refused observations. */
        const val PROMOTION_MIN_ATTEMPTS = 30

        /** Promotion bar: at least this many executed answers. */
        const val PROMOTION_MIN_ANSWERED = 20

        /** Promotion bar: answered/total at or above this ratio. */
        const val PROMOTION_ANSWER_RATE = 0.80
    }
}

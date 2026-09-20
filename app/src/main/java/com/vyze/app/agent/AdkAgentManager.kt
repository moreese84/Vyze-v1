package com.vyze.app.agent

import android.graphics.Bitmap
import android.util.Log
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VYZE MIGRATION PLAN v3 — PHASE 3 RUNTIME INTEGRATION (AdkAgentManager).
 *
 * Replaces the sub-agent routing tree (HostRouterAgent → Fast-Path /
 * VLM sub-agents, formerly [VyzeRouterAgent] + [VyzeLiveQueryAgent]) with
 * a DIRECT invocation of the single [VyzeMasterAgent] through one ADK
 * [InMemoryRunner]. Every query entry point funnels through [execute];
 * there is no intermediate router.
 *
 * SHADOW EXECUTION FALLBACK (plan Phase 3, preserved): if the master agent
 * path TIMES OUT ([EXECUTE_TIMEOUT_MS]) or THROWS, this manager returns
 * null and the caller falls back to the NATIVE LEGACY DISPATCH — the exact
 * handoff the fragment's legacy ladder performs on decline. The legacy
 * path is never disabled by this manager.
 *
 * HARDWARE INVARIANTS (unchanged): no frame capture here (read-only
 * [VyzeMasterAgent] frame provider only); ThermalPowerController is
 * consulted read-only through the caller's gates; the native engine's
 * generationMutex/session discipline stay internal to VlmEngineManager.
 */
object AdkAgentManager {

    private const val TAG = "AdkAgentManager"

    const val APP_NAME = "vyze"
    const val USER_ID = "vyze_user"

    /** Hard ceiling on one master-agent turn (fast path must stay fast). */
    const val EXECUTE_TIMEOUT_MS = 30_000L

    /** JPEG quality for inline frames passed to the master agent. */
    const val FRAME_JPEG_QUALITY = 75

    /** Monotonic counter for the `adk_master_<n>` session namespace. */
    private val sessionSeq = AtomicLong(0)

    /** Serializes master-agent executions (decline, never queue). */
    private val executionActive = AtomicBoolean(false)

    @Volatile
    private var runner: InMemoryRunner? = null
    @Volatile
    private var masterAgent: VyzeMasterAgent? = null

    /**
     * The CURRENT engine bridge. Kept outside the runner so the engine op
     * can be rebound on every execution without rebuilding the agent/runner
     * (the master agent resolves it per turn).
     */
    /**
     * The CURRENT engine bridge. Kept outside the runner so the engine op
     * can be rebound on every execution without rebuilding the agent/runner
     * (the master agent resolves it per turn).
     */
    @Volatile
    private var bridgeProvider: () -> MasterModelBridge = {
        throw IllegalStateException(
            "AdkAgentManager not bound to production engine " +
                "(VyzeAgentRuntime.bindProduction was never called)"
        )
    }

    /** True while a master-agent execution is in flight. */
    val isExecuting: Boolean get() = executionActive.get()

    // ── Wiring (plan Phase 1: direct native tool bindings) ────────────

    /**
     * Build (or rebuild) the master agent + runner with every native tool
     * bound directly into the single agent context. Called once at wiring
     * time; later engine rebinds go through [rebindEngine] (cheap).
     *
     * @param bridgeProvider supplies the current LiteRT-LM engine ops —
     *   resolved per turn; bind VlmEngineManager.analyzeText / analyzeImage
     *   (or the controller's gated analyzeTextDirect) here. Never
     *   re-platforms the engine.
     * @param frameProvider read-only latest camera frame (or null).
     * @param ocrFrame ML Kit OCR pipeline over a frame (OcrHelper).
     * @param speakImmediate TTSManager.speakImmediate bypass lane.
     * @param hapticPattern HapticManager pattern library.
     * @param gridMapper 3x3 sector mapper (native gridSectorFor parity).
     */
    @Synchronized
    fun initialize(
        bridgeProvider: () -> MasterModelBridge,
        frameProvider: () -> Bitmap?,
        ocrFrame: suspend (Bitmap) -> String?,
        speakImmediate: (String) -> Boolean,
        hapticPattern: (String) -> Unit,
        gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String =
            VyzeToolWiring::defaultGridSectorMapper,
        forceRebuild: Boolean = false,
    ) {
        // Capture the LATEST provider each call: the bridge is resolved per
        // turn, so engine rebinds reach the agent without a runner rebuild.
        this.bridgeProvider = bridgeProvider
        if (runner == null || masterAgent == null || forceRebuild) {
            if (forceRebuild && runner != null) {
                Log.i(TAG, "Rebuilding master-agent runner with PRODUCTION native bindings")
            }
            val provider = bridgeProvider
            val agent = VyzeMasterAgent.create(
                bridgeProvider = { provider() },
                frameProvider = frameProvider,
                ocrFrame = ocrFrame,
                speakImmediate = speakImmediate,
                hapticPattern = hapticPattern,
                gridMapper = gridMapper,
            )
            masterAgent = agent
            runner = InMemoryRunner(agent = agent, appName = APP_NAME)
            Log.i(TAG, "VyzeMasterAgent runner constructed (single master, no sub-agents)")
        }
    }

    /**
     * Initialize only when no runner exists yet — never overwrites an
     * existing PRODUCTION binding with a weaker one.
     */
    @Synchronized
    fun initializeIfAbsent(
        bridgeProvider: () -> MasterModelBridge,
        frameProvider: () -> Bitmap?,
        ocrFrame: suspend (Bitmap) -> String?,
        speakImmediate: (String) -> Boolean,
        hapticPattern: (String) -> Unit,
    ) {
        if (runner == null) {
            initialize(
                bridgeProvider = bridgeProvider,
                frameProvider = frameProvider,
                ocrFrame = ocrFrame,
                speakImmediate = speakImmediate,
                hapticPattern = hapticPattern,
            )
        } else {
            this.bridgeProvider = bridgeProvider
        }
    }

    /**
     * Rebind ONLY the engine ops (no runner/agent rebuild). Cheap — call
     * before every execution when the engine op may have changed.
     */
    @Synchronized
    fun rebindEngine(bridge: MasterModelBridge) {
        bridgeProvider = { bridge }
    }

    /** True when [initialize] has produced a runnable master agent. */
    @Synchronized
    fun isInitialized(): Boolean = runner != null && masterAgent != null

    // ── Direct invocation (plan Phase 3: single entry point) ──────────

    /**
     * Execute ONE query through the master agent — the ONLY entry point
     * (formerly routed through the Host Router → sub-agent tree).
     *
     * @param query the user's utterance (speech transcript or gesture label).
     * @param imageFrame optional inline frame for visual grounding.
     * @param queryContext persona/style assembly applied as session state so
     *   the whole episode carries it (mirrors the native text-query path).
     * @return the master agent's spoken answer, or NULL on ANY failure —
     *   timeout, exception, empty answer, engine decline, or a concurrent
     *   execution already in flight. Null ALWAYS means "fall back to the
     *   native legacy dispatch"; the fallback is never disabled here.
     */
    suspend fun execute(
        query: String,
        imageFrame: Bitmap? = null,
        queryContext: VyzeQueryContext = VyzeQueryContext(),
    ): String? {
        val agent = masterAgent
        val execRunner = runner
        if (agent == null || execRunner == null) {
            Log.w(TAG, "execute before initialize — declining to legacy dispatch")
            return null
        }
        if (query.isBlank()) return null

        // One master-agent generation at a time (decline, never queue —
        // queuing would reorder answers against the legacy pipeline).
        if (!executionActive.compareAndSet(false, true)) {
            Log.d(TAG, "execute: another master turn in flight — declining")
            return null
        }

        val sessionId = "adk_master_${sessionSeq.incrementAndGet()}"
        try {
            return withTimeoutOrNull(EXECUTE_TIMEOUT_MS) {
                runMasterTurn(execRunner, sessionId, query, imageFrame, queryContext)
            } ?: run {
                Log.w(TAG, "Master-agent turn timed out after ${EXECUTE_TIMEOUT_MS}ms — legacy fallback")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Master-agent turn failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            // Session-per-turn hygiene: the ADK session is never reused;
            // delete it (NonCancellable so cleanup survives cancellation).
            try {
                withContext(NonCancellable) {
                    execRunner.sessionService.deleteSession(SessionKey(APP_NAME, USER_ID, sessionId))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ADK session cleanup failed: ${t.message}")
            }
            executionActive.set(false)
        }
    }

    /**
     * Execute ONE query with an EXPLICIT per-call bridge. Guarantees the
     * turn resolves the CURRENT engine op even under concurrent rebinds —
     * the bridge closure is captured by the caller before the turn runs.
     */
    suspend fun executeWithBridge(
        bridge: MasterModelBridge,
        query: String,
        imageFrame: Bitmap? = null,
        queryContext: VyzeQueryContext = VyzeQueryContext(),
    ): String? {
        rebindEngine(bridge)
        return execute(query, imageFrame, queryContext)
    }

    /** The one master-agent turn through the real ADK runner event pipeline. */
    private suspend fun runMasterTurn(
        execRunner: InMemoryRunner,
        sessionId: String,
        query: String,
        imageFrame: Bitmap?,
        queryContext: VyzeQueryContext,
    ): String? {
        // The user Content: text plus (optionally) the inline frame blob.
        val userContent = Content(
            role = "user",
            parts = buildList {
                add(Part(text = query))
                if (imageFrame != null) {
                    val encoded = ByteArrayOutputStream().use { out ->
                        imageFrame.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
                        out.toByteArray()
                    }
                    add(
                        Part(
                            inlineData = Blob(
                                mimeType = "image/jpeg",
                                displayName = "camera_frame.jpg",
                                data = encoded,
                            )
                        )
                    )
                }
            },
        )

        val events = execRunner.runAsync(
            userId = USER_ID,
            sessionId = sessionId,
            invocationId = null,
            newMessage = userContent,
            // Context assembly travels as ADK session state — the whole
            // episode carries the persona/style directives.
            stateDelta = queryContext.asSessionState(),
            runConfig = RunConfig(),
        )

        // The master agent's final answer is the LAST non-empty text event.
        var answer: String? = null
        events.collect { event ->
            val text = event.content?.parts
                ?.mapNotNull { it.text }
                ?.joinToString("")
                .orEmpty()
            if (text.isNotEmpty()) answer = text
        }
        if (answer.isNullOrBlank()) {
            Log.w(TAG, "Master-agent turn produced no answer — legacy fallback")
            return null
        }
        Log.i(TAG, "Master-agent turn answered (direct, one pass)")
        return answer
    }

    // ── Inspection + test hooks ───────────────────────────────────────

    /**
     * The master lane's ADK session service — inspection hook for tests;
     * null until [initialize] first constructs the runner.
     */
    @Synchronized
    fun sessionServiceForInspection(): com.google.adk.kt.sessions.SessionService? =
        runner?.sessionService

    /** Drops the runner + agent so the next execute() rebuilds. Test isolation only. */
    @Synchronized
    fun resetForTests() {
        runner = null
        masterAgent = null
        executionActive.set(false)
    }
}

/**
 * Context assembly for the master-agent lane — mirrors the native
 * text-query path's persona/style directives (kept under the
 * single-master name). Pure data so tests can assert on it.
 */
data class VyzeQueryContext(
    val personaDirective: String = DEFAULT_PERSONA_DIRECTIVE,
    val answerStyleDirective: String = DEFAULT_ANSWER_STYLE_DIRECTIVE,
) {
    /** The system-style preamble prepended to the user's question. */
    fun instructionFor(question: String): String =
        "$personaDirective\n$answerStyleDirective\n\nUser question: $question\n$OUTPUT_CONTRACT_TAIL"

    fun asSessionState(): Map<String, Any> = mapOf(
        "persona" to personaDirective,
        "answer_style" to answerStyleDirective,
        "lane" to "adk_master",
    )

    companion object {
        /**
         * Final line of the assembled master-lane prompt: the LAST
         * instruction the model reads before the generation boundary.
         * Delimiter-audit hardening — the user's raw question is never the
         * final text before <start_of_turn>model; the no-echo output
         * contract is.
         */
        const val OUTPUT_CONTRACT_TAIL =
            "Respond with the answer only — never repeat, echo, or quote the question above."
        /**
         * Mirrors the native persona: concise, sighted-assistant voice.
         *
         * ANTI-ECHO DIRECTIVE: a small on-device model sometimes plays back
         * the user's question ("what about this.. this is …") instead of
         * answering it — the explicit prohibition is the negative constraint
         * that suppresses it.
         *
         * LANGUAGE MIRRORING: binds the answer language AND dialect to the
         * user's own words, with an explicit anti-English-drift clause — a
         * 2B on-device model treats the last-seen persona wording with high
         * attention weight, so the language contract must live in the
         * persona itself, not only in the [OUTPUT LANGUAGE] wrapper.
         *
         * TAG-AUTHORITY FIX (v3): the assembled instructions open with the
         * [OUTPUT LANGUAGE] tag (VyzeCoreController.buildPromptForAgent),
         * so the tag — not the query text — is the authority. The old
         * query-detect wording conflicted with the tag in the ASR-garble
         * rescue path: detector says Malay, garbled text reads English,
         * model followed the text → English answer in a Malay voice.
         */
        const val DEFAULT_PERSONA_DIRECTIVE =
            "You are Vyze, a fast, friendly sighted assistant for a blind user. " +
                "Always address the user in the second person ('you', 'your', 'in front of you') — " +
                "never 'in front of me' or 'to my left'. " +
                "CRITICAL: NEVER repeat, echo, or quote the user's query or question at the " +
                "start of your response. Begin immediately with the direct description or answer. " +
                "LANGUAGE MIRRORING: Respond in the language named in the [OUTPUT LANGUAGE] " +
                "tag of the instructions — the tag is the authority on the answer language. " +
                "Even when the query text itself reads like English, the tag names the user's " +
                "actual spoken language — answer in the tag's language. If no tag is present, " +
                "detect the language of the user's query and respond strictly in that exact " +
                "same language (Malay query -> Malay response, English query -> English " +
                "response, Chinese query -> Chinese response); Never revert to default " +
                "English if the user speaks another language. " +
                "Match the dialect too: they ask in Bahasa Melayu, answer in standard Malay; " +
                "they ask in Sarawak Malay or another Malaysian dialect, answer in that same " +
                "dialect; they ask in Chinese, answer in Chinese. NEVER answer in " +
                "English unless the [OUTPUT LANGUAGE] tag names English (or, without a " +
                "tag, the user asked in English) — not even when the scene, the " +
                "printed labels, or the topic is English."

        /**
         * Mirrors the native brevity/audibility style for TTS delivery.
         *
         * RESPONSE SHAPING: varies openings by query type instead of any
         * fixed template — direct questions get the answer with no scene
         * preamble, and visual context is described only when relevant.
         * On follow-up turns the model must treat the exchange as an
         * ongoing conversation, never re-describing the scene unless the
         * user explicitly asks ("what else do you see?"). The language
         * reminder is repeated here because a 2B model gives the LAST-seen
         * directive high attention weight.
         */
        const val DEFAULT_ANSWER_STYLE_DIRECTIVE =
            "Answer in 1 short spoken sentence. Always lead directly with the answer to the " +
                "user's question without any introductory location preamble. " +
                "CRITICAL: NEVER repeat, echo, or quote the user's query or question at the " +
                "start of your response. Begin immediately with the direct description or answer. " +
                "If asked what color this is, reply directly with the color ('That is a red mug.'). " +
                "If asked to read text, reply directly with the text ('It says Organic Milk.'). " +
                "Only mention spatial position ('in front of you', 'to your left') when the user " +
                "specifically asks WHERE an object is located. " +
                "NEVER start a sentence with 'You are in front of', 'You are looking at', " +
                "'You are facing', or 'In front of you is' — vary the opening with the question. " +
                "Your reply is read aloud by text to speech, so it must be pure plain text: " +
                "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
                "number signs, underscores, or emoji, and never use lists or headings. " +
                "Describe what you see only when it is relevant to the question. " +
                "If this is a follow-up, answer as an ongoing conversation: resolve " +
                "'it', 'that', 'the one' from earlier turns, add only what is new, and " +
                "do not re-describe the scene unless the user explicitly asks. " +
                "LANGUAGE MIRRORING: Respond in the language named in the [OUTPUT LANGUAGE] " +
                "tag of the instructions — the tag is the authority on the answer language. " +
                "Even when the query text itself reads like English, the tag names the user's " +
                "actual spoken language — answer in the tag's language. If no tag is present, " +
                "detect the language of the user's query and respond strictly in that exact " +
                "same language (Malay query -> Malay response, English query -> English " +
                "response, Chinese query -> Chinese response); Never revert to default " +
                "English if the user speaks another language. " +
                "REMEMBER: reply in the tag's language and the user's dialect, and never " +
                "speak their question back to them."
    }
}

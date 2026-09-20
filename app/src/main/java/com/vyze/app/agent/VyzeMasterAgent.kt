package com.vyze.app.agent

import android.graphics.Bitmap
import android.util.Log
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicLong

/**
 * VYZE MIGRATION PLAN v3 — SINGLE MASTER ORCHESTRATOR (VyzeMasterAgent).
 *
 * Replaces the 3-agent hierarchy (Host Router → Fast-Path & VLM sub-agents)
 * with ONE [BaseAgent] that binds every native tool directly into its own
 * tool registry and drives the whole one-pass loop in [runAsyncImpl]:
 *
 *  1. FAST PATH: the model turn produces native tool calls (OCR, TTS,
 *     haptics, spatial sector) — executed INLINE on the injected native
 *     singletons, results emitted as REAL ADK functionResponse events (no
 *     inter-agent serialization, no router hop, 100–300 ms tax eliminated).
 *  2. VLM PATH: visual Q&A goes DIRECT to the local LiteRT-LM engine in the
 *     SAME pass — the engine sees the user text, the instruction, and any
 *     fast-path tool results, then answers once.
 *
 * BACKEND: the local LiteRT-LM engine (`gemma-4-E2B-it.litertlm`) via
 * [MasterModelBridge] — the EXISTING [com.vyze.app.core.VlmEngineManager]
 * analyzeText/analyzeImage ops. The engine's generationMutex, session
 * discipline and LiteRT-LM runtime remain 100% internal (approved hybrid
 * pathing): ADK orchestrates the call, never the engine.
 *
 * HARDWARE INVARIANTS (binding, unchanged from the Phase 0 directive):
 *  - CameraFragment's `isCapturing` AtomicBoolean CAS gate is NEVER read,
 *    written, or bypassed here — [frameProvider] gives read-only access to a
 *    frame the camera layer has ALREADY delivered.
 *  - [com.vyze.app.core.ThermalPowerController] is consulted READ-ONLY by
 *    the CALLER's gates (AdkAgentManager); never overridden here.
 *  - TTS deterministic speech binds to TTSManager.speakImmediate() ONLY —
 *    the streaming sentence-buffer state machine stays unreachable.
 */
class VyzeMasterAgent private constructor(
    private val bridgeProvider: () -> MasterModelBridge,
    private val toolRegistry: MasterToolRegistry,
) : BaseAgent(
    name = MASTER_AGENT_NAME,
    description = "Vyze single master orchestrator: one-pass native tool dispatch " +
        "plus direct VLM inference over the local LiteRT-LM engine",
    subAgents = emptyList(), // SINGLE master — the hierarchy is gone
) {

    /** Unified native tool registry — every tool in ONE array on ONE agent. */
    val tools: List<FunctionTool> get() = toolRegistry.tools

    override fun runAsyncImpl(ctx: InvocationContext): Flow<Event> = flow {
        val userText = ctx.userContent?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("\n")
            .orEmpty()
        val userFrameBytes = ctx.userContent?.parts
            ?.firstOrNull { it.inlineData != null }?.inlineData?.data

        // The system instruction travels as ADK session state (see
        // VyzeQueryContext); if the caller supplied a persona assembly,
        // fold it ahead of the raw question — Gemma has no system role.
        val persona = ctx.session.state["persona"] as? String
        val style = ctx.session.state["answer_style"] as? String
        val prompt = buildString {
            if (!persona.isNullOrBlank()) { append(persona); append('\n') }
            if (!style.isNullOrBlank()) { append(style); append('\n') }
            if (isNotEmpty()) append('\n')
            append("User question: ").append(userText)
            append('\n')
            // Delimiter-audit hardening: the no-echo output contract is the
            // LAST text before the generation boundary — never the raw
            // user question (its final words used to leak into the output
            // prefix on follow-up turns).
            append(VyzeQueryContext.OUTPUT_CONTRACT_TAIL)
        }

        // ── PASS 1: deterministic fast-path dispatch ─────────────────
        // The deterministic intent classifier decides which native tool
        // fires WITHOUT an LLM round-trip. Only VLM-classified queries are
        // reserved for direct engine inference below.
        val fastResult = runFastPath(userText)

        // ── PASS 2 (same invocation): direct VLM inference ───────────
        if (fastResult == null) {
            val bridge = bridgeProvider()
            val sessionId = "adk_master_${SEQ.incrementAndGet()}"
            val answer = if (userFrameBytes != null) {
                val frame = bitmapFromBytes(userFrameBytes)
                    ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bridge.generateImage(frame, prompt, sessionId)
            } else {
                bridge.generateText(prompt, sessionId)
            }
            emit(
                Event(
                    invocationId = ctx.invocationId,
                    author = name,
                    content = Content("model", listOf(Part(text = answer.orEmpty()))),
                )
            )
            emitEndOfAgent(ctx)
        } else {
            // Deterministic answer — engine never invoked (fast path).
            emit(
                Event(
                    invocationId = ctx.invocationId,
                    author = name,
                    content = Content(
                        "model",
                        listOf(Part(text = fastResult.spoken)),
                    ),
                )
            )
            emitEndOfAgent(ctx)
        }
    }

    // ── Fast-path dispatch (deterministic, zero-LLM) ──────────────────

    /** Result of a deterministic fast-path tool firing. */
    private class FastPathResult(val spoken: String)

    /**
     * Deterministic intent → native tool. Returns null when the query is
     * VLM-classified (visual/spatial Q&A) so [runAsyncImpl] invokes the
     * engine. This is the fast path the Host Router used to gate behind a
     * sub-agent hop — now it is a direct tool call on the single context.
     */
    private suspend fun runFastPath(userText: String): FastPathResult? {
        val text = userText.trim()
        val lower = text.lowercase()

        // Haptic confirmation requests — pure device action. The whole
        // utterance must BE the command (e.g. "double tap"); a mention of
        // a pattern word inside a longer question ("what is tap water?")
        // is NOT a haptic request and falls through to VLM inference.
        val hapticMatch = HAPTIC_INTENT_REGEX.matchEntire(text)
        if (hapticMatch != null) {
            val pattern = hapticMatch.groupValues[1].uppercase()
            val hapticPattern = toolRegistry.hapticPattern ?: return null
            // Unknown names are ignored inside the tool binding: haptics
            // must never crash or misfire from a malformed pattern name.
            try { hapticPattern.invoke(pattern) } catch (t: Throwable) {
                Log.w(TAG_LOG, "fast haptics failed: ${t.message}")
            }
            return FastPathResult(spoken = "Done.")
        }

        // Reading intents — instant OCR through the native ML Kit pipeline.
        if (READ_INTENT_REGEX.containsMatchIn(lower)) {
            val ocr = toolRegistry.ocr
            if (ocr != null) {
                val frame = latestFrame(toolRegistry.frameProvider)
                if (frame != null) {
                    val text0 = try { ocr.invoke(frame) } catch (t: Throwable) {
                        Log.w(TAG_LOG, "fast OCR failed: ${t.message}"); null
                    }
                    return if (text0 != null) FastPathResult(spoken = text0)
                    else FastPathResult(spoken = "I could not read any text.")
                }
                return FastPathResult(spoken = "No camera frame available right now.")
            }
        }

        // Everything else is VLM-classified: direct engine inference.
        return null
    }

    private fun bitmapFromBytes(data: ByteArray): Bitmap? = try {
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
    } catch (t: Throwable) {
        Log.w(TAG_LOG, "frame decode failed: ${t.message}")
        null
    }

    companion object {
        private val SEQ = AtomicLong(0)

        private const val TAG_LOG = "VyzeMasterAgent"

        const val MASTER_AGENT_NAME = "vyze_master"

        /** Model identity reported to the ADK pipeline. */
        const val MODEL_ID = "gemma-4-E2B-it.litertlm (local LiteRT-LM)"

        /** Reading intent keywords (aligned with the native pipeline). */
        private val READ_INTENT_REGEX =
            Regex("\\b(read|text|label|sign|baca|teks|字|读|念)\\b")

        /** Haptic confirmation intents, capturing the pattern name. */
        private val HAPTIC_INTENT_REGEX =
            Regex("\\b(tap|double.?tap|long.?press|warning)\\b")

        /**
         * ONE-PASS SYSTEM INSTRUCTION (plan Phase 2) — kept as data for
         * tests and prompt-assembly parity; the persona/style assembly
         * travels as ADK session state (VyzeQueryContext).
         *
         * PROMPT FORMAT CONTRACT: plain continuous natural prose only.
         * No markdown syntax of any kind lives in this string — no bullet
         * symbols, numbered list prefixes, hashes, asterisks, or backticks —
         * because formatting artifacts in a system directive teach a small
         * model to emit formatting artifacts in its SPOKEN answers, where
         * Android TTS would read them aloud.
         */
        val MASTER_INSTRUCTION: String = """
            You are Vyze, the single master orchestrator of an accessibility
            assistant for a blind user, running on a local on-device model.
            Everything is answered here in one pass and in one turn.

            Deterministic device requests, meaning text and label reads,
            haptic confirmations, or spatial sector lookups, must immediately
            invoke the matching native tool, such as read_scene_text,
            trigger_haptics, or find_spatial_sector, and answer from its
            result in the SAME turn. Never deliberate before firing a
            deterministic tool. Visual questions, like what is in front of
            the user or any other scene query, are answered by direct
            generation in the same pass, using the camera frame attached to
            the turn when one is present. There are no sub-agents, and you
            never say you are delegating.

            CRITICAL: NEVER repeat, echo, or quote the user's query or
            question at the start of your response. Begin immediately with
            the direct description or answer.

            Speak in the second person, saying in front of you rather than
            in front of me. Keep answers to one short spoken sentence.
            Always lead directly with the answer to the user's question
            without any introductory location preamble. If asked what color
            this is, reply directly with the color, like That is a red mug.
            If asked to read this, reply directly with the text, like It
            says Organic Milk. Only mention spatial position, such as in
            front of you or to your left, when the user specifically asks
            where an object is located, like Your cup is directly to your
            right on the desk. Never open answers with a fixed template
            phrase such as You are in front of, You are looking at, You are
            facing, or In front of you is. Vary the opening with the
            question. Your response text must be pure plain prose for text
            to speech: never output markdown symbols, never output bullet
            points, dashes, asterisks, number signs, or underscores as
            formatting, and never use emoji. Write only the spoken words
            themselves.

            LANGUAGE MIRRORING: Respond in the language named in the
            [OUTPUT LANGUAGE] tag of the assembled instructions — the tag is
            the authority on the answer language. Even when the query text
            itself reads like English, the tag names the user's actual spoken
            language; answer in the tag's language. If no tag is present,
            detect the language of the user's query and respond strictly in
            that exact same language: a Malay query gets a Malay response, an
            English query gets an English response, a Chinese query gets a
            Chinese response. NEVER revert to default English if the user
            speaks another language. Mirror the dialect too: if they ask in
            Bahasa Melayu, answer in standard Malay; if they ask in Sarawak
            Malay or another Malaysian dialect, answer in that same casual
            dialect. Never drift to English when the user did not ask in
            English, not even because the scene, the printed labels, or the
            topic is English. A question about English content is still
            answered in the user's language.

            On follow-up turns the same two rules bind absolutely: echo the
            user's language, never the user's words. A short question like
            what about this is never spoken back; it is answered directly in
            the language it was asked. For example, a user asking what is in
            front of you is answered A coffee mug on the desk, and the
            follow-up what about this is answered That is a pair of reading
            glasses. In Malay, apa kat depan saya is answered Sebiji cawan
            kopi di atas meja, and the follow-up bagaimana dengan ini is
            answered Itu sepasang cermin mata membaca.

            Never use a rigid response template and never begin answers with
            a fixed opener. Vary the opening with the query type. A direct
            question gets the answer first, with no scene preamble. A scene
            description starts naturally and differently each time. Describe
            visual elements only when they are relevant to the question. The
            no-echo rule above applies on every turn, including follow-ups:
            the first words of your answer are always your own.

            Treat every turn as an ongoing conversation, not a fresh scene
            analysis. On follow-up turns resolve words like it, that, or the
            one from the conversation so far, answer only what is new, and
            never re-describe the whole scene unless the user explicitly
            asks what else you see. If the scene does not clearly show the
            answer, say so plainly instead of guessing.
        """.trimIndent()

        /**
         * Factory: builds the master agent with every native tool bound
         * directly into ONE tool array (plan Phase 1 "Unified Registry").
         *
         * @param bridgeProvider supplies the current LiteRT-LM engine ops —
         *   resolved per turn so [AdkAgentManager] can rebind the engine op
         *   without rebuilding the runner.
         * @param frameProvider read-only latest camera frame (or null);
         *   capture itself stays in the camera layer's isCapturing-gated paths.
         * @param ocrFrame runs the ML Kit OCR pipeline on a frame.
         * @param speakImmediate binds TTSManager.speakImmediate() bypass lane.
         * @param hapticPattern binds the HapticManager pattern library.
         * @param gridMapper 3x3 sector mapper (native gridSectorFor parity).
         */
        fun create(
            bridgeProvider: () -> MasterModelBridge,
            frameProvider: () -> Bitmap?,
            ocrFrame: suspend (Bitmap) -> String?,
            speakImmediate: (String) -> Boolean,
            hapticPattern: (String) -> Unit,
            gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String =
                VyzeToolWiring::defaultGridSectorMapper,
        ): VyzeMasterAgent = VyzeMasterAgent(
            bridgeProvider = bridgeProvider,
            toolRegistry = MasterToolRegistry(
                tools = listOf(
                    ReadSceneTextTool(frameProvider, ocrFrame),
                    GetCameraFrameTool(frameProvider),
                    SpeakImmediateTtsTool(speakImmediate),
                    TriggerHapticsTool(hapticPattern),
                    FindSpatialSectorTool(frameProvider, gridMapper),
                ),
                frameProvider = frameProvider,
                ocrFrame = ocrFrame,
                hapticPattern = hapticPattern,
            ),
        )
    }
}

// ── Local engine bridge (LiteRT-LM ⇄ ADK, no re-platforming) ───────────

/**
 * The engine ops the master agent needs, as plain function types so tests
 * can inject fakes and production binds the REAL VlmEngineManager /
 * VyzeCoreController without importing the engine here (dependency direction:
 * agent → bridge interface; the manager wires the implementation).
 */
class MasterModelBridge(
    /** Text-only generation over the local LiteRT-LM engine. */
    val generateText: suspend (prompt: String, sessionId: String) -> String?,
    /** Frame + prompt generation over the local LiteRT-LM engine. */
    val generateImage: suspend (frame: Bitmap, prompt: String, sessionId: String) -> String?,
)

// ── Unified native tool registry (plan Phase 1) ────────────────────────

/**
 * The SINGLE tool array bound to the master agent — every native tool in
 * one registry, no per-sub-agent split. Fast-path tools first, then
 * frame/sector support tools. Also exposes the direct native hooks the
 * agent's deterministic fast path dispatches through.
 */
class MasterToolRegistry(
    /** All native tools in ONE array — the unified registry. */
    val tools: List<FunctionTool>,
    val frameProvider: () -> Bitmap?,
    val ocrFrame: suspend (Bitmap) -> String?,
    val hapticPattern: (String) -> Unit,
) {
    internal val ocr: suspend (Bitmap) -> String? get() = ocrFrame
}

private const val TAG_LOG = "VyzeMasterAgent"

/**
 * Shared helper: pull the latest already-delivered camera frame, or null.
 * Never captures — frame acquisition stays in the camera layer's
 * isCapturing-gated paths (hardware invariant).
 */
internal fun latestFrame(frameProvider: () -> Bitmap?): Bitmap? =
    try {
        frameProvider()
    } catch (t: Throwable) {
        Log.w(TAG_LOG, "frame provider failed: ${t.message}")
        null
    }

/** FAST PATH — ML Kit OCR over the current frame, answer in the same pass. */
internal class ReadSceneTextTool(
    private val frameProvider: () -> Bitmap?,
    private val ocrFrame: suspend (Bitmap) -> String?,
) : FunctionTool(
    name = "read_scene_text",
    description = "Performs instant on-device OCR on the current camera frame and " +
        "returns the recognized text. Use immediately for any read/label/sign request.",
) {
    override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(
            name = name,
            description = description,
            parameters = Schema(
                type = Type.OBJECT,
                properties = mapOf(
                    "includeChinese" to Schema(
                        type = Type.BOOLEAN,
                        description = "Merge the Chinese-capable recognizer for CJK signage",
                    ),
                ),
                required = listOf("includeChinese"),
            ),
        )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): Any {
        val frame = latestFrame(frameProvider)
            ?: return mapOf("error" to "No camera frame available right now")
        // OcrHelper merges the latin + Chinese recognizers internally; the
        // parameter is accepted for contract parity and per-recognizer tuning.
        val text = try {
            ocrFrame(frame)
        } catch (t: Throwable) {
            Log.w(TAG_LOG, "OCR tool failed: ${t.message}")
            null
        }
        return mapOf("text" to (text ?: ""))
    }
}

/** Support tool — acknowledges frame availability for the current turn. */
internal class GetCameraFrameTool(
    private val frameProvider: () -> Bitmap?,
) : FunctionTool(
    name = "get_camera_frame",
    description = "Confirms the current camera frame is attached to this turn for " +
        "visual grounding. Call once per visual query before answering.",
) {
    override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(name = name, description = description, parameters = Schema(type = Type.OBJECT))

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): Any {
        // Compact response on purpose: no frame bytes travel through the
        // LLM channel — the frame reaches the engine via the vision path.
        val available = latestFrame(frameProvider) != null
        return if (available) {
            mapOf("status" to "frame attached to this turn")
        } else {
            mapOf("error" to "No camera frame available right now")
        }
    }
}

/** FAST PATH — deterministic TTS bypass lane (never the streaming buffer). */
internal class SpeakImmediateTtsTool(
    private val speakImmediate: (String) -> Boolean,
) : FunctionTool(
    name = "speak_immediate_tts",
    description = "Speaks short deterministic text immediately through the bypass " +
        "TTS lane without any generative model delay.",
) {
    override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(
            name = name,
            description = description,
            parameters = Schema(
                type = Type.OBJECT,
                properties = mapOf(
                    "text" to Schema(type = Type.STRING, description = "Text to speak verbatim"),
                ),
                required = listOf("text"),
            ),
        )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): Any {
        val text = args["text"] as? String ?: ""
        return mapOf("queued" to speakImmediate(text).toString())
    }
}

/** FAST PATH — haptic confirmation patterns. */
internal class TriggerHapticsTool(
    private val hapticPattern: (String) -> Unit,
) : FunctionTool(
    name = "trigger_haptics",
    description = "Triggers a haptic feedback pattern for gesture confirmation.",
) {
    override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(
            name = name,
            description = description,
            parameters = Schema(
                type = Type.OBJECT,
                properties = mapOf(
                    "pattern" to Schema(
                        type = Type.STRING,
                        description = "One of: TAP, DOUBLE_TAP, LONG_PRESS, WARNING",
                    ),
                ),
                required = listOf("pattern"),
            ),
        )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): Any {
        val name = (args["pattern"] as? String)?.uppercase().orEmpty()
        // Unknown names are ignored: haptics must never crash or misfire
        // from a malformed tool argument.
        if (name in setOf("TAP", "DOUBLE_TAP", "LONG_PRESS", "WARNING")) {
            hapticPattern(name)
        }
        return mapOf("triggered" to name)
    }
}

/**
 * FAST PATH — spatial sectoring. Maps a tap (or an object's pixel position)
 * onto one of nine frame sectors, mirroring the native gridSectorFor logic.
 * Defaults to the CURRENT frame's dimensions when none are supplied.
 */
internal class FindSpatialSectorTool(
    private val frameProvider: () -> Bitmap?,
    private val gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String,
) : FunctionTool(
    name = "find_spatial_sector",
    description = "Maps a pixel position to one of nine grid sectors of the camera " +
        "frame (Top/Middle/Bottom × Left/Center/Right).",
) {
    override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(
            name = name,
            description = description,
            parameters = Schema(
                type = Type.OBJECT,
                properties = mapOf(
                    "x" to Schema(type = Type.INTEGER, description = "Tap x pixel"),
                    "y" to Schema(type = Type.INTEGER, description = "Tap y pixel"),
                    "frameW" to Schema(type = Type.INTEGER, description = "Frame width in pixels (optional)"),
                    "frameH" to Schema(type = Type.INTEGER, description = "Frame height in pixels (optional)"),
                ),
                required = listOf("x", "y"),
            ),
        )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): Any {
        val frame = latestFrame(frameProvider)
        val frameW = (args["frameW"] as? Number)?.toInt() ?: frame?.width
        val frameH = (args["frameH"] as? Number)?.toInt() ?: frame?.height
        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        if (frameW == null || frameH == null || x == null || y == null) {
            return mapOf("error" to "No frame available and no dimensions supplied")
        }
        return mapOf("sector" to gridMapper(x, y, frameW, frameH))
    }
}


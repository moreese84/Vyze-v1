package com.vyze.app.agent

import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the SINGLE MASTER ORCHESTRATOR (migration plan v3):
 * the unified tool registry ([VyzeMasterAgent] native tools), the sector
 * mapper parity with the native gridSectorFor logic, and the
 * [MasterModelAdapter] prompt folding. Android-context wiring
 * (VyzeToolWiring.masterToolHooks) is intentionally NOT covered here — it
 * touches TTSManager / ML Kit and belongs to instrumented tests.
 */
class VyzeMasterAgentTest {

    // ── Unified registry: all native tools bound to ONE agent ─────

    @Test
    fun `master agent registers all native tools in one array`() {
        val agent = VyzeMasterAgent.create(
            bridgeProvider = { MasterModelBridge({ _, _ -> null }, { _, _, _ -> null }) },
            frameProvider = { null },
            ocrFrame = { null },
            speakImmediate = { false },
            hapticPattern = {},
        )
        val names = agent.tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "read_scene_text",
                "get_camera_frame",
                "speak_immediate_tts",
                "trigger_haptics",
                "find_spatial_sector",
            ),
            names,
        )
        assertEquals("vyze_master", agent.name)
    }

    // ── Sector mapper (SpatialSectoringEngine parity) ─────────────

    @Test
    fun `defaultGridSectorMapper produces all nine sectors`() {
        val m = VyzeToolWiring::defaultGridSectorMapper
        assertEquals("Top-Left", m(0, 0, 300, 300))
        assertEquals("Top-Center", m(100, 0, 300, 300))
        assertEquals("Top-Right", m(299, 0, 300, 300))
        assertEquals("Middle-Left", m(0, 150, 300, 300))
        assertEquals("Middle-Center", m(150, 150, 300, 300))
        assertEquals("Middle-Right", m(200, 150, 300, 300))
        assertEquals("Bottom-Left", m(0, 299, 300, 300))
        assertEquals("Bottom-Center", m(150, 250, 300, 300))
        assertEquals("Bottom-Right", m(299, 299, 300, 300))
    }

    @Test
    fun `defaultGridSectorMapper boundary semantics match native third rules`() {
        val m = VyzeToolWiring::defaultGridSectorMapper
        // x == frameW/3 is Center (x < frameW/3 is false at exactly one third)
        assertEquals("Top-Left", m(99, 0, 300, 300))
        assertEquals("Top-Center", m(100, 0, 300, 300))
        // y == 2*frameH/3 is Bottom
        assertEquals("Middle-Left", m(0, 199, 300, 300))
        assertEquals("Bottom-Left", m(0, 200, 300, 300))
    }

    // ── One-pass instruction contract ─────────────────────────────

    @Test
    fun `master instruction mandates one-pass native dispatch and no sub-agents`() {
        val i = VyzeMasterAgent.MASTER_INSTRUCTION
        assertTrue(i.contains("read_scene_text"))
        assertTrue(i.contains("trigger_haptics"))
        assertTrue(i.contains("find_spatial_sector"))
        assertTrue("no sub-agents", i.contains("no sub-agents"))
        assertTrue("one pass", i.contains("SAME turn") || i.contains("SAME pass"))
    }

    // ── Prompt folding (MasterModelAdapter semantics, pure part) ──

    @Test
    fun `query context mirrors native persona and style directives`() {
        val ctx = VyzeQueryContext()
        val p = ctx.instructionFor("what is paracetamol used for?")
        assertTrue(p.contains(VyzeQueryContext.DEFAULT_PERSONA_DIRECTIVE))
        assertTrue(p.contains(VyzeQueryContext.DEFAULT_ANSWER_STYLE_DIRECTIVE))
        assertTrue(p.contains("User question: what is paracetamol used for?"))
        assertEquals("adk_master", ctx.asSessionState()["lane"])
    }

    // ── Anti-echo + language-mirroring contract ─────────────────

    @Test
    fun `master instruction carries the strict anti-echo directive`() {
        // Whitespace-normalized: the trimIndent block wraps lines, so
        // contains() must ignore line breaks (case-insensitive too).
        val i = VyzeMasterAgent.MASTER_INSTRUCTION.replace(Regex("\\s+"), " ").lowercase()
        assertTrue(i.contains("never repeat, echo, or quote the user's query or question"))
        assertTrue(i.contains("begin immediately with the direct description or answer"))
    }

    @Test
    fun `master instruction carries the mandatory language mirroring rule`() {
        val i = VyzeMasterAgent.MASTER_INSTRUCTION.replace(Regex("\\s+"), " ").lowercase()
        assertTrue(i.contains("language mirroring"))
        // TAG-AUTHORITY (v3): the [OUTPUT LANGUAGE] tag outranks the query
        // text — the garble-rescue path depends on this precedence.
        assertTrue(i.contains("the tag is the authority"))
        assertTrue(i.contains("even when the query text itself reads like english"))
        assertTrue(i.contains("respond strictly in that exact same language"))
        assertTrue(i.contains("never revert to default english"))
    }

    @Test
    fun `query context directives carry anti-echo and language mirroring`() {
        val persona = VyzeQueryContext.DEFAULT_PERSONA_DIRECTIVE
        val style = VyzeQueryContext.DEFAULT_ANSWER_STYLE_DIRECTIVE
        for (d in listOf(persona, style)) {
            assertTrue(d.contains("NEVER repeat, echo, or quote the user's query or question"))
            assertTrue(d.contains("LANGUAGE MIRRORING"))
            // TAG-AUTHORITY (v3): tag outranks query text in both directives.
            assertTrue(d.contains("the tag is the authority on the answer language"))
            assertTrue(d.contains("Even when the query text itself reads like English"))
            assertTrue(d.contains("Never revert to default English"))
        }
    }

    @Test
    fun `assembled master prompt ends with the no-echo output contract`() {
        // Delimiter-audit contract: the raw user question must never be the
        // last text before the generation boundary.
        val p = VyzeQueryContext().instructionFor("what about this?")
        assertTrue(p.endsWith(VyzeQueryContext.OUTPUT_CONTRACT_TAIL))
        assertTrue(p.contains("never repeat, echo, or quote the question"))
    }

    // ── Bridge ────────────────────────────────────────────────────

    @Test
    fun `engine bridge forwards text op`() = runBlocking {
        var textPrompt: String? = null
        val bridge = VyzeToolWiring.engineBridge(
            analyzeText = { prompt, _ -> textPrompt = prompt; "text-answer" },
            analyzeImage = { _, _, _ -> "image-answer" },
        )
        assertEquals("text-answer", bridge.generateText("q", "s1"))
        assertEquals("q", textPrompt)
    }

    // ── Haptic fast path (audit P2 #36/#37, P3 #78) ────────────────

    @Test
    fun `haptic commands match case-insensitively and normalize to canonical names`() {
        // #36: the regex had no ignore-case, so "Double tap" fell through
        // to VLM. #37: "double tap" uppercased to "DOUBLE TAP" (space),
        // which the underscore-only wiring ignored — yet "Done." was
        // spoken. #78: default-locale uppercase broke Turkish matching.
        assertEquals("TAP", VyzeMasterAgent.companionHapticPatternForCommand("tap"))
        assertEquals("TAP", VyzeMasterAgent.companionHapticPatternForCommand("Tap"))
        assertEquals("DOUBLE_TAP", VyzeMasterAgent.companionHapticPatternForCommand("double tap"))
        assertEquals("DOUBLE_TAP", VyzeMasterAgent.companionHapticPatternForCommand("Double tap"))
        assertEquals("DOUBLE_TAP", VyzeMasterAgent.companionHapticPatternForCommand("DOUBLE TAP"))
        assertEquals("DOUBLE_TAP", VyzeMasterAgent.companionHapticPatternForCommand("double-tap"))
        assertEquals("LONG_PRESS", VyzeMasterAgent.companionHapticPatternForCommand("long press"))
        assertEquals("LONG_PRESS", VyzeMasterAgent.companionHapticPatternForCommand("Long Press"))
        assertEquals("WARNING", VyzeMasterAgent.companionHapticPatternForCommand("warning"))
    }

    @Test
    fun `non-commands and unknown patterns return null, never a fake Done`() {
        assertNull(VyzeMasterAgent.companionHapticPatternForCommand("what is tap water?"))
        assertNull(VyzeMasterAgent.companionHapticPatternForCommand("doublextap"))
        assertNull(VyzeMasterAgent.companionHapticPatternForCommand("tap twice fast"))
        assertNull(VyzeMasterAgent.companionHapticPatternForCommand(""))
    }

    @Test
    fun `fast haptic path fires the canonical pattern and says Done`() = runBlocking {
        val fired = mutableListOf<String>()
        val agent = VyzeMasterAgent.create(
            bridgeProvider = {
                MasterModelBridge(
                    generateText = { _, _ -> "vlm-fallback" },
                    generateImage = { _, _, _ -> "vlm-fallback" },
                )
            },
            frameProvider = { null },
            ocrFrame = { null },
            speakImmediate = { false },
            hapticPattern = { fired.add(it) },
        )
        val runner = InMemoryRunner(agent = agent, appName = "test")
        var lastText = ""
        runner.runAsync(
            userId = "u",
            sessionId = "s-haptic",
            invocationId = null,
            newMessage = Content("user", listOf(Part(text = "Double tap"))),
            stateDelta = emptyMap(),
            runConfig = RunConfig(),
        ).collect { event ->
            val text = event.content?.parts?.mapNotNull { it.text }
                ?.joinToString("").orEmpty()
            if (text.isNotEmpty()) lastText = text
        }
        // Pre-fix "Double tap" fell through to VLM (no fire, vlm text).
        assertEquals(listOf("DOUBLE_TAP"), fired)
        assertEquals("Done.", lastText)
    }

    // ── Undecodable frame (audit P2 #11) ───────────────────────────

    @Test
    fun `undecodable frame bytes fail safe, never reach the engine`() = runBlocking {
        var imageCalls = 0
        val agent = VyzeMasterAgent.create(
            bridgeProvider = {
                MasterModelBridge(
                    generateText = { _, _ -> "text-answer" },
                    generateImage = { _, _, _ -> imageCalls++; "image-answer" },
                )
            },
            frameProvider = { null },
            ocrFrame = { null },
            speakImmediate = { false },
            hapticPattern = {},
        )
        val runner = InMemoryRunner(agent = agent, appName = "test")
        var lastText = ""
        runner.runAsync(
            userId = "u",
            sessionId = "s-frame",
            invocationId = null,
            newMessage = Content(
                "user",
                listOf(
                    Part(text = "what is this?"),
                    Part(
                        inlineData = Blob(
                            mimeType = "image/jpeg",
                            displayName = "f.jpg",
                            data = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04),
                        )
                    ),
                ),
            ),
            stateDelta = emptyMap(),
            runConfig = RunConfig(),
        ).collect { event ->
            val text = event.content?.parts?.mapNotNull { it.text }
                ?.joinToString("").orEmpty()
            if (text.isNotEmpty()) lastText = text
        }
        assertEquals(0, imageCalls)
        assertEquals("I couldn't read that camera frame.", lastText)
    }

    // ── Read-intent matching: CJK substring + reading (#76) ──

    @Test
    fun `latin read intents match whole words`() {
        assertTrue(VyzeMasterAgent.companionIsReadIntent("read the label"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("Read this sign"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("baca label ini"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("teks ini apa"))
    }

    @Test
    fun `reading counts as a read intent`() {
        assertTrue(VyzeMasterAgent.companionIsReadIntent("reading the sign"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("I am reading this"))
    }

    @Test
    fun `cjk read intents match as substrings`() {
        assertTrue(VyzeMasterAgent.companionIsReadIntent("请读这个"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("这是什么字"))
        assertTrue(VyzeMasterAgent.companionIsReadIntent("念一下"))
    }

    @Test
    fun `embeddings and non-reads do not match`() {
        assertFalse(VyzeMasterAgent.companionIsReadIntent("bread"))
        assertFalse(VyzeMasterAgent.companionIsReadIntent("already done"))
        assertFalse(VyzeMasterAgent.companionIsReadIntent("what is this"))
        assertFalse(VyzeMasterAgent.companionIsReadIntent(""))
    }

    // ── read_scene_text declares no decorative knobs (#77) ──

    @Test
    fun `read scene text declares no parameters`() {
        // OcrHelper always merges both recognizers — the old required
        // includeChinese taught the model a knob that did nothing.
        val tool = ReadSceneTextTool(frameProvider = { null }, ocrFrame = { null })
        val declaration = tool.declaration()
        assertTrue(declaration.parameters?.required.isNullOrEmpty())
        assertTrue(declaration.parameters?.properties.isNullOrEmpty())
    }
}

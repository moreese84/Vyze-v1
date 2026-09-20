package com.vyze.app.core

import com.vyze.app.data.VyzeMemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the ANTI-ECHO + LANGUAGE-MIRRORING prompt contract:
 *
 * 1. The built prompt must never present the user's query as a quoted
 *    output preface (the old `Answer: "<query>"` line) — the format that
 *    taught the model to replay the question on follow-ups.
 * 2. The mandatory language-mirroring rule must appear on EVERY prompt,
 *    regardless of mode, language, or dialogue context.
 * 3. Follow-up dialogue context must be framed as read-only, role-labeled
 *    history — never an unlabeled blob that ends with a `User:` line the
 *    model can mistake for its own output prefix.
 * 4. Few-shot follow-up examples (EN + MS + ZH) must ship in the prompt
 *    blocks, demonstrating clean multilingual follow-ups without echoing.
 */
class DynamicPromptBuilderTest {

    /** In-memory fake — DynamicPromptBuilder only writes through the DAO. */
    private class FakeMemoryDao : com.vyze.app.data.MemoryDao {
        private val rows = mutableListOf<VyzeMemoryEntity>()
        override suspend fun upsert(memory: VyzeMemoryEntity): Long {
            rows.add(memory); return rows.size.toLong()
        }
        override suspend fun get(category: String, key: String): VyzeMemoryEntity? =
            rows.firstOrNull { it.category == category && it.key == key }
        override suspend fun getAllByCategory(category: String): List<VyzeMemoryEntity> =
            rows.filter { it.category == category }
        override fun observeByCategory(category: String): kotlinx.coroutines.flow.Flow<List<VyzeMemoryEntity>> =
            kotlinx.coroutines.flow.flowOf(rows.filter { it.category == category })
        override suspend fun getRecent(limit: Int): List<VyzeMemoryEntity> = rows.takeLast(limit)
        override suspend fun getRecentEnvironment(limit: Int): List<VyzeMemoryEntity> =
            rows.filter { it.category == "environment" }.takeLast(limit)
        override suspend fun getAllPreferences(): List<VyzeMemoryEntity> =
            rows.filter { it.category == "preference" }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteByCategory(category: String) { rows.removeAll { it.category == category } }
        override suspend fun pruneOlderThan(cutoffTimestamp: Long) {
            rows.removeAll { it.timestamp < cutoffTimestamp }
        }
        override suspend fun getCount(): Int = rows.size
    }

    private fun builder() = DynamicPromptBuilder(FakeMemoryDao())

    // ── 1. No quoted query preface (echo vector) ─────────────────

    @Test
    fun `direct query prompt contains no quoted Answer template`() = runBlocking {
        val q = "what about this?"
        val p = builder().buildPrompt(queryOverride = q)
        assertFalse(p.contains("Answer: \"$q\""))
        assertTrue(p.contains("The user asked: $q"))
        // The query appears EXACTLY once — as input to answer, never as an
        // output template.
        assertEquals(1, p.split(q).size - 1)
    }

    @Test
    fun `every prompt carries the anti-echo directive top and bottom`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "what is this?",
            dialogueContext = "User: What is in front of me?\\nVyze: A coffee mug on the desk."
        )
        assertTrue(p.contains("NEVER repeat, echo, or quote the user's query or question"))
        assertTrue(p.contains("Begin immediately with the direct description or answer"))
        // Bottom reinforcement in the REMEMBER line.
        assertTrue(p.contains("never repeat or echo the user's question"))
        // No-echo output contract directly under the current query.
        assertTrue(p.contains("Respond with the answer only"))
    }

    // ── 2. Mandatory language mirroring on every prompt ──────────

    @Test
    fun `language mirroring mandate is present for every locale and mode`() = runBlocking {
        // TAG-AUTHORITY contract (v3): the mandate names the [OUTPUT LANGUAGE]
        // tag as the answer-language authority, with query-detection only as
        // the no-tag fallback.
        val mandate = "LANGUAGE MIRRORING: Respond in the language named in the"
        val locales = listOf(Locale.US, Locale("ms"), Locale("zh"), Locale("ja"))
        for (loc in locales) {
            for (queryOverride in listOf(null, "apa ini?")) {
                val p = builder().buildPrompt(
                    queryOverride = queryOverride,
                    userLocale = loc,
                    continuousMode = queryOverride == null,
                )
                assertTrue("missing mirror mandate for $loc", p.contains(mandate))
                assertTrue("missing tag-authority clause for $loc",
                    p.contains("the tag is the authority on the answer language"))
                assertTrue("missing garble clause for $loc",
                    p.contains("Even when the query text itself reads like English"))
                assertTrue("missing English-drift ban for $loc", p.contains("Never revert to default English"))
                assertTrue("missing bottom mirror for $loc", p.contains("REMEMBER: Respond only in"))
            }
        }
    }

    @Test
    fun `non-english locales get their explicit output language anchor`() = runBlocking {
        val p = builder().buildPrompt(queryOverride = "apa ini?", userLocale = Locale("ms"))
        assertTrue(p.contains("[OUTPUT LANGUAGE: Malay]"))
        assertTrue(p.contains("Do NOT use English"))
        val zh = builder().buildPrompt(queryOverride = "这是什么？", userLocale = Locale("zh"))
        assertTrue(zh.contains("[OUTPUT LANGUAGE: Chinese]"))
    }

    // ── 3. Follow-up delimiter framing ───────────────────────────

    @Test
    fun `dialogue context is framed as read-only role-labeled history`() = runBlocking {
        val history = "User: What is in front of me?\\nVyze: A coffee mug on the desk."
        val p = builder().buildPrompt(queryOverride = "What about this?", dialogueContext = history)
        assertTrue(p.contains("Recent conversation (context only — never repeat, echo, or quote these lines):"))
        assertTrue(p.contains(history))
        // The last line before the generation boundary is the current query,
        // followed by the no-echo output contract — never the raw history.
        assertTrue(p.contains("The user asked: What about this?"))
        assertTrue(p.indexOf("Respond with the answer only") > p.indexOf(history))
    }

    // ── 4. Few-shot follow-up examples ship in every mode block ──

    @Test
    fun `multilingual follow-up few-shot examples are embedded`() = runBlocking {
        val en = builder().buildPrompt(queryOverride = "what about this?", userLocale = Locale.US)
        val ms = builder().buildPrompt(queryOverride = "Bagaimana dengan ini?", userLocale = Locale("ms"))
        val zh = builder().buildPrompt(queryOverride = "那这个呢？", userLocale = Locale("zh"))

        // EN exchange.
        assertTrue(en.contains("User: What is in front of me?"))
        assertTrue(en.contains("Vyze: A coffee mug on the desk."))
        assertTrue(en.contains("User: What about this?"))
        assertTrue(en.contains("Vyze: That is a pair of reading glasses."))

        // MS exchange (language mirroring demonstrated in-language).
        assertTrue(ms.contains("User: Apa kat depan saya?"))
        assertTrue(ms.contains("Vyze: Sebiji cawan kopi di atas meja."))
        assertTrue(ms.contains("User: Bagaimana dengan ini?"))
        assertTrue(ms.contains("Vyze: Itu sepasang cermin mata membaca."))

        // ZH exchange.
        assertTrue(zh.contains("User: 我前面有什么？"))
        assertTrue(zh.contains("Vyze: 桌上有一个咖啡杯。"))
        assertTrue(zh.contains("User: 那这个呢？"))
        assertTrue(zh.contains("Vyze: 那是一副老花眼镜。"))

        // The examples must also carry the no-echo demonstration rule.
        assertTrue(en.contains("NEVER repeat or echo the user's question"))
        assertTrue(ms.contains("JANGAN sesekali ulang atau gema soalan pengguna"))
    }

    // ── Engine turn delimiters (VlmEngineManager) ────────────────

    @Test
    fun `gemma turn prompt uses exact ADK LiteRT-LM role tags`() {
        val out = VlmEngineManager.companionBuildGemmaTurnPrompt(
            userContent = "what is in front of me?",
            systemPrompt = "SYS",
        )
        assertTrue(out.startsWith("<start_of_turn>user\n"))
        assertTrue(out.endsWith("<end_of_turn>\n<start_of_turn>model\n"))
        assertTrue(out.contains("SYS"))
        assertTrue(out.contains("what is in front of me?"))
        // Exactly one user turn and one model opening tag.
        assertEquals(1, out.split("<start_of_turn>user").size - 1)
        assertEquals(1, out.split("<start_of_turn>model").size - 1)
        // The model opening tag comes AFTER the user turn closes — the model
        // generates after the boundary, never inside the user turn.
        assertTrue(out.indexOf("<end_of_turn>") < out.indexOf("<start_of_turn>model"))
    }

    @Test
    fun `conversation turns fold into the user turn as labeled context`() {
        val out = VlmEngineManager.companionBuildGemmaTurnPrompt(
            userContent = "What about this?",
            systemPrompt = "",
            conversationTurns = listOf("What is in front of me?" to "A coffee mug on the desk."),
        )
        assertTrue(out.contains("Recent conversation (context only — never repeat, echo, or quote these lines):"))
        assertTrue(out.contains("User: What is in front of me?"))
        assertTrue(out.contains("You answered: A coffee mug on the desk."))
        // The CURRENT query stays the last line of the user turn.
        assertTrue(out.endsWith("What about this?\n<end_of_turn>\n<start_of_turn>model\n"))
        // History is never adjacent to the generation boundary.
        assertTrue(out.indexOf("User: What is in front of me?") < out.indexOf("What about this?"))
    }
}

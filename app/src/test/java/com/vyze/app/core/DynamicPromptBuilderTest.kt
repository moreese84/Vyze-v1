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

        // ZH parity (2026-09-26 audit finding #1): the follow-up header is
        // fully localized in Chinese — never the old English-only header.
        assertTrue(zh.contains("后续对话示例"))
        assertTrue(zh.contains("绝不重复或复述用户的问题"))
        assertFalse(zh.contains("Follow-up conversation examples"))
    }

    // ── 5. Canned failure-phrase override (mirroring parity) ─────

    @Test
    fun `ms and zh prompts pin localized canned failure phrases`() = runBlocking {
        val ms = builder().buildPrompt(
            queryOverride = "berapa duit ni?",
            userLocale = Locale("ms"),
            currencyMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "这是什么钱？",
            userLocale = Locale("zh"),
            currencyMode = true,
        )

        // Both carry the override clause...
        for ((loc, p) in listOf("ms" to ms, "zh" to zh)) {
            assertTrue("$loc missing override clause", p.contains("FAILURE-PHRASE OVERRIDE"))
            assertTrue("$loc missing speak-instead rule", p.contains("instead — never the English one"))
        }
        // ...with the currency safety phrase pinned in the user's language
        // (the never-guess path is the one a wrong-language answer costs most).
        assertTrue(ms.contains("Saya tidak dapat membaca nilai ini dengan jelas"))
        assertTrue(zh.contains("我读不清楚"))
        // The bank-card and read-failure phrases are covered too.
        assertTrue(ms.contains("Saya tidak dapat mengenal pasti kad ini dengan jelas"))
        assertTrue(zh.contains("文字不清楚"))
    }

    @Test
    fun `english prompts get no failure-phrase override`() = runBlocking {
        val en = builder().buildPrompt(
            queryOverride = "how much is this note?",
            userLocale = Locale.US,
            currencyMode = true,
        )
        assertFalse(en.contains("FAILURE-PHRASE OVERRIDE"))
        // Other unsupported languages fall through to null as well — no clause,
        // English-drift handling stays with the mirroring layers.
        val ja = builder().buildPrompt(queryOverride = "これは何？", userLocale = Locale("ja"))
        assertFalse(ja.contains("FAILURE-PHRASE OVERRIDE"))
    }

    // ── 6. Sensitive-ID privacy contract ────────────────────────

    @Test
    fun `sensitive id mode injects pinned per-language refusal late in prompt`() = runBlocking {
        val ms = builder().buildPrompt(
            queryOverride = "nombor kad pengenalan saya apa?",
            userLocale = Locale("ms"),
            ocrText = "KAD PENGENALAN MALAYSIA 900101-01-5231",
            sensitiveIdMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "这个卡号是什么？",
            userLocale = Locale("zh"),
            sensitiveIdMode = true,
        )

        // The refusal phrase is pinned in the user's language.
        assertTrue(ms.contains("Membaca nombor kad pengenalan atau nombor kad bank"))
        assertTrue(zh.contains("朗读您的身份证号码或银行卡号码是被禁止的"))

        // Late placement: the privacy rule must land AFTER the OCR verbatim
        // directive (3f vs 2b — last-seen rule wins on a 2B model).
        val ocrIdx = ms.indexOf("The OCR text above is the ground truth")
        val privacyIdx = ms.indexOf("PRIVACY — HIGHEST PRIORITY RULE")
        assertTrue(ocrIdx in 0 until privacyIdx)

        // The override contract instructs skipping the number in OCR text.
        assertTrue(ms.contains("overrides any instruction to read text verbatim"))
    }

    @Test
    fun `sensitive id off by default leaves no privacy clause`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "berapa duit ini?",
            userLocale = Locale("ms"),
            currencyMode = true,
        )
        assertFalse(p.contains("PRIVACY — HIGHEST PRIORITY RULE"))
    }

    @Test
    fun `currency rules anchor identity and gate serial reading to asks`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "ini duit apa?",
            userLocale = Locale("ms"),
            currencyMode = true,
        )
        // Identity anchor (the 'kad pengenalan' mislabel fix).
        assertTrue(p.contains("even if it resembles a card"))
        // Value-first ordering.
        assertTrue(p.contains("dominant color") && p.contains("FIRST"))
        // Serial reading is allowed but gated on being asked.
        assertTrue(p.contains("Serial numbers on banknotes are allowed to read"))
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

    // ── 7. Medicine DB-miss contract (P1b) ──────────────────

    @Test
    fun `medicine miss mode pins the localized no-record refusal`() = runBlocking {
        val en = builder().buildPrompt(
            queryOverride = "how often do I take this?",
            userLocale = Locale.US,
            medicineMissMode = true,
        )
        val ms = builder().buildPrompt(
            queryOverride = "ubat ini macam mana nak makan?",
            userLocale = Locale("ms"),
            medicineMissMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "這個藥要怎麼吃？",
            userLocale = Locale("zh"),
            medicineMissMode = true,
        )

        // Pinned spoken form per language — absence is spoken honestly,
        // never paraphrased into general knowledge.
        assertTrue(en.contains("I don't have a record of this medicine"))
        assertTrue(ms.contains("Saya tiada rekod ubat ini dalam senarai"))
        assertTrue(zh.contains("我的藥品列表裡沒有這個藥的資訊"))
        // The guess-ban is present in every language.
        for ((loc, p) in listOf("en" to en, "ms" to ms, "zh" to zh)) {
            assertTrue("$loc missing guess ban", p.contains("Never state or guess any dosage"))
        }
    }

    @Test
    fun `medicine miss off leaves no record rule and late placement wins`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "ubat apa ini?",
            userLocale = Locale("ms"),
            medicineMissMode = false,
        )
        assertFalse(p.contains("MEDICINE RECORD RULE"))

        // With OCR + a miss, the record rule lands AFTER the OCR verbatim
        // directive — last-seen rule wins on the 2B model.
        val withMiss = builder().buildPrompt(
            queryOverride = "how often do I take this?",
            userLocale = Locale.US,
            ocrText = "DICLAC RETARD 100mg",
            medicineMissMode = true,
        )
        val ocrIdx = withMiss.indexOf("The OCR text above is the ground truth")
        val missIdx = withMiss.indexOf("MEDICINE RECORD RULE")
        assertTrue(ocrIdx in 0 until missIdx)
    }

    // ── 8. Date-arithmetic contract (P1a) ────────────────────────

    @Test
    fun `date rules mode injects the contract in every language`() = runBlocking {
        // Detection is the controller's job (keyword gate); the builder only
        // injects when the flag is set — mirror that contract here.
        val asks = listOf(
            "what date is next Friday?" to Locale.US,
            "berapa hari lagi sampai raya?" to Locale("ms"),
            "下個星期五是什麼日期？" to Locale("zh"),
        )
        for ((q, loc) in asks) {
            val p = builder().buildPrompt(
                queryOverride = q,
                userLocale = loc,
                dateRulesMode = true,
            )
            assertTrue("no DATE ARITHMETIC RULE for: $q", p.contains("DATE ARITHMETIC RULE"))
            assertTrue("no device date anchor for: $q", p.contains("Today's date (from the device):"))
        }
    }

    @Test
    fun `date rules inject the device date and force shown work`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "what date is next Friday?",
            userLocale = Locale.US,
            dateRulesMode = true,
        )
        // Gallery pattern: the model never guesses the anchor — Kotlin injects
        // the device date deterministically above the shown-work rule.
        assertTrue(p.contains("Today's date (from the device):"))
        assertTrue(p.indexOf("Today's date (from the device):") <
            p.indexOf("DATE ARITHMETIC RULE"))
        // Shown work: write out the chain, never one-shot silent arithmetic.
        assertTrue(p.contains("write out each step"))
        assertTrue(p.contains("Do not do date arithmetic in one silent step"))
        // Truthful anchor: never a remembered date.
        assertTrue(p.contains("never to a date you remember"))
        // TTS is the only channel — the working is capped, not silent.
        // ALL-WEEKDAYS update: the common pairings are pre-computed, so the
        // short-answer cap replaces the old working-sentence cap.
        assertTrue(p.contains("Keep it short: answer with just the date sentence"))
    }

    @Test
    fun `device date line is localized per language`() = runBlocking {
        val ms = builder().buildPrompt(
            queryOverride = "berapa hari lagi sampai raya?",
            userLocale = Locale("ms"),
            dateRulesMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "下個星期五是什麼日期？",
            userLocale = Locale("zh"),
            dateRulesMode = true,
        )
        assertTrue(ms.contains("Today's date (from the device):"))
        assertTrue(zh.contains("Today's date (from the device):"))
    }

    @Test
    fun `date clause prose and worked example are localized per language`() = runBlocking {
        // MIRRORING PARITY (audit Gap 1): the date contract was the last
        // English-only clause — English instructions plus an English worked
        // example sitting under a zh/ms [OUTPUT LANGUAGE] tag. Each language
        // now gets rule prose AND a demonstrated worked example in-language.
        val ms = builder().buildPrompt(
            queryOverride = "berapa hari lagi sampai raya?",
            userLocale = Locale("ms"),
            dateRulesMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "下個星期五是什麼日期？",
            userLocale = Locale("zh"),
            dateRulesMode = true,
        )
        val en = builder().buildPrompt(
            queryOverride = "what date is next Friday?",
            userLocale = Locale.US,
            dateRulesMode = true,
        )

        // ALL-WEEKDAYS TABLE (device-evidenced extension): Kotlin computes
        // EVERY upcoming weekday, not just Friday — "How about next
        // Saturday/Wednesday" follow-ups previously fell back to model
        // arithmetic and mislabeled weekdays ("Saturday is October 1", a
        // Thursday). Tests recompute the exact same table from the clock.
        val (restEn, nextEn) = expectedTable("en")
        val (restMs, nextMs) = expectedTable("ms")
        val (restZh, nextZh) = expectedTable("zh")

        // MS: computed table in Malay — never a wrong static date.
        assertTrue(ms.contains("Baki minggu ini: $restMs"))
        assertTrue(ms.contains("Minggu depan: $nextMs"))
        assertFalse("ms must not demo in English", ms.contains("Rest of this week:"))
        assertTrue(ms.contains("Jangan buat kiraan tarikh dalam satu langkah senyap"))

        // ZH: computed table in Chinese — never a wrong static date.
        assertTrue(zh.contains("本周余下：$restZh"))
        assertTrue(zh.contains("下周：$nextZh"))
        assertFalse("zh must not demo in English", zh.contains("Rest of this week:"))
        assertTrue(zh.contains("不要一步静默算完"))

        // EN: computed table, shown-work contract retained for far dates.
        assertTrue(en.contains("Rest of this week: $restEn"))
        assertTrue(en.contains("Next week: $nextEn"))
        assertTrue(en.contains("Do not do date arithmetic in one silent step"))
    }

    /** Recompute the builder's weekday table exactly as production does. */
    private fun expectedTable(lang: String): Pair<String, String> {
        val today = java.time.LocalDate.now()
        val locale = when (lang) {
            "ms" -> Locale("ms")
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.US
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern(
            if (lang == "zh") "M月d日EEEE" else "EEEE, d MMMM", locale
        )
        val daysToNextMonday = ((8 - today.dayOfWeek.value) % 7).takeIf { it != 0 } ?: 7
        val nextMonday = today.plusDays(daysToNextMonday.toLong())
        val rest = (1L until daysToNextMonday.toLong())
            .map { today.plusDays(it).format(fmt) }.joinToString("; ")
        val next = (0L..6L).map { nextMonday.plusDays(it).format(fmt) }.joinToString("; ")
        return rest to next
    }

    @Test
    fun `date clause forbids the no-access refusal in every language`() = runBlocking {
        // DEVICE EVIDENCE (2026-09-29 08:34): on the text-only lane, the en
        // ask "Is the date for next Friday?" drew "I do not have access to
        // the current date..." — the model resolved the injected device date
        // as 'not its own knowledge' (TEXT_ONLY_RULES: "answer from your own
        // knowledge... never guess") and refused. The ms row escaped because
        // the computed example made the answer copyable. Precedence line
        // pinned per language: the device date is INPUT DATA, using it is
        // mandatory, and the no-access refusal is explicitly banned.
        val en = builder().buildPrompt(
            queryOverride = "What is the date for next Friday?",
            userLocale = Locale.US,
            dateRulesMode = true,
        )
        val ms = builder().buildPrompt(
            queryOverride = "Jumaat depan tarikh apa",
            userLocale = Locale("ms"),
            dateRulesMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "下个星期五是什么日期",
            userLocale = Locale("zh"),
            dateRulesMode = true,
        )
        assertTrue(en.contains("INPUT DATA supplied by the app"))
        assertTrue(en.contains("never say you lack access to the current date"))
        assertTrue(ms.contains("DATA INPUT yang dibekalkan apl"))
        assertTrue(ms.contains("tiada akses kepada tarikh semasa"))
        assertTrue(zh.contains("输入数据"))
        assertTrue(zh.contains("无法获取当前日期"))
    }

    @Test
    fun `text-only date ask replaces own-knowledge rules - refusal removed`() = runBlocking {
        // DEVICE EVIDENCE (2026-09-29, twice): under TEXT_ONLY_RULES the en
        // ask drew "I do not have access to the current date" even WITH the
        // precedence line — the "from your own knowledge... never guess"
        // framing out-ranked the date clause on the 2B model. The fix
        // REPLACES the rules block on text-only date asks: no own-knowledge
        // claim exists to out-rank.
        val en = builder().buildPrompt(
            queryOverride = "What is the date for next Friday?",
            userLocale = Locale.US,
            textOnlyMode = true,
            dateRulesMode = true,
        )
        assertTrue(en.contains("The current date is HANDED TO YOU below"))
        // The conflicting framing must be GONE, not merely out-shouted.
        assertFalse(en.contains("from your own knowledge"))
        assertFalse(en.contains("No camera image is available"))
        // The date contract still rides along.
        assertTrue(en.contains("DATE ARITHMETIC RULE"))
        assertTrue(en.contains("Today's date (from the device):"))

        // ms/zh parity of the replacement block.
        val ms = builder().buildPrompt(
            queryOverride = "Jumaat depan tarikh apa",
            userLocale = Locale("ms"),
            textOnlyMode = true,
            dateRulesMode = true,
        )
        val zh = builder().buildPrompt(
            queryOverride = "下个星期五是什么日期",
            userLocale = Locale("zh"),
            textOnlyMode = true,
            dateRulesMode = true,
        )
        assertTrue(ms.contains("DIBEKALKAN kepada anda"))
        assertTrue(zh.contains("已在下方提供给你"))

        // Non-date text-only asks keep the original rules untouched.
        val plain = builder().buildPrompt(
            queryOverride = "what is paracetamol used for?",
            userLocale = Locale.US,
            textOnlyMode = true,
        )
        assertTrue(plain.contains("from your own knowledge"))
        assertFalse(plain.contains("HANDED TO YOU"))
    }

    @Test
    fun `unsupported language falls back to english date clause`() = runBlocking {
        // Same fall-through discipline as failurePhraseClauseFor: unknown
        // languages get the English contract, never a missing rule.
        val ja = builder().buildPrompt(
            queryOverride = "来週の金曜日は何日ですか？",
            userLocale = Locale("ja"),
            dateRulesMode = true,
        )
        assertTrue(ja.contains("DATE ARITHMETIC RULE"))
        val (restEn, _) = expectedTable("en")
        assertTrue(ja.contains("Rest of this week: $restEn"))
        assertFalse(ja.contains("Baki minggu ini"))
    }

    // ── OCR grounding clause (fix #4, 2026-10-01) ─────────────────

    @Test
    fun `sparse ocr text triggers the grounding license`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "what is this",
            userLocale = Locale.US,
            ocrText = "250ml",
        )
        assertTrue(p.contains("too small or blurred to read reliably"))
        assertTrue(p.contains("a wrong name is worse than an honest description"))
    }

    @Test
    fun `dense ocr text does not carry the grounding license`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "read this label",
            userLocale = Locale.US,
            // ≥ 60 chars — a real read with plenty of ground truth.
            ocrText = "PARACETAMOL 500mg TABLETS. Contains paracetamol. Dosage: adults 1-2 tablets every 4 hours.",
        )
        assertFalse(p.contains("too small or blurred to read reliably"))
    }

    @Test
    fun `no ocr text never carries the grounding license`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "what is in front of me",
            userLocale = Locale.US,
        )
        assertFalse(p.contains("too small or blurred to read reliably"))
    }

    @Test
    fun `grounding trigger companion mirrors the inline threshold`() {
        assertTrue(DynamicPromptBuilder.companionShouldGroundOcr("250ml"))
        // No OCR ground truth → no clause (the buildPrompt branch only runs
        // inside the non-blank check; the mirror must agree).
        assertFalse(DynamicPromptBuilder.companionShouldGroundOcr(""))
        assertFalse(DynamicPromptBuilder.companionShouldGroundOcr(null))
        assertFalse(DynamicPromptBuilder.companionShouldGroundOcr("x".repeat(60)))
        // Boundary: 59 chars fires, 60 does not.
        assertTrue(DynamicPromptBuilder.companionShouldGroundOcr("x".repeat(59)))
    }

    // ── 9. E1 lean prompt class (two-budget contract) ─────────────

    @Test
    fun `lean prompt drops few-shot examples but keeps every pinned contract`() = runBlocking {
        val p = builder().buildPrompt(
            queryOverride = "what is this?",
            ocrText = "PARACETAMOL 500mg TABLETS",
            dialogueContext = "User: What is in front of me?\nVyze: A coffee mug on the desk.",
            leanMode = true,
        )
        // Examples are the lean class's whole point — out.
        assertFalse(p.contains("Examples:"))
        assertTrue(p.contains("Examples omitted for fast answering."))
        // Every load-bearing contract stays byte-identical (the lean
        // branch's own doc: mirror, anti-echo, OCR ground-truth, task).
        assertTrue(p.contains("LANGUAGE MIRRORING: Respond in the language named in the"))
        assertTrue(p.contains("NEVER repeat, echo, or quote the user's query or question"))
        assertTrue(p.contains("Begin immediately with the direct description or answer"))
        assertTrue(p.contains("Respond with the answer only"))
        assertTrue(p.contains("REMEMBER: Respond only in"))
        // Read fidelity under lean: the OCR ground-truth directive survives.
        assertTrue(p.contains("The OCR text above is the ground truth"))
        // Dialogue survives lean too — condensed, not dropped.
        assertTrue(p.contains("A coffee mug on the desk."))
    }

    @Test
    fun `lean dialogue window keeps the newest turns - not the oldest`() = runBlocking {
        // 4 turns × 2 lines = 8 lines; the lean window takes the LAST 6
        // (turns 2-4), cutting only the turn furthest from the question.
        // The old take(6) cut the other end — it kept turn 1 and dropped
        // the exchange the follow-up was about.
        val history =
            "User: turn one?\nVyze: turn one answer.\n" +
                "User: turn two?\nVyze: turn two answer.\n" +
                "User: turn three?\nVyze: turn three answer.\n" +
                "User: turn four?\nVyze: turn four answer."
        val lean = builder().buildPrompt(
            queryOverride = "and that one?",
            dialogueContext = history,
            leanMode = true,
        )
        assertTrue(lean.contains("turn four answer."))
        assertTrue(lean.contains("turn two answer."))
        assertFalse(lean.contains("turn one answer."))
        // The window is LEAN-ONLY: the rich form still renders full history.
        val rich = builder().buildPrompt(
            queryOverride = "and that one?",
            dialogueContext = history,
            leanMode = false,
        )
        assertTrue(rich.contains("turn one answer."))
    }

    @Test
    fun `lean prompt carries no memory-none marker and matches non-lean absence`() = runBlocking {
        // E1 removal: the old "MEMORY: None. Answer from the fresh frame
        // only." line was lean-only, wrong on frameless prompts, and re-
        // prefilled every turn — both classes now render nothing when no
        // memory block exists.
        val lean = builder().buildPrompt(queryOverride = "what is this?", leanMode = true)
        val rich = builder().buildPrompt(queryOverride = "what is this?", leanMode = false)
        assertFalse(lean.contains("MEMORY: None"))
        assertFalse(rich.contains("MEMORY: None"))
    }

    @Test
    fun `lean prompt is materially smaller than the rich form - budget pin`() = runBlocking {
        val rich = builder().buildPrompt(queryOverride = "what is this?", leanMode = false)
        val lean = builder().buildPrompt(queryOverride = "what is this?", leanMode = true)
        // The example/follow-up blocks are the gap; it must stay wide or
        // the lean class has quietly regressed. MEASURED at calibration
        // (2026-10-21): rich=3431 lean=2638 → gap 793. Pin at 700 so any
        // block re-entering lean (~100+ chars) trips it.
        assertTrue(
            "rich=${rich.length} lean=${lean.length} — gap collapsed",
            rich.length - lean.length >= 700,
        )
        // Class ceiling: an accidental block re-entering lean pushes past
        // this; calibrated against measured builds on first run.
        assertTrue("lean=${lean.length} exceeds class budget", lean.length <= 3_100)
    }

    // (Was missing its @Test — never ran. Activated as part of the E1
    // narration-class work: it pins the continuous path directly.)
    @Test
    fun `non-date queries never carry the date rules`() = runBlocking {
        val p = builder().buildPrompt(queryOverride = "describe this room", userLocale = Locale.US)
        assertFalse(p.contains("DATE ARITHMETIC RULE"))
        val scene = builder().buildPrompt(userLocale = Locale.US, continuousMode = true)
        assertFalse(scene.contains("DATE ARITHMETIC RULE"))
    }

    // ── 10. E1 narration class (asymmetric two-budget contract) ───

    @Test
    fun `narration class strips query-shaped weight but keeps every load-bearing contract`() = runBlocking {
        // The class owns its budget (E1): query-shaped context passed by a
        // caller is IGNORED on the narration lane, while the load-bearing
        // layers — language authority, narration contract, task line, the
        // bottom mirror — stay byte-identical to the deliberate class.
        val p = builder().buildPrompt(
            userLocale = Locale.US,
            continuousMode = true,
            // Deliberate-class context passed ON PURPOSE — must not render:
            dialogueContext = "User: What is in front of me?\nVyze: A coffee mug on the desk.",
            memoryContext = "Prior shelf with blue bins",
            brevityLevel = com.vyze.app.memory.PreferenceLearner.BrevityLevel.TERSE,
        )
        // Load-bearing layers present.
        assertTrue(p.contains("[OUTPUT LANGUAGE: English]"))
        assertTrue(p.contains("LANGUAGE MIRRORING: Respond in the language named in the"))
        assertTrue(p.contains("Instant assistant."))
        assertTrue(p.contains("Describe environment: obstacles, doors, people, text."))
        assertTrue(p.contains("REMEMBER: Respond only in"))
        // Query-shaped weight absent — each string is unique to its block.
        assertFalse("anti-echo block re-entered narration",
            p.contains("CRITICAL: NEVER repeat, echo, or quote"))
        assertFalse("learned-brevity line re-entered narration", p.contains("LENGTH:"))
        assertFalse("dialogue recall re-entered narration", p.contains("Recent conversation"))
        assertFalse("scene memory re-entered narration", p.contains("Prior scene memory"))
        // TAIL ORDER (echo fix 2026-10-26): the user-shaped task line must be
        // the LAST line of the turn — generation continues it, never the
        // REMEMBER instruction prose above it.
        assertTrue(
            "task line must follow the bottom mirror",
            p.indexOf("REMEMBER: Respond only in") <
                p.indexOf("Describe environment: obstacles, doors, people, text."),
        )
        assertTrue(
            "narration turn must end on the task line",
            p.trim().endsWith("Describe environment: obstacles, doors, people, text."),
        )
    }

    @Test
    fun `ms narration drops the failure-phrase override until a safety block quotes English`() = runBlocking {
        // Plain ambient turn: CONTINUOUS_MODE_RULES names no English
        // failure phrase → the override would explain rules that are not
        // in the prompt (~450 chars of dead weight every 4 s tick).
        val plain = builder().buildPrompt(userLocale = Locale("ms"), continuousMode = true)
        assertFalse(plain.contains("FAILURE-PHRASE OVERRIDE"))
        // Armed safety keeps mirroring parity — currency quotes English.
        val armed = builder().buildPrompt(
            userLocale = Locale("ms"),
            continuousMode = true,
            currencyMode = true,
        )
        assertTrue(armed.contains("FAILURE-PHRASE OVERRIDE"))
        assertTrue(armed.contains("Saya tidak dapat membaca nilai ini dengan jelas"))
        // The deliberate class is unchanged: the override always ships on ms.
        val deliberate = builder().buildPrompt(userLocale = Locale("ms"), queryOverride = "apa ini?")
        assertTrue(deliberate.contains("FAILURE-PHRASE OVERRIDE"))
    }

    @Test
    fun `narration class is the smallest budget - three-class pin`() = runBlocking {
        val rich = builder().buildPrompt(queryOverride = "what is this?", leanMode = false)
        val lean = builder().buildPrompt(queryOverride = "what is this?", leanMode = true)
        val narration = builder().buildPrompt(userLocale = Locale.US, continuousMode = true)
        // Narration is strictly the smallest of the three budgets...
        assertTrue(
            "narration=${narration.length} not below lean=${lean.length}",
            narration.length < lean.length,
        )
        // ...by a wide margin against the deliberate rich form. Any block
        // re-entering narration (~200-700 chars) must trip this: the exact
        // blocks are pinned by name in the strip test above; this catches
        // anything new. MEASURED at calibration (2026-10-24):
        // rich=3431 lean=2638 narration=1069 → gap 2362. Pin at 1,700 so
        // a full re-entry of any stripped block (~450 worst case) trips it.
        assertTrue(
            "rich=${rich.length} narration=${narration.length} — gap collapsed",
            rich.length - narration.length >= 1_700,
        )
        // Class ceiling: hard cap for the ambient budget in EN.
        assertTrue("narration=${narration.length} exceeds class budget", narration.length <= 1_500)
    }
}

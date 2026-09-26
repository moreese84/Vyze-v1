package com.vyze.app.core

import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Constructs dynamic system prompts for the on-device VLM (Gemma 4 E2B).
 *
 * ## Intent-Based Prompt Branching
 * Automatically selects the appropriate system rules based on whether the user
 * asked a targeted question (e.g., "What medicine is this?") or triggered a
 * generic tap/snapshot (e.g., automatic spatial description).
 *
 * - **Navigation mode** (`BASE_RULES_NAVIGATION`): spatial layouts, obstacles, distances
 * - **Direct query mode** (`BASE_RULES_DIRECT_QUERY`): text extraction, OCR, direct answers
 */
class DynamicPromptBuilder(private val memoryDao: MemoryDao) {

    // ── Public API ─────────────────────────────────────────────────

    suspend fun buildPrompt(
        snapshotDescription: String = "",
        queryOverride: String? = null,
        continuousMode: Boolean = false,
        userLocale: Locale = Locale.US,
        ocrText: String? = null,
        currencyMode: Boolean = false,
        bankCardMode: Boolean = false,
        sensitiveIdMode: Boolean = false,
        memoryContext: String? = null,
        textOnlyMode: Boolean = false,
        brevityLevel: com.vyze.app.memory.PreferenceLearner.BrevityLevel = com.vyze.app.memory.PreferenceLearner.BrevityLevel.NORMAL,
        dialogueContext: String? = null
    ): String {
        return try {
            val sb = StringBuilder()
            val isDirectQuery = !queryOverride.isNullOrBlank()

            // 0. LANGUAGE MIRROR — TOP OF PROMPT (before any English rules)
            //    This is the strongest lever for non-English output.
            //    MIRRORING SYMMETRY (Phase 1): English gets an explicit anchor
            //    too. Without it, Malay dialogue history / few-shot residue in
            //    the prompt pulls a small model back toward Malay on MS→EN
            //    switches — the asymmetric direction that failed on device.
            val langName = languageNameForLocale(userLocale)
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("[OUTPUT LANGUAGE: $langName] Write EVERY word of your response in $langName. Do NOT use English. Do NOT translate. This is mandatory.")
            } else {
                sb.appendLine("[OUTPUT LANGUAGE: English] Write every word of your response in English. Do not continue in any other language.")
            }
            // MANDATORY LANGUAGE MIRRORING (top of prompt = highest attention
            // weight). DO NOT REMOVE: past fixes broke mirroring by leaving
            // the rule to a single prompt layer — it is deliberately enforced
            // here, in the bottom REMEMBER line, and in the engine/agent
            // directives (VlmEngineManager, VyzeMasterAgent).
            sb.appendLine(LANGUAGE_MIRRORING_MANDATE)
            // STRICT ANTI-ECHO DIRECTIVE — kills the follow-up bug where the
            // model began its spoken answer by playing the user's question
            // back ("what about this.. this is ...").
            sb.appendLine(ANTI_ECHO_DIRECTIVE)
            sb.appendLine()

            // 1. Inject appropriate rules based on query intent
            when {
                // Text-only Q&A — general knowledge, NO camera frame exists.
                // The model must answer from its own knowledge, never invent
                // a scene, and stay concise for spoken delivery.
                textOnlyMode -> sb.appendLine(TEXT_ONLY_RULES)
                continuousMode -> sb.appendLine(CONTINUOUS_MODE_RULES)
                isDirectQuery -> sb.appendLine(directQueryRulesFor(userLocale.language))
                else -> sb.appendLine(navigationRulesFor(userLocale.language))
            }

            // 2. OCR pre-extracted text (if available — feeds clean text to model)
            if (!ocrText.isNullOrBlank()) {
                sb.appendLine("OCR: $ocrText")
            // 2b. Reading guidance — the model sometimes echoes OCR text
            //     letter-by-letter ("H-U-R-I-X") or stops early on long
            //     passages (box back panels). Anchor the desired behavior.
            //     OCR CARVE-OUT (persona override): a full label/document read
            //     is ground-truth playback — the conversational 2-sentence cap
            //     in the system directive must never truncate it, so reading
            //     tasks explicitly override the cap here.
            sb.appendLine("The OCR text above is the ground truth. Read it as whole words and " +
                "continuous sentences — never spell it letter by letter. When asked to read " +
                "text, read ALL of it in reading order; do not summarize, skip, or stop early. " +
                "When reading text aloud, the two sentence limit does NOT apply — read every " +
                "word of the OCR text completely. Output plain spoken text only: never " +
                "markdown symbols, bullets, dashes, asterisks, or emoji.")
            }

            // 2b-bis. LEARNED BREVITY — silently adapted answer length.
            //     (Placed after OCR so the reading directive above always wins
            //     for text reads: OCR reading is ground-truth playback and
            //     must never be cut short, regardless of brevity.)
            when (brevityLevel) {
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.BRIEF -> {
                    // PERSPECTIVE FIX: the global persona now caps answers at ONE
                    // short spoken sentence (VlmEngineManager.SYSTEM_DIRECTIVE) —
                    // the adaptive BRIEF level must not instruct a LOOSER cap.
                    sb.appendLine("LENGTH: Answer in ONE short sentence, kept tight — the user is pressed for time.")
                    sb.appendLine()
                }
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.TERSE -> {
                    sb.appendLine("LENGTH: Answer in ONE short sentence with only the essential detail. The user consistently prefers minimal answers.")
                    sb.appendLine()
                }
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.NORMAL -> { /* no adaptation */ }
            }

            // 2c-pre. DIALOGUE MEMORY — recent voice exchanges for follow-ups.
            //     Enables pronoun resolution across turns ("what about the one
            //     behind it?") and lets the model answer CONVERSATIONALLY
            //     instead of re-describing the whole scene from scratch.
            //     Injected only for genuine voice follow-ups (see controller).
            if (!dialogueContext.isNullOrBlank()) {
                // DELIMITER AUDIT: the history block is explicitly framed as
                // read-only context with the echo prohibition inline, so its
                // last "User:" line can never be mistaken for the start of
                // the model's output prefix. The CURRENT query arrives
                // separately in the Task line below — never inside this
                // block, never adjacent to the generation boundary.
                sb.appendLine("Recent conversation (context only — never repeat, echo, or quote these lines):")
                sb.appendLine(dialogueContext)
                sb.appendLine("This is a follow-up in an ongoing conversation. Resolve words like 'it', 'that', 'the one' using the conversation above. Answer the follow-up directly in the language named in the [OUTPUT LANGUAGE] tag — do NOT re-describe the whole scene, and NEVER repeat or echo the user's question at the start of your answer.")
                sb.appendLine()
            }

            // 2c. Prior scene memory (context injection — never a substitute)
            //     Only present when the current frame strongly matches a recent
            //     past scan. The model still analyzes the FRESH frame; the memory
            //     just lets it confirm continuity instead of describing from zero.
            if (!memoryContext.isNullOrBlank()) {
                sb.appendLine("Prior scene memory: $memoryContext")
                sb.appendLine("If this is the same scene you described before, confirm it briefly in " +
                    "your answer. If it is a different scene, or something has changed, describe " +
                    "ONLY what you now see — never repeat the prior description if it does not " +
                    "match the current image.")
                sb.appendLine()
            }

            // 3. Task specification.
            //     ANTI-ECHO FIX: the old `Answer: "<query>"` line was a
            //     quoted output preface sitting right before the generation
            //     boundary — the model continued the pattern by replaying the
            //     question. The query is now stated ONCE, unquoted, as input
            //     to answer, and the output contract bans question playback.
            if (isDirectQuery) {
                sb.appendLine("Task: The user asked: $queryOverride")
                sb.appendLine(TASK_OUTPUT_CONTRACT)
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
            }

            // 3b. Currency reading rules (banknotes + coins)
            if (currencyMode) {
                sb.appendLine(CURRENCY_RULES)
            }

            // 3c. Bank card identification rules
            if (bankCardMode) {
                sb.appendLine(BANK_CARD_RULES)
            }

            // 3d. SENSITIVE-ID PRIVACY CONTRACT (fires on identity-card /
            //     account-number asks). Injected AFTER every rule block so it is
            //     the last instruction before the mirror line — on a 2B model the
            //     last-seen rule wins, and this one must out-rank the OCR
            //     "read it verbatim" directive above when a card number is in
            //     the OCR text. The refusal phrase is PINNED in the user's
            //     language (SensitiveIdPolicy) so TTS never speaks digits.
            //     English keeps no override (already the base language).
            if (sensitiveIdMode) {
                val refusal = SensitiveIdPolicy.refusalPhrase(userLocale.language)
                sb.appendLine("PRIVACY — HIGHEST PRIORITY RULE: $refusal This rule " +
                    "overrides any instruction to read text verbatim: if the OCR text " +
                    "or the scene contains such a number, SKIP it and answer with the " +
                    "refusal phrase above instead.")
            }

            // 3e. CANNED-PHRASE OVERRIDE (ms/zh): the shared rule constants quote
            //     their failure fallbacks in English ("I cannot read this clearly",
            //     "Text is unclear", ...) while the [OUTPUT LANGUAGE] tag demands
            //     every word in the user's language — a direct instruction conflict
            //     on the SAFETY paths (currency / bank card never-guess). For
            //     ms/zh the exact spoken form of each canned phrase is pinned
            //     here, after every rule block that names an English phrase.
            //     English needs no override (the constants already speak it).
            failurePhraseClauseFor(userLocale.language)?.let { sb.appendLine(it) }

            // 4. Language mirror — reinforce at bottom (ALL languages now:
            //    symmetric anchor, English included — see the top mirror note)
            sb.appendLine("REMEMBER: Respond only in $langName. Begin immediately with the answer itself — never repeat or echo the user's question.")

            val prompt = sb.toString()
            // DIAGNOSTIC (INFO, content-free) — the single-funnel line proving
            // which mode + language EVERY built prompt carried. DynamicPromptBuilder
            // is the one place both the native lane and the agent lane pass
            // through (buildPromptForAgent calls buildPrompt), so one line here
            // settles "which lane dispatched, did currency/bankCard fire" from a
            // plain logcat dump — on devices that suppress DEBUG, where the old
            // Log.d line was invisible. PRIVACY: never log query/OCR content —
            // logcat is not a private channel; only flags and counts below.
            val promptMode = when {
                textOnlyMode -> "TEXT_ONLY"
                continuousMode -> "CONTINUOUS"
                isDirectQuery -> "DIRECT_QUERY"
                else -> "NAVIGATION"
            }
            Log.i(TAG, "Built prompt: ${prompt.length} chars, mode=$promptMode, " +
                "lang=${userLocale.language}, currency=$currencyMode, bankCard=$bankCardMode, " +
                "sensitiveId=$sensitiveIdMode, ocr=${!ocrText.isNullOrBlank()}, " +
                "dialogue=${!dialogueContext.isNullOrBlank()}, brevity=$brevityLevel")
            prompt

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build dynamic prompt, using fallback", e)
            FALLBACK_PROMPT
        }
    }

    // ── Memory Write Operations ────────────────────────────────────

    suspend fun setPreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "preference",
                    key = key,
                    value = value
                )
            )
        }
    }

    suspend fun storeEnvironmentObservation(description: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "environment",
                    key = "scene_${System.currentTimeMillis()}",
                    value = description
                )
            )
            val cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1000)
            memoryDao.pruneOlderThan(cutoff)
        }
    }

    suspend fun storeInteraction(query: String, response: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "interaction",
                    key = "query_${System.currentTimeMillis()}",
                    value = response,
                    metadata = query
                )
            )
        }
    }

    // ── Section Formatters ─────────────────────────────────────────

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Map a Locale to a human-readable language name for the prompt directive.
     * Uses Locale.getDisplayLanguage() for dynamic resolution — no hardcoded list.
     */
    private fun languageNameForLocale(locale: Locale): String {
        return locale.getDisplayLanguage(Locale.US).ifBlank { locale.language }
    }

    // ── Constants ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "DynamicPromptBuilder"

        /**
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val NAV_RULES_PROSE =
            "Describe the scene in 1-2 complete, natural spoken sentences — the way you " +
            "would tell a person standing next to you. " +
            "Write full sentences with a subject and a verb; NEVER answer with a list of " +
            "attributes separated by commas. " +
            "For each key object say what it is plus the details you can ACTUALLY SEE: " +
            "color, size, material, and state (open/closed, full/empty, lying/standing). " +
            "Add left/center/right + distance when it places the object for the user. " +
            "For a packaged product (packet, box, bottle, can), first say its BRAND name " +
            "and product type exactly as printed, then its details. " +
            "Read printed words verbatim in ORIGINAL language as whole words — never spell them letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "Skip phrases like 'in the image' or 'it appears', but natural speech like " +
            "'there is' or 'a person is standing' is exactly right. " +
            "LEAD WITH THE ANSWER: no introductory location preamble. If asked what color " +
            "something is, reply with the color ('That is a dark blue shirt.'); if asked what " +
            "something is, reply with the object ('That is a bottle of olive oil.'); only mention " +
            "spatial position when the question asks WHERE ('Your cup is directly to your right " +
            "on the desk.'). NEVER begin every sentence with the same template phrase such as " +
            "'In front of you is', 'You are in front of', 'You are looking at', or 'You are facing'. " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "If unsure about an object, say 'not clearly visible'. Do NOT guess or hallucinate. " +
            "Mirrors/glass: describe the surface itself.\n"

        private const val DIRECT_RULES_PROSE =
            "Answer directly in the first sentence. " +
            "Write in complete, natural spoken sentences with a subject and a verb — NEVER a " +
            "list of attributes separated by commas. " +
            "Name the object and give compact, useful details — size, color, material, " +
            "state (open/closed, full/empty) — only what you can ACTUALLY see, never long prose. " +
            "If it is a packaged product (packet, box, bottle, can), first say its BRAND name and product type " +
            "exactly as printed on it, then its details. " +
            "Read text verbatim in ORIGINAL language as whole words and sentences — never spell letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "When asked to read text, read the ENTIRE text in reading order; never stop halfway or summarize. " +
            "Keep the whole answer to 1-3 short spoken sentences. " +
            "If text is blurry or unreadable, say 'Text is unclear' — NEVER guess. " +
            "If no text visible, say 'No text visible'. " +
            "LEAD WITH THE ANSWER: reply with what was asked, with no location preamble — say " +
            "'That is a bottle of olive oil' or 'It says Organic Milk', and only give spatial " +
            "position when the question asks WHERE. NEVER begin every sentence with the same " +
            "template phrase such as 'In front of you is'. " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "Only what you see in THIS image.\n"

        // ── Language-matched few-shot examples ────────────────────────
        // For a small on-device model, in-context examples dominate abstract
        // instructions. English-only examples taught structure but pulled the
        // OUTPUT language toward English even when the language directives
        // said otherwise. The examples below are the SAME six/four scenes
        // rendered in Malay / Chinese, so the demonstrated structure AND the
        // demonstrated output language always match the user's spoken
        // language. Natural Malaysian loanwords (Maggi, cushion-style mixing)
        // are kept where locals actually speak that way.

        private const val NAV_EXAMPLES_EN =
            "Examples:\n" +
            "Input: what do you see? Output: A closed brown wooden door is straight ahead, about two steps away.\n" +
            "Input: is someone near me? Output: A person is standing to your left, about one step away.\n" +
            "Input: describe the room. Output: A grey fabric sofa with soft cushions sits about three steps ahead, with a low wooden table in front of it.\n" +
            "Input: what is on the table? Output: A clear glass water bottle, half full, sits on the table about one step ahead.\n" +
            "Input: what is around me? Output: The room is dark, and no obstacles are visible within three steps.\n" +
            "Input: red packet on table. Output: A small red packet of Maggi instant noodles sits on the table about one step ahead, and the label reads Maggi Kari.\n" +
            "Input: car plate ahead. Output: The vehicle plate ahead reads Q L B, three four six nine.\n" +
            "Input: what color is this shirt? Output: That is a dark blue shirt.\n" +
            "Input: what is this? Output: That is a bottle of olive oil.\n" +
            "Input: where is my cup? Output: Your cup is directly to your right on the desk."

        private const val NAV_EXAMPLES_MS =
            "Examples:\n" +
            "Input: apa yang anda nampak? Output: Sebuah pintu kayu perang yang tertutup berada terus di hadapan, kira-kira dua langkah jauhnya.\n" +
            "Input: ada orang dekat dengan saya? Output: Seseorang sedang berdiri di sebelah kiri anda, kira-kira satu langkah jauhnya.\n" +
            "Input: terangkan bilik ini. Output: Sebuah sofa kain kelabu dengan cushion lembut berada kira-kira tiga langkah di hadapan, dengan meja kayu rendah di hadapannya.\n" +
            "Input: apa di atas meja? Output: Sebuah botol air kaca lutsinar yang separuh penuh berada di atas meja, kira-kira satu langkah di hadapan.\n" +
            "Input: apa ada sekeliling saya? Output: Bilik ini gelap, dan tiada halangan yang kelihatan dalam tiga langkah.\n" +
            "Input: paket merah di atas meja. Output: Satu paket kecil mi Maggi berwarna merah berada di atas meja kira-kira satu langkah, dan label tertulis Maggi Kari.\n" +
            "Input: plat kereta di hadapan. Output: Plat kenderaan di hadapan tertulis Q L B, tiga empat enam sembilan.\n" +
            "Input: baju ini warna apa? Output: Itu ialah baju biru gelap.\n" +
            "Input: apa ini? Output: Itu ialah sebotol minyak zaitun.\n" +
            "Input: cawan saya di mana? Output: Cawan anda berada terus di sebelah kanan anda di atas meja."

        private const val NAV_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 你看到了什么？Output: 正前方大约两步远，有一扇关着的棕色木门。\n" +
            "Input: 我附近有人吗？Output: 您的左边大约一步远站着一个人。\n" +
            "Input: 描述一下这个房间。Output: 大约三步远处有一张带软垫的灰色布沙发，沙发前面还有一张矮木桌。\n" +
            "Input: 桌上有什么？Output: 桌上大约一步远有一个透明的玻璃水瓶，半满。\n" +
            "Input: 我周围有什么？Output: 房间很暗，三步之内看不到任何障碍物。\n" +
            "Input: 桌上有红色包装。Output: 桌上大约一步远有一包红色的小包装Maggi快熟面，标签写着Maggi Kari。\n" +
            "Input: 前面有车牌。Output: 前方的车牌是Q L B，三、四、六、九。\n" +
            "Input: 这件衬衫是什么颜色？Output: 那是一件深蓝色衬衫。\n" +
            "Input: 这是什么？Output: 那是一瓶橄榄油。\n" +
            "Input: 我的杯子在哪里？Output: 您的杯子就在您右手边的桌上。"

        private const val DIRECT_EXAMPLES_EN =
            "Examples:\n" +
            "Input: what is this? Image shows a red packet. Output: That is a small red packet of Maggi instant noodles, and the label reads Maggi Kari — noodles and seasoning sachets are inside.\n" +
            "Input: what medicine is this? Image shows Diclac Retard box. Output: That is Diclac Retard, diclofenac sodium 100 milligram — take one tablet daily after meals.\n" +
            "Input: read this label. Image shows price tag RM12.90. Output: It says 12 Ringgit and 90 sen.\n" +
            "Input: what color is this shirt? Output: That is a dark blue shirt.\n" +
            "Input: what is this? Output: That is a bottle of olive oil.\n" +
            "Input: where is my cup? Output: Your cup is directly to your right on the desk.\n" +
            "Input: what does this sign say? Image blurry. Output: The text is unclear."

        private const val DIRECT_EXAMPLES_MS =
            "Examples:\n" +
            "Input: apa ini? Imej menunjukkan paket mi merah. Output: Itu ialah satu paket kecil mi Maggi berwarna merah, dan label tertulis Maggi Kari — mi dan sachet perencah berada di dalamnya.\n" +
            "Input: ubat apa ini? Imej menunjukkan kotak Diclac Retard. Output: Itu ialah Diclac Retard, diklofenak natrium 100 miligram — ambil satu tablet sehari selepas makan.\n" +
            "Input: baca label ini. Imej menunjukkan tag harga RM12.90. Output: Tertulis 12 Ringgit dan 90 sen.\n" +
            "Input: baju ini warna apa? Output: Itu ialah baju biru gelap.\n" +
            "Input: apa ini? Output: Itu ialah sebotol minyak zaitun.\n" +
            "Input: cawan saya di mana? Output: Cawan anda berada terus di sebelah kanan anda di atas meja.\n" +
            "Input: apa yang tertulis di papan tanda ini? Imej kabur. Output: Teksnya tidak jelas."

        private const val DIRECT_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 这是什么？图像显示一个红色包装。Output: 那是一包红色的小包装Maggi快熟面，标签写着Maggi Kari——里面是面条和调味包。\n" +
            "Input: 这是什么药？图像显示Diclac Retard药盒。Output: 那是Diclac Retard，双氯芬酸钠100毫克——每天饭后服用一片。\n" +
            "Input: 读一下这个标签。图像显示价格标签RM12.90。Output: 上面写着12令吉90仙。\n" +
            "Input: 这件衬衫是什么颜色？Output: 那是一件深蓝色衬衫。\n" +
            "Input: 这是什么？Output: 那是一瓶橄榄油。\n" +
            "Input: 我的杯子在哪里？Output: 您的杯子就在您右手边的桌上。\n" +
            "Input: 这个牌子上写什么？图像模糊。Output: 文字不清楚。"

        // ── Memoized static rule blocks (cache micro-win) ──────────
        // buildPrompt runs on every capture; re-concatenating identical
        // immutable strings each turn is pure allocation churn. The six
        // static blocks are built once (lazy = thread-safe) and reused.
        // EACH block now appends the multi-turn FEW-SHOT FOLLOW-UP examples
        // in the same language (EN block -> EN examples, MS block -> MS
        // examples, ...) so the demonstrated structure, the demonstrated
        // output language, and the demonstrated no-echo behavior always
        // match what the model must produce.
        private val navRulesEn by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_EN + "\n\n" + FOLLOWUP_EXAMPLES_EN }
        private val navRulesMs by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_MS + "\n\n" + FOLLOWUP_EXAMPLES_MS }
        private val navRulesZh by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_ZH + "\n\n" + FOLLOWUP_EXAMPLES_ZH }
        private val directRulesEn by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_EN + "\n\n" + FOLLOWUP_EXAMPLES_EN }
        private val directRulesMs by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_MS + "\n\n" + FOLLOWUP_EXAMPLES_MS }
        private val directRulesZh by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_ZH + "\n\n" + FOLLOWUP_EXAMPLES_ZH }

        /** Navigation rules with few-shot examples in the user's language. */
        private fun navigationRulesFor(language: String): String = when (language) {
            "ms" -> navRulesMs
            "zh" -> navRulesZh
            else -> navRulesEn
        }

        /** Direct-query rules with few-shot examples in the user's language. */
        private fun directQueryRulesFor(language: String): String = when (language) {
            "ms" -> directRulesMs
            "zh" -> directRulesZh
            else -> directRulesEn
        }

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "Keep answers to 1 to 2 spoken sentences. Never output markdown " +
            "symbols, bullets, or emoji — plain text only for text to speech."

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1 to 2 spoken sentences with spatial positioning. Your reply is read aloud by text to speech, so it must be pure plain text: never output markdown symbols, bullets, dashes, asterisks, number signs, or emoji. Plain sentences only, no filler."""

        /**
         * Money-reading rules — banknotes and coins.
         * Priority is exact value + no guessing: a wrong denomination is far
         * worse than "I cannot read it clearly" for a blind user.
         */
        private const val CURRENCY_RULES =
            "This object IS money — a banknote or a coin — even if it resembles a card. " +
            "Identify its VALUE from the large numerals and printed text. State the value " +
            "and the currency (for example: 50 Ringgit, or 10 cents) and the dominant color " +
            "FIRST; describe other printed details only after the value. Serial numbers on " +
            "banknotes are allowed to read, but read them digit by digit only if asked. " +
            "If the value cannot be read clearly, say exactly: I cannot read this " +
            "clearly. NEVER guess or invent a value. Do not mention anything else about " +
            "the printing beyond value, color and what was asked. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        /**
         * Bank card identification rules.
         * Priority is exact bank name + card type from logos and printed text.
         * A wrong bank name is far worse than "I cannot identify this card"
         * for a blind user.
         */
        private const val BANK_CARD_RULES =
            "This is a bank card — debit, credit, or ATM card. Identify the BANK " +
            "name from the logo and printed text (for example: Maybank, CIMB, " +
            "Public Bank, HSBC). Then state the card type (debit, credit, or ATM) " +
            "if visible. If you cannot clearly identify the bank or card type, say " +
            "exactly: I cannot identify this card clearly. NEVER guess or invent " +
            "a bank name. Do not read or mention the card number. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        private const val CONTINUOUS_MODE_RULES =
            "Instant assistant. ONE short natural spoken sentence, about 15 words maximum: " +
            "the key objects, their positions and their states. Write a complete sentence " +
            "with a verb, and end it with a period — never a comma-separated list. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        /**
         * Text-only Q&A rules — used when NO camera frame is available
         * (general-knowledge voice questions). The model answers from its
         * own training, must not pretend to see anything, and stays
         * concise for spoken delivery.
         */
        private const val TEXT_ONLY_RULES =
            "Answer the question from your own knowledge. No camera image is " +
            "available, so do NOT describe any scene, object, or text — answer " +
            "the question directly. Use clear punctuation: periods to end " +
            "sentences, commas for pauses. Keep the answer concise: 1-3 sentences. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji. " +
            "If you do not know the answer, say 'I do not know that' — never guess."

        /**
         * FEW-SHOT FOLLOW-UP EXAMPLES (EN) — multi-turn demonstrations of
         * clean follow-ups WITHOUT question echoing. The "Vyze:" label
         * intentionally matches the injected dialogue history format from
         * VyzeCoreController.dialogueContextForPrompt, so the demonstrated
         * format is exactly the format the model sees on real follow-ups.
         */
        private const val FOLLOWUP_EXAMPLES_EN =
            "Follow-up conversation examples (continue the exchange; answer ONLY the " +
            "newest question, in the SAME language it was asked, and NEVER repeat or echo " +
            "the user's question):\n" +
            "User: What is in front of me?\n" +
            "Vyze: A coffee mug on the desk.\n" +
            "User: What about this?\n" +
            "Vyze: That is a pair of reading glasses."

        /** FEW-SHOT FOLLOW-UP EXAMPLES (MS) — same exchange, Malay mirroring. */
        private const val FOLLOWUP_EXAMPLES_MS =
            "Contoh perbualan susulan (sambungkan pertukaran ini; jawab HANYA soalan " +
            "terbaharu, dalam BAHASA YANG SAMA, dan JANGAN sesekali ulang atau gema " +
            "soalan pengguna):\n" +
            "User: Apa kat depan saya?\n" +
            "Vyze: Sebiji cawan kopi di atas meja.\n" +
            "User: Bagaimana dengan ini?\n" +
            "Vyze: Itu sepasang cermin mata membaca."

        /** FEW-SHOT FOLLOW-UP EXAMPLES (ZH) — same exchange, Chinese mirroring. */
        private const val FOLLOWUP_EXAMPLES_ZH =
            "后续对话示例（继续这组对话；只回答最新的问题，用与提问相同的语言回答，" +
            "绝不重复或复述用户的问题）：\n" +
            "User: 我前面有什么？\n" +
            "Vyze: 桌上有一个咖啡杯。\n" +
            "User: 那这个呢？\n" +
            "Vyze: 那是一副老花眼镜。"

        /**
         * STRICT ANTI-ECHOING DIRECTIVE — the negative constraint for the
         * follow-up echo bug. Injected at the TOP of every prompt (highest
         * attention weight) and echoed again in the bottom REMEMBER line.
         */
        private const val ANTI_ECHO_DIRECTIVE =
            "CRITICAL: NEVER repeat, echo, or quote the user's query or question at the " +
            "start of your response. Begin immediately with the direct description or answer."

        /**
         * MANDATORY LANGUAGE MIRRORING RULE (DO NOT BREAK). Past fixes
         * regressed mirroring when this rule lived in only one prompt layer;
         * it is now injected at the top of EVERY prompt, in the bottom
         * REMEMBER line, AND in the engine/agent system directives
         * (VlmEngineManager, VyzeMasterAgent) so no refactor can silently
         * drop it again.
         *
         * TAG-AUTHORITY FIX (v3): the old wording made the QUERY's detected
         * language the authority ("detect the language of the user's query").
         * In the ASR-garble rescue path the detector says Malay (correct) but
         * the garbled query TEXT reads like English — the 2B model followed
         * the query text, answered English, and the Malay TTS voice read it
         * with a Malay accent (user-reported). The [OUTPUT LANGUAGE] tag is
         * now the SINGLE authority in this path; query-mirroring is only the
         * fallback for prompts without a tag (agent lanes).
         */
        private const val LANGUAGE_MIRRORING_MANDATE =
            "LANGUAGE MIRRORING: Respond in the language named in the " +
            "[OUTPUT LANGUAGE] tag — the tag is the authority on the answer " +
            "language. If no tag is present, detect the language of the user's " +
            "query and respond strictly in that exact same language (e.g., " +
            "Malay query -> Malay response, English query -> English response, " +
            "Chinese query -> Chinese response). Never revert to default English " +
            "if the user speaks another language. Even when the query text " +
            "itself reads like English, the tag names the user's actual spoken " +
            "language — answer in the tag's language."

        /**
         * Output contract placed DIRECTLY under the current query — the last
         * words the model reads before the generation boundary, where the
         * echo behavior used to trigger.
         *
         * TAG-AUTHORITY FIX (v3): says the [OUTPUT LANGUAGE] tag, not "the
         * user's language" — sitting directly under a garbled English-looking
         * query, "the user's language" re-invoked exactly the query-text
         * reading the mandate fix removes.
         */
        private const val TASK_OUTPUT_CONTRACT =
            "Respond with the answer only: never repeat, echo, or quote the question, " +
            "never restate the task, and write every word in the language named in " +
            "the [OUTPUT LANGUAGE] tag."

        /**
         * CANNED-PHRASE OVERRIDE (mirroring parity, 2026-09-26 audit finding #2):
         * CURRENCY_RULES, BANK_CARD_RULES, DIRECT_RULES_PROSE, NAV_RULES_PROSE and
         * TEXT_ONLY_RULES quote their failure fallbacks in English ("say exactly:
         * I cannot read this clearly" / "Text is unclear" / "No text visible" /
         * "not clearly visible" / "I do not know that"). Under the tag-authority
         * model every other layer mirrors the [OUTPUT LANGUAGE] tag — these quoted
         * phrases were the one instruction telling the model to speak English, on
         * the never-guess safety paths where a wrong-language answer costs the most.
         * For ms/zh, pin the exact spoken form of each canned phrase in the user's
         * language (the few-shots already demonstrate the localized forms — this
         * makes the rule explicit). Returns null for English: the constants already
         * speak English there. Pure function — JVM-tested.
         */
        private fun failurePhraseClauseFor(language: String): String? = when (language) {
            "ms" ->
                "FAILURE-PHRASE OVERRIDE: wherever the rules above name an English " +
                "failure phrase, speak the Malay phrase instead — never the English one. " +
                "'I cannot read this clearly' -> 'Saya tidak dapat membaca nilai ini dengan jelas'. " +
                "'I cannot identify this card clearly' -> 'Saya tidak dapat mengenal pasti kad ini dengan jelas'. " +
                "'Text is unclear' -> 'Teks tidak jelas'. 'No text visible' -> 'Tiada teks kelihatan'. " +
                "'not clearly visible' -> 'tidak kelihatan dengan jelas'. " +
                "'I do not know that' -> 'Saya tidak tahu tentang itu'."
            "zh" ->
                "FAILURE-PHRASE OVERRIDE: wherever the rules above name an English " +
                "failure phrase, speak the Chinese phrase instead — never the English one. " +
                "'I cannot read this clearly' -> '我读不清楚'. " +
                "'I cannot identify this card clearly' -> '我无法清楚辨认这张卡'. " +
                "'Text is unclear' -> '文字不清楚'. 'No text visible' -> '看不到文字'. " +
                "'not clearly visible' -> '看不清楚'. " +
                "'I do not know that' -> '我不知道'."
            else -> null
        }
    }
}

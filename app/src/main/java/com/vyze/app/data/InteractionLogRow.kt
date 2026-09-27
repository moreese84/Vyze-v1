package com.vyze.app.data

/**
 * One harness-compatible row of the debug-only interaction export (Phase 1
 * of the Jev distillation pipeline).
 *
 * PURE data mapping — no I/O, no Android, no network. The only writer is
 * the debug-sourceSet [com.vyze.app.debug.InteractionLogExporter]; the
 * release variant never references this class, so R8 strips it from the
 * shipped APK. The release binary stays 100% offline regardless.
 *
 * The schema mirrors tools/jev_harness corpus rows so the exported JSONL
 * feeds `python -m tools.jev_harness audit --corpus ...` directly.
 *
 * PRIVACY: the FULL built VLM prompt is deliberately never emitted — the
 * built prompt embeds memory context, OCR text, and scene descriptions
 * (bank cards, medication labels). Only the extracted user query and the
 * model's spoken answer are exported.
 *
 * v2 additions (device-audit round 1):
 *  - [lane] distinguishes spoken queries from screen-tap queries. Tap
 *    queries carry no spoken language, so audits must not grade their
 *    language mirroring.
 *  - [inferLanguage] gives every row an inferred language ("en"/"ms"/"zh"/
 *    "unknown") so audit reports can show real per-language compliance
 *    instead of a single meaningless "unknown" bucket.
 */
data class InteractionLogRow(
    val id: String,
    val lang: String,
    val query: String,
    val answer: String,
    val previous: String?,
    val ts: Long,
    val source: String,
    val lane: String = LANE_VOICE,

    /**
     * Suspect marks (implicit failure detection, SuspectMarker) — null when
     * clean. Exported as the corpus `note` field so a shared row arrives
     * PRE-LABELED AS FAILURE evidence for distillation gating. Also the
     * provenance field the device fixture integrity test requires.
     */
    val note: String? = null,
) {
    /** JSON string with proper escaping; fields ordered for grep-ability. */
    fun toJson(): String {
        val sb = StringBuilder(128)
        sb.append('{')
        sb.append("\"id\": ").append(quote(id)).append(", ")
        sb.append("\"lang\": ").append(quote(lang)).append(", ")
        sb.append("\"query\": ").append(quote(query)).append(", ")
        sb.append("\"previous\": ").append(if (previous == null) "null" else quote(previous)).append(", ")
        sb.append("\"answer\": ").append(quote(answer)).append(", ")
        sb.append("\"ts\": ").append(ts).append(", ")
        sb.append("\"source\": ").append(quote(source)).append(", ")
        sb.append("\"lane\": ").append(quote(lane))
        if (!note.isNullOrBlank()) {
            sb.append(", \"note\": ").append(quote(note))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        /**
         * Marker the prompt builder wraps the raw spoken query in
         * (DynamicPromptBuilder: "Task: The user asked: <query>").
         */
        const val QUERY_MARKER = "Task: The user asked: "

        /** Spoken-query lane (speech-driven VLM / text queries). */
        const val LANE_VOICE = "voice"

        /** Screen-tap lane (query is a tap-position description — no spoken language). */
        const val LANE_TAP = "tap"

        /**
         * Classify a raw query into [LANE_VOICE] or [LANE_TAP].
         *
         * CameraFragment submits tap queries as "User tapped at position
         * (x, y)…" — they describe WHERE the user touched, not what they
         * asked. Language-mirror auditing is meaningless for them (v2
         * device audit: 4 of 13 rows were tap rows skewing compliance).
         */
        fun laneOf(query: String): String =
            if (query.startsWith("User tapped at position")) LANE_TAP else LANE_VOICE

        /**
         * Infer the language of a raw spoken query: "en", "ms", "zh", or
         * "unknown" for uninterpretable/garbled text.
         *
         * PURE and JVM-testable — a faithful port of the scoring core of
         * MainActivity.detectLocaleFromText (Signals A, A2, A3, B, C, D)
         * including the v2 ASR-garble aliases and fused-word evidence.
         * Deliberately NOT shared as a runtime dependency with the app
         * detector (different failure semantics): a wrong inference here
         * only mislabels an audit row; a wrong detection there breaks the
         * language mirror. Keep the two word banks in sync manually — the
         * InteractionLogRowTest pins the cases that matter.
         *
         * Returns "unknown" (never a device-locale guess) — the exporter
         * has no meaningful device-locale fallback for transcripts, and
         * auditors should see unknown rather than a lie.
         */
        fun inferLanguage(query: String): String {
            if (query.isBlank()) return "unknown"
            val lower = query.lowercase()
            val words = lower.split(Regex("\\s+"))

            // ── CJK (Chinese) detection: Unicode ranges ─────────────
            var cjkCount = 0
            var totalLetters = 0
            for (ch in query) {
                if (ch.isLetter()) totalLetters++
                val cp = ch.code
                if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF) {
                    cjkCount++
                }
            }
            if (totalLetters > 0 && cjkCount.toFloat() / totalLetters > 0.3f) return "zh"

            // ── Malay multi-signal scoring (mirrors the app detector) ──
            val malayFunctionWords = setOf(
                "saya", "kami", "kita", "anda", "kamu", "mereka",
                "tidak", "tak", "bukan", "jangan", "belum",
                "dan", "atau", "tapi", "kerana", "sebab",
                "ini", "itu", "sini", "situ", "sono",
                "yang", "adalah", "ialah",
                "di", "ke", "dari", "dengan", "untuk",
                "dalam", "atas", "bawah", "antara", "sebelum",
                "sudah", "sedang", "akan", "baru", "lagi",
                "boleh", "mahu", "nak", "perlu", "mesti",
                "apa", "siapa", "mana", "kenapa",
                "bila", "berapa", "mengapa",
                "ada", "depan", "belakang",
                "pula",
            )
            val malayGarbleAliases = mapOf(
                "appa" to "apa",
                "apah" to "apa",
            )
            val malaySuffixes = listOf("kan", "an", "i", "lah", "kah", "tah", "nya", "pun")
            val malayStrongPatterns = listOf(
                "baca", "tolong", "analisis", "tengok", "tunjuk", "tunjukkan",
                "lihat", "cari", "dengar", "cakap", "bagitahu",
                "hasil", "gambar", "kamera", "warna", "harga", "duit",
            )
            val malayPatterns = listOf(
                "selamat", "terima", "kasih",
                "macam", "bahasa", "malaysia",
                "rumah", "makan", "minum", "jalan",
                "kenal", "paham", "faham",
                "pergi", "datang", "balik",
                "besar", "kecil", "cantik", "bagus",
                "panas", "sejuk", "hujan", "cerah",
                "hari", "malam", "pagi", "petang",
                "orang", "anak", "ibu", "bapa",
                "objek", "benda", "senario", "teks",
                "semua", "sekarang", "sikit", "banyak", "sama", "ada",
            )

            var malayScore = 0

            // Signal A: function words (+2 each)
            for (word in words) {
                val cleaned = word.replace(Regex("[^a-z]"), "")
                if (cleaned in malayFunctionWords) malayScore += 2
            }
            // Signal A2: ASR-garble aliases (+2 each)
            for (word in words) {
                val cleaned = word.replace(Regex("[^a-z]"), "")
                if (malayGarbleAliases.containsKey(cleaned)) malayScore += 2
            }
            // Signal A3: fused-word evidence (+1, capped)
            if (malayScore < 4) {
                for (word in words) {
                    val cleaned = word.replace(Regex("[^a-z]"), "")
                    if (cleaned.length < 5) continue
                    val fused = malayFunctionWords.any { fn ->
                        fn.length >= 3 && cleaned != fn && cleaned.contains(fn)
                    }
                    if (fused) {
                        malayScore += 1
                        if (malayScore >= 4) break
                    }
                }
            }
            // Signal B: morphological suffixes (+1)
            for (word in words) {
                val cleaned = word.replace(Regex("[^a-z]"), "")
                if (malaySuffixes.any { cleaned.length >= 4 && cleaned.endsWith(it) }) malayScore += 1
            }
            // Signal C: strong patterns (+2) / descriptive patterns (+1)
            for (word in words) {
                val cleaned = word.replace(Regex("[^a-z]"), "")
                when {
                    cleaned in malayStrongPatterns -> malayScore += 2
                    cleaned in malayPatterns -> malayScore += 1
                }
            }
            // Signal D: reduplication (+2)
            if (Regex("(\\b[a-z]{2,})-([a-z]{2,})\\b").containsMatchIn(lower)) malayScore += 2

            if (malayScore >= 2) return "ms"

            // Latin-script text with no Malay signal is English (Vyze's three
            // supported languages make this unambiguous); total absence of
            // letters (numbers, symbols) is unknowable.
            return if (words.any { it.isNotBlank() && it.first().isLetter() }) "en" else "unknown"
        }

        /**
         * Extract the raw spoken query from a built VLM prompt. Returns
         * null when the prompt carries no query marker (e.g. gesture
         * scene-describes) — such rows are useless for router distillation
         * and are deliberately skipped by the exporter.
         */
        fun extractQuery(builtPrompt: String): String? {
            val idx = builtPrompt.indexOf(QUERY_MARKER)
            if (idx < 0) return null
            val start = idx + QUERY_MARKER.length
            if (start > builtPrompt.length) return null
            val end = builtPrompt.indexOf('\n', start)
                .let { if (it < 0) builtPrompt.length else it }
            val query = builtPrompt.substring(start, end).trim()
            return query.ifEmpty { null }
        }
    }
}

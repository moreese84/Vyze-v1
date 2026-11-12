package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the text-only route decision
 * ([VyzeCoreController.Companion.companionIsTextOnlyQuery]) and the P1a
 * date detector — pure functions, no Android.
 *
 * The date branch (2026-09-29) routes calendar/date asks WITHOUT a visual
 * anchor to the text-only lane. Device evidence: date asks previously fell
 * through to the camera lane — "Analyzing scene..." played for a question
 * that needs no camera, and the garbled "Jubarat depan tarikh apa" row was
 * answered with the date READ OFF A LAPTOP SCREEN.
 */
class VyzeCoreControllerRouteTest {

    // ── Date asks route text-only (the fix) ──────────────────────────

    @Test
    fun `clean date asks route text-only`() {
        // Verbatim from the 2026-09-29 device session rows.
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("What is the date for next Friday"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("Next Friday"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("Jumaat depan tarikh apa"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("berapa hari lagi sampai raya"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("下個星期五是什麼日期"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("下个星期五是什么日期"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("what day is it today"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("esok hari apa"))
    }

    // ── Number-reading asks route to the read lane (fix #2, 2026-09-30) ──

    @Test
    fun `number and unit-reading asks route to the read lane`() {
        // ir_114 (2026-09-22): "how many milligram potassium" carried no read
        // keyword and fell to the scene lane — 256px, NO OCR pre-pass — so the
        // printed milligram value was unreadable ("bottle of water with
        // mineral content listed on the label"). Unit-value asks are reads.
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("how many milligram potassium"))
        // ms unit form (en "milligram" existed nowhere before this fix).
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("berapa miligram garam ini"))
        // zh number asks: the zh banks had no "number" form at all (en
        // "number" / ms "nombor" already existed) — 卡号/卡號 also pull the
        // bank-card prompt rules via BANK_CARD_KEYWORDS.
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("卡号是多少"))
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("這張卡的卡號是什麼"))
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("上面写的号码是什么"))
        // Regression pins: forms that already routed must keep routing.
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("read the card number"))
        assertTrue(VyzeCoreController.companionIsTextExtractionQuery("berapa nombor kad ini"))
    }

    @Test
    fun `scene and currency asks stay off the read lane`() {
        assertFalse(VyzeCoreController.companionIsTextExtractionQuery("what is in front of me"))
        assertFalse(VyzeCoreController.companionIsTextExtractionQuery("what color is this shirt"))
        assertFalse(VyzeCoreController.companionIsTextExtractionQuery("apa kat depan saya"))
        // Currency stays on the analyzer-frame OCR path by design: banknote
        // denomination is barcode+vision (frozen fixture e6 = VLM_VOICE_QUERY
        // for the seed row) — only the TEXT bank drives the full-res still.
        assertFalse(VyzeCoreController.companionIsTextExtractionQuery("berapa nilai duit ini"))
    }

    @Test
    fun `date ask with garbled asr still routes text-only`() {
        // The garbled row from 07:55:23 — "Jumaat" garbled to "Jubarat",
        // but the "tarikh" trigger survived. Text-only is the right lane:
        // the model should speak the device-computed example, not read
        // whatever date happens to be visible on a screen.
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("Jubarat depan tarikh apa"))
    }

    // ── Date asks WITH a visual anchor stay on camera ────────────────

    @Test
    fun `date asks anchored to scene objects stay camera`() {
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what date is on this receipt"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("read the date on the calendar"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("tarikh atas surat ini"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("tarikh kat label pakej ni"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("屏幕上的日期是多少"))
    }

    // ── Existing text-only behavior is preserved (regression) ────────

    @Test
    fun `knowledge questions still route text-only`() {
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("what is paracetamol used for"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("apa itu paracetamol"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("why is the sky blue"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("CIA 秘密组织是什么意义"))
    }

    @Test
    fun `scene-pointing knowledge forms still route camera`() {
        // "what is" marker but scene word present — camera must win.
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what is this"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("apa itu yang saya pegang"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what do you see in front of me"))
    }

    // ── Scene-referent guard (2026-10-21, device-evidenced) ───────────

    @Test
    fun `pronoun follow-ups about visible referents route camera`() {
        // DEVICE EVIDENCE (10:21 session): both "what is he doing" queries
        // matched knowledge marker "what is" with no scene word, went down
        // the text-only lane, and answered from stale dialogue history — the
        // same verbatim "That is a guy." twice, no fresh frame. An action
        // question about people/animals can only be answered from a frame.
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what is he doing"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what is she wearing"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what does he want"))
        // zh: carries marker 是什么 + the scene pronoun 他.
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("他是什么意思"))
        // Action predicate with NO pronoun — the exclude-list half of the fix.
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what is the bird doing"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what is the man holding"))
    }

    @Test
    fun `pronoun guard word boundaries never trip ordinary knowledge asks`() {
        // Plain contains would match "he" in "the"/"sherbet" and drag every
        // everyday knowledge ask to the camera — boundaries prevent it, and
        // these pins keep the text-only lane working for real knowledge asks.
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("what is the weather tomorrow"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("what is sherbet made of"))
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("why is the sky blue"))
    }

    @Test
    fun `non-date pointing questions stay camera`() {
        // The fix must not widen the route: pure pointing asks were and
        // remain camera queries.
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("what about this"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("apa ini"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("read this label"))
    }

    @Test
    fun `simplified pointing marker routes text-only - pre-existing quirk, pinned`() {
        // PRE-EXISTING marker-path behavior, NOT introduced by the date
        // branch: the SIMPLIFIED form contains the knowledge marker 是什么
        // and none of the scene-exclusion words (这个 never appears in it),
        // so it routes text-only. The TRADITIONAL form the ASR usually
        // emits (這是什麼) does NOT contain the simplified marker and stays
        // camera — which is why device sessions never showed this lane
        // flip. Candidate for the same visual-anchor parity later; pinned
        // here so the behavior is visible, not silently changed.
        assertTrue(VyzeCoreController.companionIsTextOnlyQuery("这是什么"))
        assertFalse(VyzeCoreController.companionIsTextOnlyQuery("這是什麼"))
    }

    // ── Date-ask recall skip (same predicate as the controller) ─────

    @Test
    fun `date asks skip text recall - poison-channel guard`() {
        // DEVICE EVIDENCE (2026-09-29 08:34): the crash log showed the WRONG
        // pre-fix answer ("Jumaat depan ialah 25 Oktober 2026") re-injected
        // as recall context into a later correct date ask. The controller
        // skips recallByText when companionHitsDateQuery fires — these pins
        // guard the predicate that gates that skip across languages.
        val dateAsks = listOf(
            "Jumaat depan tarikh apa",
            "What is the date for next Friday",
            "berapa hari lagi sampai raya",
            "下个星期五是什么日期",
        )
        for (q in dateAsks) {
            assertTrue("recall skip predicate failed for: $q",
                VyzeCoreController.companionHitsDateQuery(q))
        }
        // Non-date asks keep recall: "where are my keys" must still surface
        // the last scene that showed keys.
        assertFalse(VyzeCoreController.companionHitsDateQuery("where are my keys"))
    }

    // ── Date detector pins ───────────────────────────────────────────

    @Test
    fun `date detector fires on triggers and never on null or blank`() {
        assertTrue(VyzeCoreController.companionHitsDateQuery("Next Friday"))
        assertTrue(VyzeCoreController.companionHitsDateQuery("berapa hari lagi"))
        assertFalse(VyzeCoreController.companionHitsDateQuery(null))
        assertFalse(VyzeCoreController.companionHitsDateQuery(""))
        assertFalse(VyzeCoreController.companionHitsDateQuery("hello"))
    }

    // ── Text-vs-object refusal swap (live 2026-11-07) ───────────────

    @Test
    fun `text refusal on a non-read query swaps to the object refusal`() {
        // "what about the one behind?" answered with text status nobody
        // asked about — the ear must hear the object refusal instead.
        assertTrue(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Text is unclear.", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
        assertTrue(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Teksnya tidak jelas.", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
        assertTrue(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "文字不清楚。", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
    }

    @Test
    fun `explicit reads and money-card lanes keep their own refusals`() {
        // A text refusal IS the honest answer to a read question.
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Text is unclear.", textReadActive = true,
                currencyActive = false, bankCardActive = false
            )
        )
        // Currency/card lanes own pinned never-guess contracts — never touched.
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Text is unclear.", textReadActive = false,
                currencyActive = true, bankCardActive = false
            )
        )
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Text is unclear.", textReadActive = false,
                currencyActive = false, bankCardActive = true
            )
        )
    }

    @Test
    fun `real answers object refusals and blanks never swap`() {
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "A black chair sits here.", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "Not clearly visible.", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
        assertFalse(
            VyzeCoreController.companionShouldSubstituteObjectRefusal(
                "", textReadActive = false,
                currencyActive = false, bankCardActive = false
            )
        )
    }

    // ── CJK coverage net: OCR Han runs missing from a read answer ──
    // (2026-11-08, GT 50 Pro: Malay answer fluent, OCR-captured 味精
    // dropped from the answer — not truncation, ~30/1024 tokens used.)

    @Test
    fun `ms read missing OCR Han appends Malay bridge plus span`() {
        val missingAppend = VyzeCoreController.companionCjkCoverage(
            cleanedResponse = "Serbuk perasa yang lazat.",
                ocrText = "SERBUK PERASA\n味精\nHALAL",
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        assertEquals("dan dalam Bahasa Cina:", missingAppend?.bridge)
        assertEquals("味精", missingAppend?.span)
    }

    @Test
    fun `covered span is not repeated`() {
        assertNull(
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "Serbuk perasa 味精 yang lazat.",
                ocrText = "SERBUK PERASA\n味精",
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        )
    }

    @Test
    fun `ocr without Han yields nothing`() {
        assertNull(
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "Serbuk perasa yang lazat.",
                ocrText = "SERBUK PERASA\nHALAL",
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        )
    }

    @Test
    fun `non-read echo and refusal turns never append`() {
        // Tap-to-describe: scene lane, no OCR baseline to append.
        assertNull(
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "A packet sits on the table.",
                ocrText = "味精",
                language = "en",
                textReadActive = false,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        )
        // Echo fallback: the verbatim recital already contains the span.
        assertNull(
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "SERBUK PERASA 味精",
                ocrText = "SERBUK PERASA 味精",
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = true
            )
        )
        // Refusal: appending a span would contradict "unclear".
        assertNull(
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "Text is unclear.",
                ocrText = "味精",
                language = "en",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        )
    }

    @Test
    fun `money card id medicineMiss and date lanes never append`() {
        val base = { flag: String ->
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "Sepuluh ringgit.",
                ocrText = "味精",
                language = "ms",
                textReadActive = true,
                currencyActive = flag == "currency",
                bankCardActive = flag == "card",
                sensitiveIdActive = flag == "id",
                medicineMissActive = flag == "medicine",
                dateActive = flag == "date",
                echoOnly = false
            )
        }
        assertNull(base("currency"))
        assertNull(base("card"))
        assertNull(base("id"))
        assertNull(base("medicine"))
        assertNull(base("date"))
    }

    @Test
    fun `bridges localize per language with English fallback`() {
        val cover = { lang: String ->
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = "Seasoning powder.",
                ocrText = "味精",
                language = lang,
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        }
        assertEquals("and in Chinese:", cover("en")?.bridge)
        assertEquals("dan dalam Bahasa Cina:", cover("ms")?.bridge)
        assertEquals("还有中文：", cover("zh")?.bridge)
        assertEquals("and in Chinese:", cover("ta")?.bridge)
    }

    @Test
    fun `multiple runs join in OCR order with single chars kept`() {
        val multiAppend = VyzeCoreController.companionCjkCoverage(
            cleanedResponse = "Serbuk perasa.",
                ocrText = "成分：味精，酱油，酒",
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        assertEquals("dan dalam Bahasa Cina:", multiAppend?.bridge)
        assertEquals("成分 味精 酱油 酒", multiAppend?.span)
    }

    @Test
    fun `null blank OCR and blank answers yield nothing`() {
        val cover = { cleaned: String, ocr: String? ->
            VyzeCoreController.companionCjkCoverage(
                cleanedResponse = cleaned,
                ocrText = ocr,
                language = "ms",
                textReadActive = true,
                currencyActive = false, bankCardActive = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, echoOnly = false
            )
        }
        assertNull(cover("Serbuk perasa.", null))
        assertNull(cover("Serbuk perasa.", "  "))
        assertNull(cover("", "味精"))
        assertNull(cover("   ", "味精"))
    }

    // ── Label recital lane: deterministic reads (device 2026-11-08) ──
    // The 18:58 turn ("read this for me", 158-char OCR, conf 0.72) fell in
    // the lanes' dead zone — below fast-path 0.85 AND below direct-read
    // 300 chars — so the VLM summarized/invented instead of reciting.

    private val labelOcr =
        "Daplir\nKITCHEN\nPenambah Perisa\nMonosodium Glutamat Tulen 99%\n味精\n" +
            "MONOSODIUM GLUTAMATE\n99% MURNI & BERKUALITA TINGGI\n99% PURE & HIGH QUALITY\n" +
            "Berat Bersih / Net Weight 150g"

    @Test
    fun `label read with solid OCR takes the recital lane`() {
        assertTrue(
            VyzeCoreController.companionShouldReciteLabel(
                ocrText = labelOcr, ocrConfidence = 0.72f,
                textReadActive = true, isTap = false, isPreciseRead = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, ocrLooksIncomplete = false
            )
        )
    }

    @Test
    fun `short thin and low-confidence OCR stays on the model lane`() {
        val recite = { ocr: String?, conf: Float, incomplete: Boolean ->
            VyzeCoreController.companionShouldReciteLabel(
                ocrText = ocr, ocrConfidence = conf,
                textReadActive = true, isTap = false, isPreciseRead = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, ocrLooksIncomplete = incomplete
            )
        }
        // Sparse line is the grounding threshold (60): 59 stays, 60 goes.
        assertFalse(recite("a".repeat(59), 0.72f, false))
        assertTrue(recite("a".repeat(60), 0.72f, false))
        // Confidence floor is the direct-read floor (0.65).
        assertFalse(recite(labelOcr, 0.64f, false))
        assertTrue(recite(labelOcr, 0.65f, false))
        assertFalse(recite(null, 0.72f, false))
        assertFalse(recite("   ", 0.72f, false))
        assertFalse(recite(labelOcr, 0.72f, true))
    }

    @Test
    fun `taps precise reads and guarded lanes never recite`() {
        val recite = { flag: String ->
            VyzeCoreController.companionShouldReciteLabel(
                ocrText = labelOcr, ocrConfidence = 0.72f,
                textReadActive = flag != "nonread",
                isTap = flag == "tap",
                isPreciseRead = flag == "precise",
                sensitiveIdActive = flag == "id",
                medicineMissActive = flag == "medicine",
                dateActive = flag == "date",
                ocrLooksIncomplete = false
            )
        }
        assertFalse(recite("nonread"))
        assertFalse(recite("tap"))
        assertFalse(recite("precise"))
        assertFalse(recite("id"))
        assertFalse(recite("medicine"))
        assertFalse(recite("date"))
    }

    @Test
    fun `medicine-hit OCR stays on the model lane`() {
        assertFalse(
            VyzeCoreController.companionShouldReciteLabel(
                ocrText = "$labelOcr\n[MEDICINE INFO: Panadol, paracetamol, 500mg. Take as needed. WARNING: Do not exceed.]",
                ocrConfidence = 0.72f,
                textReadActive = true, isTap = false, isPreciseRead = false,
                sensitiveIdActive = false, medicineMissActive = false,
                dateActive = false, ocrLooksIncomplete = false
            )
        )
    }

    @Test
    fun `mixed label splits into Latin-Han-Latin spans in order`() {
        val spans = VyzeCoreController.companionBuildRecitalSpans(labelOcr)
        assertEquals(3, spans.size)
        assertFalse(spans[0].needsZhVoice)
        // Lexicon corrects the brand garble (Daplir→Dapur) — the span
        // carries corrected words while memory keeps raw OCR.
        assertTrue(spans[0].text.contains("Dapur KITCHEN"))
        assertTrue(spans[0].text.contains("Tulen 99%"))
        assertFalse(spans[0].text.contains("\n"))
        assertEquals("味精", spans[1].text)
        assertTrue(spans[1].needsZhVoice)
        assertFalse(spans[2].needsZhVoice)
        assertTrue(spans[2].text.contains("MONOSODIUM GLUTAMATE"))
        assertTrue(spans[2].text.contains("150g"))
        assertFalse(spans[2].text.contains("\n"))
    }

    @Test
    fun `pure Latin yields one span and multiline Han joins with spaces`() {
        val latin = VyzeCoreController.companionBuildRecitalSpans("Penambah Perisa\nTulen 99%")
        assertEquals(1, latin.size)
        assertFalse(latin[0].needsZhVoice)
        val han = VyzeCoreController.companionBuildRecitalSpans("味精\n纯素")
        assertEquals(1, han.size)
        assertEquals("味精 纯素", han[0].text)
        assertTrue(han[0].needsZhVoice)
    }

    @Test
    fun `adjacent Han-Latin lines split and kana rides the current voice`() {
        val spans = VyzeCoreController.companionBuildRecitalSpans("味精\nMONOSODIUM")
        assertEquals(2, spans.size)
        assertEquals("味精", spans[0].text)
        assertTrue(spans[0].needsZhVoice)
        assertEquals("MONOSODIUM", spans[1].text)
        assertFalse(spans[1].needsZhVoice)
        // Han-only scope (same as the CJK net): kana is not zh-routed.
        val kana = VyzeCoreController.companionBuildRecitalSpans("の")
        assertEquals(1, kana.size)
        assertFalse(kana[0].needsZhVoice)
    }

    @Test
    fun `blank OCR yields no spans`() {
        assertTrue(VyzeCoreController.companionBuildRecitalSpans("").isEmpty())
        assertTrue(VyzeCoreController.companionBuildRecitalSpans("  \n  ").isEmpty())
    }

    @Test
    fun `RM prices expand to Ringgit words`() {
        assertEquals("Ringgit 12.90", VyzeCoreController.companionExpandRmPrices("RM12.90"))
        assertEquals("Ringgit 5", VyzeCoreController.companionExpandRmPrices("RM 5"))
        assertEquals("Ringgit 5", VyzeCoreController.companionExpandRmPrices("rm5"))
        assertEquals("Ringgit", VyzeCoreController.companionExpandRmPrices("RM"))
        assertEquals("FIRM", VyzeCoreController.companionExpandRmPrices("FIRM"))
        assertEquals("HARUM12", VyzeCoreController.companionExpandRmPrices("HARUM12"))
    }

    // ── Lexicon correction for recital spans (device 2026-11-08) ──
    // The deterministic recital speaks OCR garble literally ("Pertambah",
    // "Nonosodium") — too rough for label reads. Single/double-char
    // confusions on real words get lexicon-corrected; everything else
    // (digits, codes, CJK, ambiguous, short) passes through untouched.

    @Test
    fun `observed label garble corrects to label words`() {
        assertEquals("Penambah", VyzeCoreController.companionCorrectWord("Pertambah"))
        assertEquals("Tulen", VyzeCoreController.companionCorrectWord("Tuleà"))
        assertEquals("Monosodium", VyzeCoreController.companionCorrectWord("Nonosodium"))
        assertEquals("Glutamat", VyzeCoreController.companionCorrectWord("Gatamat"))
        assertEquals("Dapur", VyzeCoreController.companionCorrectWord("Daplir"))
        assertEquals("Dapur", VyzeCoreController.companionCorrectWord("Dapure"))
    }

    @Test
    fun `correction is conservative`() {
        // Exact words (even mixed-case garble) normalize, never change.
        assertEquals("Penambah", VyzeCoreController.companionCorrectWord("Penambah"))
        // No close neighbor: untouched.
        assertEquals("Xyzzq", VyzeCoreController.companionCorrectWord("Xyzzq"))
        // Two closest (tulen/bulan): ambiguous, untouched.
        assertEquals("tulan", VyzeCoreController.companionCorrectWord("tulan"))
        // Short tokens: too ambiguous to touch.
        assertEquals("ab", VyzeCoreController.companionCorrectWord("ab"))
        assertEquals("RM", VyzeCoreController.companionCorrectWord("RM"))
        // Digits, codes, CJK: never word-corrected.
        assertEquals("150g", VyzeCoreController.companionCorrectWord("150g"))
        assertEquals("QLB3469", VyzeCoreController.companionCorrectWord("QLB3469"))
        assertEquals("味精", VyzeCoreController.companionCorrectWord("味精"))
    }

    @Test
    fun `casing mirrors the OCR word`() {
        assertEquals("TULEN", VyzeCoreController.companionCorrectWord("TULEÀ"))
        assertEquals("tulen", VyzeCoreController.companionCorrectWord("tuleà"))
        assertEquals("Tulen", VyzeCoreController.companionCorrectWord("Tuleà"))
    }

    @Test
    fun `recital spans speak corrected words`() {
        val spans = VyzeCoreController.companionBuildRecitalSpans("Pertambah Perisa\nTulen 99%")
        assertEquals(1, spans.size)
        assertTrue(spans[0].text.contains("Penambah Perisa"))
        assertTrue(spans[0].text.contains("Tulen 99%"))
    }

    @Test
    fun `price expansion survives word correction`() {
        val spans = VyzeCoreController.companionBuildRecitalSpans("Harga RM12.90")
        assertEquals(1, spans.size)
        assertEquals("Harga Ringgit 12.90", spans[0].text)
    }

    // ── Verbatim-read gate: who may speak OCR directly (2026-11-09) ──
    // Whole-app audit P1 #2: the OCR fast-path spoke sensitive reads
    // verbatim — no sensitive-ID or precise-read exclusion. Every lane
    // that speaks OCR text directly (fast-path, direct-read, label
    // recital) now shares this gate: explicit reads only.

    @Test
    fun `clean explicit reads take verbatim lanes`() {
        assertTrue(
            VyzeCoreController.companionVerbatimReadAllowed(
                textReadActive = true, isTap = false,
                isPreciseRead = false, sensitiveIdActive = false
            )
        )
    }

    @Test
    fun `non-reads and taps never take verbatim lanes`() {
        // Scene asks keep the VLM in the loop for framing/grounding.
        assertFalse(
            VyzeCoreController.companionVerbatimReadAllowed(
                textReadActive = false, isTap = false,
                isPreciseRead = false, sensitiveIdActive = false
            )
        )
        // A tap on a product must keep the model in the loop — the
        // fast path would commit Latin-only OCR and never let the
        // model read small/mixed script the OCR missed.
        assertFalse(
            VyzeCoreController.companionVerbatimReadAllowed(
                textReadActive = true, isTap = true,
                isPreciseRead = false, sensitiveIdActive = false
            )
        )
    }

    @Test
    fun `precise-read modes keep the VLM no-guess rules`() {
        // "read this note" IS a text-extraction ask — without this
        // exclusion a 0.85-confidence capture would speak raw OCR and
        // bypass the currency/bank-card never-guess contracts.
        assertFalse(
            VyzeCoreController.companionVerbatimReadAllowed(
                textReadActive = true, isTap = false,
                isPreciseRead = true, sensitiveIdActive = false
            )
        )
    }

    @Test
    fun `sensitive-ID asks never take verbatim lanes`() {
        // "read my IC number" at high OCR confidence must reach the
        // pinned refusal — never spoken digits.
        assertFalse(
            VyzeCoreController.companionVerbatimReadAllowed(
                textReadActive = true, isTap = false,
                isPreciseRead = false, sensitiveIdActive = true
            )
        )
    }

    // ── Precise-mode banks: word boundaries (audit P2 #21) ──
    // Bare-substring banks misfired on food labels: "wang" in "bawang"
    // armed currency mode on onions, "kad" in "kadar" armed bank-card
    // on sugar levels, "card" in "cardboard", "pill" in "spill".
    // Latin keywords now need word boundaries (trailing -s and Malay
    // -nya/-ku/-mu allowed); CJK stays substring (no word breaks).

    @Test
    fun `food and object words no longer arm money mode`() {
        assertFalse(VyzeCoreController.companionIsCurrencyQuery("baca label bawang putih ini"))
        assertFalse(VyzeCoreController.companionIsCurrencyQuery("minyak wangi apa ini"))
        assertFalse(VyzeCoreController.companionIsCurrencyQuery("kacang cashew"))
        assertFalse(VyzeCoreController.companionIsCurrencyQuery("timbang berat ini"))
    }

    @Test
    fun `genuine money asks still arm money mode`() {
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("berapa duit ini"))
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("what money is this"))
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("wang kertas ini"))
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("duit syiling"))
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("这个多少钱"))
        // Malay possessive clitic: still money.
        assertTrue(VyzeCoreController.companionIsCurrencyQuery("wangnya berapa"))
    }

    @Test
    fun `cardboard kadar and postcards no longer arm bank-card mode`() {
        assertFalse(VyzeCoreController.companionIsBankCardQuery("what is this cardboard"))
        assertFalse(VyzeCoreController.companionIsBankCardQuery("discard this"))
        assertFalse(VyzeCoreController.companionIsBankCardQuery("berapa kadar gula ini"))
        assertFalse(VyzeCoreController.companionIsBankCardQuery("read this postcard"))
    }

    @Test
    fun `genuine card asks still arm bank-card mode`() {
        assertTrue(VyzeCoreController.companionIsBankCardQuery("what card is this"))
        assertTrue(VyzeCoreController.companionIsBankCardQuery("kad bank ini"))
        assertTrue(VyzeCoreController.companionIsBankCardQuery("which bank"))
        assertTrue(VyzeCoreController.companionIsBankCardQuery("kad apa ini"))
        assertTrue(VyzeCoreController.companionIsBankCardQuery("卡号是多少"))
        assertTrue(VyzeCoreController.companionIsBankCardQuery("debit cards"))
    }

    @Test
    fun `spills and dosa no longer arm medicine mode`() {
        assertFalse(VyzeCoreController.companionIsMedicineQuery("don't spill the water"))
        assertFalse(VyzeCoreController.companionIsMedicineQuery("read this dosa packet"))
        assertFalse(VyzeCoreController.companionIsMedicineQuery("endorse this cheque"))
    }

    @Test
    fun `genuine medicine asks still arm medicine mode`() {
        assertTrue(VyzeCoreController.companionIsMedicineQuery("ubat apa ini"))
        assertTrue(VyzeCoreController.companionIsMedicineQuery("take two tablets"))
        assertTrue(VyzeCoreController.companionIsMedicineQuery("panadol dosage"))
        assertTrue(VyzeCoreController.companionIsMedicineQuery("dos ubat ini"))
        assertTrue(VyzeCoreController.companionIsMedicineQuery("这是什么药"))
        assertTrue(VyzeCoreController.companionIsMedicineQuery("ubatmu apa"))
        // Genuine ambiguity, preset behavior: a bare "capsule" is read
        // as the medicine sense (coffee capsules misroute by design —
        // no lexicon can split them, and medicine needs the recall).
        assertTrue(VyzeCoreController.companionIsMedicineQuery("capsule"))
    }

    @Test
    fun `null blank and scene queries arm no precise mode`() {
        for (q in listOf(null, "", "   ", "what is this", "apa ini")) {
            assertFalse(VyzeCoreController.companionIsCurrencyQuery(q))
            assertFalse(VyzeCoreController.companionIsBankCardQuery(q))
            assertFalse(VyzeCoreController.companionIsMedicineQuery(q))
        }
    }

    // ── Locale token headroom (audit P2 #35, 2026-11-09) ──
    // Malay morphology runs ~30% more tokens per sentence — the image
    // lane carries a x4/3 headroom but the text lane passed the bare
    // English-sized cap, truncating Malay answers mid-word. One shared
    // rule now serves both lanes (Chinese needs none — denser script).

    @Test
    fun `malay budgets grow by four thirds`() {
        assertEquals(256, VyzeCoreController.companionLocaleTokenHeadroom(192, "ms"))
        assertEquals(128, VyzeCoreController.companionLocaleTokenHeadroom(96, "ms"))
    }

    @Test
    fun `english chinese and unknown keep the base budget`() {
        assertEquals(192, VyzeCoreController.companionLocaleTokenHeadroom(192, "en"))
        assertEquals(192, VyzeCoreController.companionLocaleTokenHeadroom(192, "zh"))
        assertEquals(192, VyzeCoreController.companionLocaleTokenHeadroom(192, "ta"))
    }

    // ── Thermal token cap (audit P3 #72, 2026-11-09) ──
    // MODERATE+ heat tightens the scene-token budget so generation —
    // the hottest inference phase — ends sooner. The rule was inline
    // in the image lane only; the expansion ("tell me more") lane ran
    // the raw cap into an overheating SoC. One shared rule now serves
    // both (same `in 1 until base` semantics, extracted verbatim).

    @Test
    fun `thermal cap below base tightens the budget`() {
        assertEquals(64, VyzeCoreController.companionThermalCappedTokens(96, 64))
        assertEquals(64, VyzeCoreController.companionThermalCappedTokens(128, 64))
    }

    @Test
    fun `degenerate caps keep the base budget`() {
        // Zero/negative = no cap published; at-or-above base = no
        // tightening (the `in 1 until base` rule, both bounds pinned).
        assertEquals(128, VyzeCoreController.companionThermalCappedTokens(128, 0))
        assertEquals(128, VyzeCoreController.companionThermalCappedTokens(128, -1))
        assertEquals(128, VyzeCoreController.companionThermalCappedTokens(128, 128))
        assertEquals(128, VyzeCoreController.companionThermalCappedTokens(128, 256))
    }

    // ── Direct-read length gate (audit P2 #23, 2026-11-09) ──
    // A conf>=0.85 shortcut bypassed the 300-char dense-doc gate, so a
    // confident fragment ("150g", partial OCR) was announced as "Long
    // document. Reading now." Short reads belong to the fast-path
    // (complete) or the VLM (fragment); the document lane needs bulk.

    @Test
    fun `short confident reads skip the document lane`() {
        assertFalse(VyzeCoreController.companionShouldReadDirectly("150g", 0.9f))
        assertFalse(VyzeCoreController.companionShouldReadDirectly("a".repeat(100), 0.95f))
        assertFalse(VyzeCoreController.companionShouldReadDirectly("a".repeat(299), 0.95f))
    }

    @Test
    fun `long confident docs take the document lane`() {
        assertTrue(VyzeCoreController.companionShouldReadDirectly("a".repeat(300), 0.9f))
        assertTrue(VyzeCoreController.companionShouldReadDirectly("a".repeat(1200), 0.65f))
    }

    @Test
    fun `low confidence blank and null skip the document lane`() {
        assertFalse(VyzeCoreController.companionShouldReadDirectly("a".repeat(500), 0.5f))
        assertFalse(VyzeCoreController.companionShouldReadDirectly("", 0.95f))
        assertFalse(VyzeCoreController.companionShouldReadDirectly(null, 0.95f))
    }
}

package com.vyze.app.core

import org.junit.Assert.assertFalse
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
}

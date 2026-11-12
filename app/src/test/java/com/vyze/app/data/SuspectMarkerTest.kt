package com.vyze.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for [SuspectMarker.hasSuspectMark] — the memory-poisoning
 * guard predicate (whole-app audit P3 #73).
 *
 * Both recall pipes (MemoryRepository.findSimilar/recallByText) exclude
 * rows carrying suspect marks before the controller's private builders
 * ever see them, so template echoes and other pre-labeled failures can
 * never re-anchor an answer. These tests pin the predicate itself; tag
 * spellings come from the production constants and round-trip through
 * the production joiner — no copies (audit #98).
 */
class SuspectMarkerTest {

    @Test
    fun `null blank and empty feedback have no marks`() {
        assertFalse(SuspectMarker.hasSuspectMark(null))
        assertFalse(SuspectMarker.hasSuspectMark(""))
        assertFalse(SuspectMarker.hasSuspectMark("   "))
    }

    @Test
    fun `each system tag counts as suspect`() {
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_BARGE_IN_PRIOR))
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_PRIOR_ASR_FAILURE))
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_LANG_MISMATCH))
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_REPEAT_60S))
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_TEXT_GROUNDING))
        assertTrue(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_TEMPLATE_ECHO))
    }

    @Test
    fun `stored-record tags round-trip as suspect, untagged as clean`() {
        val tagged = SuspectMarker.tagsForStoredRecord(
            priorBargeIn = true,
            priorAsrFailure = false,
            langMismatch = false,
            repeatWithinWindow = false,
            textGrounding = false,
            templateEcho = true,
        )
        assertTrue(SuspectMarker.hasSuspectMark(tagged))
        val clean = SuspectMarker.tagsForStoredRecord(
            priorBargeIn = false,
            priorAsrFailure = false,
            langMismatch = false,
            repeatWithinWindow = false,
        )
        assertFalse(SuspectMarker.hasSuspectMark(clean))
    }

    @Test
    fun `user prose without tags is clean`() {
        assertFalse(SuspectMarker.hasSuspectMark("this answer was good"))
        assertFalse(SuspectMarker.hasSuspectMark("suspect"))
        assertFalse(SuspectMarker.hasSuspectMark("the suspect fled"))
    }

    @Test
    fun `any suspect-prefixed token counts, forward-compatible`() {
        assertTrue(SuspectMarker.hasSuspectMark("suspect_pool"))
    }

    @Test
    fun `matching is case-sensitive like the system writer`() {
        assertFalse(SuspectMarker.hasSuspectMark(SuspectMarker.TAG_LANG_MISMATCH.uppercase()))
    }
}

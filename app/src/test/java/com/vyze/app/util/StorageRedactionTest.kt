package com.vyze.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for storage-boundary redaction (whole-app audit P1 #3/#4,
 * 2026-11-09).
 *
 * Scan history and interaction records persisted sensitive content
 * verbatim and unbounded: bank-card/IC numbers in OCR takes, full VLM
 * prompts per interaction. Everything crossing a storage boundary now
 * passes [StorageRedaction]: long digit runs masked, length capped.
 * Live reads are untouched — redaction applies at the store call only.
 */
class StorageRedactionTest {

    // ── Long digit runs are masked ──────────────────────────────

    @Test
    fun `identity-card numbers are masked plain and dashed`() {
        assertEquals("nombor ••••", StorageRedaction.redactDigitRuns("nombor 900101145555"))
        assertEquals("nombor ••••", StorageRedaction.redactDigitRuns("nombor 900101-14-5555"))
    }

    @Test
    fun `bank-card numbers are masked plain and spaced`() {
        assertEquals("kad ••••", StorageRedaction.redactDigitRuns("kad 1234567890123456"))
        assertEquals("kad ••••", StorageRedaction.redactDigitRuns("kad 1234 5678 9012 3456"))
    }

    @Test
    fun `phone numbers and barcodes are masked`() {
        // The "+" prefix is not a digit and correctly survives — only
        // the digit run itself is masked.
        assertEquals("call +••••", StorageRedaction.redactDigitRuns("call +60123456789"))
        assertEquals("tel ••••", StorageRedaction.redactDigitRuns("tel 03-12345678"))
        assertEquals("EAN ••••", StorageRedaction.redactDigitRuns("EAN 1234567890123"))
    }

    @Test
    fun `mask is fixed-length and TTS-silent`() {
        // Fixed length leaks nothing about the number's shape; bullets
        // are skipped by TTS (never the letter-by-letter "X X X X").
        val masked = StorageRedaction.redactDigitRuns("a 1234567890123456 b 900101145555")
        assertEquals("a •••• b ••••", masked)
        assertFalse(masked.any { it.isDigit() })
    }

    // ── Ordinary numbers survive ────────────────────────────────

    @Test
    fun `prices weights dates and measures pass through`() {
        assertEquals("RM12.90", StorageRedaction.redactDigitRuns("RM12.90"))
        assertEquals("RM 1,000,000", StorageRedaction.redactDigitRuns("RM 1,000,000"))
        assertEquals("150g", StorageRedaction.redactDigitRuns("150g"))
        assertEquals("99% MURNI", StorageRedaction.redactDigitRuns("99% MURNI"))
        assertEquals("EXP 2026-11-09", StorageRedaction.redactDigitRuns("EXP 2026-11-09"))
        assertEquals("10:19:45", StorageRedaction.redactDigitRuns("10:19:45"))
        assertEquals("(512, 384)", StorageRedaction.redactDigitRuns("(512, 384)"))
        assertEquals("500mg", StorageRedaction.redactDigitRuns("500mg"))
    }

    @Test
    fun `short runs and banknote serials pass through`() {
        // Banknote serials stay readable by owner decision (2026-09-26):
        // the typical 7-digit run is below the 10-digit bar.
        assertEquals("AB1234567", StorageRedaction.redactDigitRuns("AB1234567"))
        assertEquals("QLB3469", StorageRedaction.redactDigitRuns("QLB3469"))
        assertEquals("123456789", StorageRedaction.redactDigitRuns("123456789"))
        assertEquals("", StorageRedaction.redactDigitRuns(""))
    }

    // ── Length caps ─────────────────────────────────────────────

    @Test
    fun `over-cap text truncates with an ellipsis marker`() {
        val long = "a".repeat(StorageRedaction.STORED_SCAN_MAX_CHARS + 50)
        val capped = StorageRedaction.sanitizeForStorage(long, StorageRedaction.STORED_SCAN_MAX_CHARS)
        assertEquals(StorageRedaction.STORED_SCAN_MAX_CHARS + 1, capped.length)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun `under-cap text keeps its full length`() {
        val text = "Serbuk perasa 150g"
        assertEquals(text, StorageRedaction.sanitizeForStorage(text, StorageRedaction.STORED_SCAN_MAX_CHARS))
    }

    @Test
    fun `sanitize redacts then truncates`() {
        val dirty = "kad 1234567890123456 " + "b".repeat(StorageRedaction.STORED_SCAN_MAX_CHARS)
        val clean = StorageRedaction.sanitizeForStorage(dirty, StorageRedaction.STORED_SCAN_MAX_CHARS)
        assertFalse(clean.any { it.isDigit() })
        assertTrue(clean.startsWith("kad •••• b"))
        assertTrue(clean.endsWith("…"))
    }
}

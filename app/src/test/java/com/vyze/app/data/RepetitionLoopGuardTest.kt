package com.vyze.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the REPETITION-LOOP GUARD (2026-09-28).
 *
 * Origin: row ir_85 — "It says New World Member Card with a number
 * 999999999999…" (~180-char '9' loop) reached TTS uncaught. These tests
 * pin both degenerate shapes and, just as importantly, the natural
 * answers that must NEVER trip the guard.
 */
class RepetitionLoopGuardTest {

    // ── The ir_85 regression ──────────────────────────────────────

    @Test
    fun `the ir_85 hundred-eighty-digit loop is degenerate`() {
        val ir85 = "It says New World Member Card with a number " + "9".repeat(180)
        assertTrue(RepetitionLoopGuard.isDegenerate(ir85))
    }

    // ── Shape 1: single-character runs ────────────────────────────

    @Test
    fun `character runs at or above the threshold trip in any script`() {
        assertTrue(RepetitionLoopGuard.isDegenerate("n".repeat(15)))
        assertTrue(RepetitionLoopGuard.isDegenerate("那是一张 " + "9".repeat(40) + " 号码"))
        assertTrue(RepetitionLoopGuard.isDegenerate("It says " + "好".repeat(20)))
        assertTrue(RepetitionLoopGuard.isDegenerate("aaaa aaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `runs below the threshold do not trip`() {
        assertFalse(RepetitionLoopGuard.isDegenerate("9".repeat(14)))
        // Realistic serial embedded in a sentence: below threshold.
        assertFalse(
            RepetitionLoopGuard.isDegenerate(
                "The serial number is T53302833 on this note."
            )
        )
    }

    // ── Shape 2: consecutive identical tokens ────────────────────

    @Test
    fun `six consecutive identical tokens trip regardless of length`() {
        // Long tokens (no length exemption — six in a row is never speech).
        assertTrue(
            RepetitionLoopGuard.isDegenerate(
                "mountains mountains mountains mountains mountains mountains"
            )
        )
        // Short tokens.
        assertTrue(RepetitionLoopGuard.isDegenerate("dan dan dan dan dan dan"))
        // CJK tokens (space-separated after segmentation).
        assertTrue(
            RepetitionLoopGuard.isDegenerate("钞票 钞票 钞票 钞票 钞票 钞票")
        )
    }

    @Test
    fun `five consecutive identical tokens do not trip`() {
        assertFalse(RepetitionLoopGuard.isDegenerate("dan dan dan dan dan"))
        assertFalse(
            RepetitionLoopGuard.isDegenerate(
                "mountains mountains mountains mountains mountains"
            )
        )
        // ...even when the phrase appears again non-consecutively.
        assertFalse(
            RepetitionLoopGuard.isDegenerate(
                "dan dan dan dan dan, and then one more dan later."
            )
        )
    }

    // ── Natural answers must NEVER trip (the false-positive bar) ──

    @Test
    fun `real answers from all three languages pass clean`() {
        val natural = listOf(
            // en
            "That is a Maybank Platinum debit card with a Visa logo.",
            "That is a bottle of 100 percent lemon extract.",
            "ha ha ha ha ha",  // five repeats — natural laughter edge
            // ms
            "Itu ialah paket keripik Sri Seranyak, beratnya satu kilogram.",
            "Itu ialah kad debit Maybank Platinum dengan logo Visa berwarna merah.",
            // zh
            "那是一张马来西亚的钞票。",
            "您面前有一个白色的马克杯，上面有蓝色的图案和Petronas的标志。",
            // edge: same char near-threshold across word boundaries
            "woooooo that is a nice one"
        )
        for (s in natural) {
            assertFalse("false positive on: $s", RepetitionLoopGuard.isDegenerate(s))
        }
    }

    // ── Null / blank / edge inputs ────────────────────────────────

    @Test
    fun `null blank and whitespace are never degenerate`() {
        assertFalse(RepetitionLoopGuard.isDegenerate(null))
        assertFalse(RepetitionLoopGuard.isDegenerate(""))
        assertFalse(RepetitionLoopGuard.isDegenerate("   "))
    }

    @Test
    fun `mixed whitespace token counting is whitespace-agnostic`() {
        // Tabs/newlines split tokens too.
        assertTrue(
            RepetitionLoopGuard.isDegenerate("ok\nok\nok\nok\nok\nok")
        )
    }
}

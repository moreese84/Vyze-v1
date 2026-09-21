package com.vyze.app.agent.student

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 2→3 GATE, frozen as a test: the distilled [StudentRouter] must
 * beat the regex baseline on the frozen corpora, overall AND per language,
 * with the Jev teacher's own accuracy pinned as reference on the seed.
 *
 * Two frozen fixtures (projected into [StudentRouterFixture] by
 * `python tools/jev_harness/gen_kotlin_fixture.py` — regenerate, never
 * hand-edit):
 *
 *  - [StudentRouterFixture.SEED_ROWS] — 52 seed queries (en/ms/zh) labeled
 *    live by Jev (tools/jev_harness), frozen at
 *    app/src/test/resources/fixtures/phase0_route_labels.jsonl. Backs the
 *    original gate and the Jev-teacher reference.
 *
 *  - [StudentRouterFixture.DEVICE_ROWS] — 14 device-corpus speech rows
 *    (plan item B2), hand-assigned `expected` labels, frozen at
 *    app/src/test/resources/fixtures/phase3_device_labels.jsonl. Pins the
 *    v2 distilled branches (app-cue echo, greeting garble, ms deictic
 *    opener) against regression — the rows Jev labeled live live in the
 *    git-ignored corpus, so the frozen artifact carries the hand labels.
 *    dev6 is a DOCUMENTED known miss (app-cue garble hybrid, the motivating
 *    row for the v3 app-cue-garble branch); the gate tolerates it, the
 *    named v2 regression test does not touch it.
 */
class StudentRouterFixtureTest {

    private val seedRows = StudentRouterFixture.SEED_ROWS
    private val deviceRows = StudentRouterFixture.DEVICE_ROWS
    private val allRows = seedRows + deviceRows

    private fun studentAction(query: String): String =
        StudentRouter.decide(query).action.name

    // ── Fixture integrity ─────────────────────────────────────────

    @Test
    fun `fixture integrity - all rows labeled with known actions`() {
        assertTrue("seed fixture must not be empty", seedRows.isNotEmpty())
        assertTrue("device fixture must not be empty", deviceRows.isNotEmpty())
        for (r in allRows) {
            assertTrue("row ${r.id}: expected action unknown", r.expected in StudentRouterFixture.GATE_ACTIONS)
            assertTrue("row ${r.id}: regex action unknown", r.regexAction in StudentRouterFixture.GATE_ACTIONS)
        }
    }

    @Test
    fun `fixture integrity - device rows are hand-labeled, not Jev-labeled`() {
        // Provenance invariant: DEVICE_ROWS carries no Jev teacher fields
        // (the live-labeling runs live in the git-ignored corpus) and each
        // row records its source. If you add a device row, label it by hand
        // and note where it came from — that is what makes it freezable.
        for (r in deviceRows) {
            assertNull("row ${r.id}: device rows must not carry a Jev label", r.jevAction)
            assertNull("row ${r.id}: device rows must not carry Jev confidence", r.jevConfidence)
            assertTrue("row ${r.id}: device rows must record provenance in note", !r.note.isNullOrEmpty())
        }
    }

    // ── Overall gate (seed + device) ──────────────────────────────

    @Test
    fun `GATE - student beats the regex baseline overall`() {
        val studentHits = allRows.count { studentAction(it.query) == it.expected }
        val regexHits = allRows.count { it.regexAction == it.expected }
        assertTrue(
            "Student ($studentHits/${allRows.size}) must beat regex ($regexHits/${allRows.size})",
            studentHits >= regexHits,
        )
        val rate = studentHits.toDouble() / allRows.size
        assertTrue(
            "Student accuracy ${(rate * 100).toInt()}% below the 80% absolute bar",
            rate >= 0.80,
        )
    }

    @Test
    fun `GATE - student never loses to regex within any language`() {
        for (lang in allRows.map { it.lang }.distinct()) {
            val sub = allRows.filter { it.lang == lang }
            val studentHits = sub.count { studentAction(it.query) == it.expected }
            val regexHits = sub.count { it.regexAction == it.expected }
            assertTrue(
                "[$lang] student ($studentHits/${sub.size}) must not lose to regex ($regexHits/${sub.size})",
                studentHits >= regexHits,
            )
        }
    }

    // ── Seed-only: Jev teacher reference + distillation signal ────

    @Test
    fun `GATE - Jev teacher reference stays above the regex baseline`() {
        // Seed-only: DEVICE_ROWS has no Jev fields (hand-labeled), so the
        // teacher-accuracy claim is meaningful only on the seed corpus.
        // The Phase 2 gate that justified distillation, pinned so a future
        // re-label that degrades the teacher below the baseline surfaces
        // loudly (it would mean the student has nothing left to learn).
        val jevHits = seedRows.count { it.jevAction == it.expected }
        val regexHits = seedRows.count { it.regexAction == it.expected }
        assertTrue(
            "Jev teacher ($jevHits/${seedRows.size}) fell to regex level ($regexHits/${seedRows.size}) — re-distill",
            jevHits >= regexHits,
        )
    }

    @Test
    fun `distilled signal - student recovers the regex-wrong Jev-right rows`() {
        // The rows that motivated the student in the first place: where the
        // regex was wrong and Jev was right, the student must now agree
        // with Jev's label far more often than the regex did.
        val signal = seedRows.filter { it.jevAction == it.expected && it.regexAction != it.expected }
        assertTrue("fixture lost its training signal rows", signal.isNotEmpty())
        val studentRecovered = signal.count { studentAction(it.query) == it.expected }
        val regexRecovered = signal.count { it.regexAction == it.expected }
        assertTrue(
            "Student recovered $studentRecovered/${signal.size} signal rows (regex: $regexRecovered) — must dominate",
            studentRecovered > regexRecovered,
        )
    }

    // ── Device gate (B2): the v2 branches are contractual ─────────

    @Test
    fun `GATE - student beats the regex baseline on the device corpus`() {
        val studentHits = deviceRows.count { studentAction(it.query) == it.expected }
        val regexHits = deviceRows.count { it.regexAction == it.expected }
        assertTrue(
            "Device corpus: student ($studentHits/${deviceRows.size}) must beat regex ($regexHits/${deviceRows.size})",
            studentHits >= regexHits,
        )
        val rate = studentHits.toDouble() / deviceRows.size
        assertTrue(
            "Device corpus student accuracy ${(rate * 100).toInt()}% below the 80% absolute bar",
            rate >= 0.80,
        )
    }

    @Test
    fun `v2 regression - distilled device branches hold their labels`() {
        // Named pin for the v2 branches: any edit that flips one of these
        // rows fails here BY NAME, not just as a rate drop. dev6 (app-cue
        // garble hybrid) is deliberately absent — it is the documented
        // known miss that motivates the v3 branch.
        val v2Pins = mapOf(
            "dev2" to "VLM_SCENE_DESCRIBE", // ms deictic opener ("Ini pula apa…")
            "dev3" to "IGNORE",             // app-cue echo ("please say that again")
            "dev4" to "IGNORE",             // greeting garble ("Hello, I'm a model.")
            "dev5" to "IGNORE",             // greeting garble ("Hey, I'm about this.")
        )
        for (r in deviceRows) {
            val pinned = v2Pins[r.id] ?: continue
            val actual = studentAction(r.query)
            assertTrue(
                "v2 branch regression on ${r.id} (${r.query}): expected $pinned, got $actual",
                actual == pinned,
            )
        }
    }
}

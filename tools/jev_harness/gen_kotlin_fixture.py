# ruff: noqa: E501
"""Generate the JVM test fixture for the student-router gate test.

Reads BOTH frozen labeling artifacts and emits one Kotlin fixture file
(app/src/test/java/com/vyze/app/agent/student/StudentRouterFixture.kt) so
JVM unit tests can evaluate StudentRouter against both frozen corpora
without needing a JSON parser on the test classpath:

  SEED_ROWS    Phase 0 — app/src/test/resources/fixtures/phase0_route_labels.jsonl
               Live Jev labeling of the hand-written seed corpus. Every row
               must carry the Jev teacher fields (jevAction / confidence /
               fast-path noul); error rows are rejected. This list backs the
               Phase 2→3 gate AND the Jev-teacher reference test.

  DEVICE_ROWS  Phase 3 (plan item B2) — app/src/test/resources/fixtures/
               phase3_device_labels.jsonl
               Real device-corpus speech rows with HAND-ASSIGNED `expected`
               labels; `regex_action` is computed by the real baseline
               (regex_baseline.py), never hand-typed. Jev fields are null —
               the supporting v2 distillation runs live in the (git-ignored)
               tools/jev_harness/corpus/jev_export and the StudentRouter
               KDoc. This list pins the v2 distilled branches against
               regression. `note` records provenance (source row id).

Usage (from repo root):

    python tools/jev_harness/gen_kotlin_fixture.py [seed_labels.jsonl] [--device-labels PATH]

The committed JSONL files stay the canonical frozen artifacts; this file is
a mechanical projection of them. Regenerate, never hand-edit.
"""

import json
import sys
from pathlib import Path

SEED_SOURCE = "app/src/test/resources/fixtures/phase0_route_labels.jsonl"
DEVICE_SOURCE = "app/src/test/resources/fixtures/phase3_device_labels.jsonl"
TARGET = Path("app/src/test/java/com/vyze/app/agent/student/StudentRouterFixture.kt")

# Must equal GATE_ACTIONS in the generated Kotlin (kept in sync by this check).
GATE_ACTIONS = {
    "VLM_SCENE_DESCRIBE", "VLM_TEXT_READ", "VLM_VOICE_QUERY",
    "FAST_COLOR_ANALYSIS", "LIGHT_CHECK", "SOS", "IGNORE",
}

HEADER = """\
// GENERATED from the frozen labeling artifacts:
//   SEED_ROWS    <- app/src/test/resources/fixtures/phase0_route_labels.jsonl
//                   (Phase 0 live Jev labeling of the seed corpus)
//   DEVICE_ROWS  <- app/src/test/resources/fixtures/phase3_device_labels.jsonl
//                   (Phase 3 device-corpus labels, hand-assigned per plan B2;
//                    regex_action from the real baseline, Jev fields null)
// Regenerate with: python tools/jev_harness/gen_kotlin_fixture.py
// Do not hand-edit.
package com.vyze.app.agent.student

/**
 * Frozen route labels consumed by StudentRouterFixtureTest.
 *
 * SEED_ROWS backs the Phase 2→3 gate (student ≥ regex baseline on the
 * Jev-labeled seed corpus) and the Jev-teacher reference test.
 *
 * DEVICE_ROWS is the B2 regression gate for the v2 distilled branches
 * (app-cue echo, greeting garble, ms deictic opener): `expected` was
 * hand-assigned from the device-corpus distillation evidence; `note`
 * records the source row. DEVICE_ROWS carries no Jev fields by design.
 */
object StudentRouterFixture {

    data class Row(
        val id: String,
        val lang: String,
        val query: String,
        val previous: String?,
        val expected: String,
        val regexAction: String,
        val jevAction: String?,
        val jevConfidence: Double?,
        val jevFastPathNoul: Double?,
        val note: String?,
    )

    val SEED_ROWS: List<Row> = listOf(
"""

MIDDLE = """\
    )

    val DEVICE_ROWS: List<Row> = listOf(
"""

FOOTER = """\
    )

    /** Actions whose speech-routing correctness the gate measures. */
    val GATE_ACTIONS = setOf(
        "VLM_SCENE_DESCRIBE", "VLM_TEXT_READ", "VLM_VOICE_QUERY",
        "FAST_COLOR_ANALYSIS", "LIGHT_CHECK", "SOS", "IGNORE",
    )
}
"""


def kstr(value: str) -> str:
    """Kotlin string literal with escapes for quotes/backslashes."""
    escaped = (
        value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    )
    return f'"{escaped}"'


def knullable_str(value: str | None) -> str:
    return kstr(value) if value is not None else "null"


def knullable_float(value) -> str:
    """Python float repr is a valid Kotlin double literal; None -> null."""
    if value is None:
        return "null"
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SystemExit(f"Expected a number or null, got: {value!r}")
    return repr(float(value))


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    if not rows:
        raise SystemExit(f"No rows found in {path}.")
    return rows


def check_action(row_id, value: str, field: str) -> str:
    if value not in GATE_ACTIONS:
        raise SystemExit(f"Row {row_id}: {field} '{value}' is not a gate action.")
    return value


def load_seed_rows(path: Path) -> list[dict]:
    """Phase 0 seed rows: live Jev labeling, teacher fields required."""
    rows = []
    for r in load_jsonl(path):
        if r.get("error") is not None:
            raise SystemExit(
                f"Seed fixture has error rows ({r.get('id')}: {r['error']}) — "
                "freeze a clean run: every row must be live-labeled."
            )
        expected = r.get("expected")
        if not expected:
            raise SystemExit(f"Row {r.get('id')} has no expected label.")
        if r.get("jev_action") is None or r.get("jev_confidence") is None \
                or r.get("jev_fast_path_noul") is None:
            raise SystemExit(
                f"Seed row {r.get('id')} is missing Jev teacher fields — "
                "SEED_ROWS must come from a live labeling run."
            )
        check_action(r["id"], expected, "expected")
        check_action(r["id"], r["regex_action"], "regex_action")
        rows.append(r)
    return rows


def load_device_rows(path: Path) -> list[dict]:
    """Phase 3 device rows: hand-assigned expected, baseline regex_action."""
    rows = []
    for r in load_jsonl(path):
        row_id = r.get("id")
        expected = r.get("expected")
        if not expected:
            raise SystemExit(f"Device row {row_id} has no expected label.")
        if r.get("regex_action") is None:
            raise SystemExit(
                f"Device row {row_id} has no regex_action — compute it with "
                "tools/jev_harness/regex_baseline.py, never hand-type it."
            )
        check_action(row_id, expected, "expected")
        check_action(row_id, r["regex_action"], "regex_action")
        rows.append(r)
    return rows


def kotlin_row(
    *, row_id, lang, query, previous, expected, regex_action,
    jev_action, jev_confidence, jev_fast_path_noul, note,
) -> str:
    return (
        "        Row(\n"
        f"            id = {kstr(row_id)},\n"
        f"            lang = {kstr(lang or '')},\n"
        f"            query = {kstr(query)},\n"
        f"            previous = {knullable_str(previous)},\n"
        f"            expected = {kstr(expected)},\n"
        f"            regexAction = {kstr(regex_action)},\n"
        f"            jevAction = {knullable_str(jev_action)},\n"
        f"            jevConfidence = {knullable_float(jev_confidence)},\n"
        f"            jevFastPathNoul = {knullable_float(jev_fast_path_noul)},\n"
        f"            note = {knullable_str(note)},\n"
        "        ),"
    )


def main() -> int:
    seed_path = Path(sys.argv[1]) if len(sys.argv) > 1 and not sys.argv[1].startswith("-") \
        else Path(SEED_SOURCE)
    device_path = Path(DEVICE_SOURCE)
    if "--device-labels" in sys.argv:
        device_path = Path(sys.argv[sys.argv.index("--device-labels") + 1])

    seed_rows = load_seed_rows(seed_path)
    device_rows = load_device_rows(device_path)

    seed_body = [
        kotlin_row(
            row_id=r["id"], lang=r.get("lang"), query=r["query"],
            previous=r.get("previous"), expected=r["expected"],
            regex_action=r["regex_action"], jev_action=r["jev_action"],
            jev_confidence=r["jev_confidence"],
            jev_fast_path_noul=r["jev_fast_path_noul"], note=None,
        )
        for r in seed_rows
    ]
    device_body = [
        kotlin_row(
            row_id=r["id"], lang=r.get("lang"), query=r["query"],
            previous=r.get("previous"), expected=r["expected"],
            regex_action=r["regex_action"], jev_action=None,
            jev_confidence=None, jev_fast_path_noul=None, note=r.get("note"),
        )
        for r in device_rows
    ]

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(
        HEADER + "\n".join(seed_body)
        + "\n" + MIDDLE + "\n".join(device_body) + "\n" + FOOTER,
        encoding="utf-8",
    )
    print(f"Wrote {len(seed_rows)} SEED_ROWS + {len(device_rows)} DEVICE_ROWS -> {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

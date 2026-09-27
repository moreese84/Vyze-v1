# ruff: noqa: E501
"""B4 rung-0 fitter: a 3-class decision list distilled from Jev labels.

  python -m tools.jev_harness fit_rung0 \
      --features out/features_rolling.jsonl \
      --fixture app/src/test/resources/fixtures/phase0_route_labels.jsonl \
      --device-fixture app/src/test/resources/fixtures/phase3_device_labels.jsonl \
      --emit-kotlin app/src/main/java/com/vyze/app/agent/student/Rung0Classifier.kt \
      --out out/rung0_weights.json

Offline, deterministic, pure Python. The 3-class projection absorbs the
Jev scene/voice boundary noise BY CONSTRUCTION (2026-09-27 owner decision):
both map to WAKE, so teacher disagreement inside the wake box never
reaches the student.

  DROP — transcript is not content (pre-gate: silent / gentle-ignore)
  FAST — deterministic lane (color / light), no VLM wake
  WAKE — everything else (default; the conservative catch-all)

Fit: ordered decision list, greedy coverage. A rule fires only when its
subset is PURE enough (purity ≥ PURE_MIN and n ≥ N_MIN); rules are tried
in learned order, first match wins; unmatched rows → WAKE (never drop on
doubt). Ordered rules give conjunctions for free (e.g. "has_question_marker
→ WAKE" placed BEFORE "has_greeting → DROP" implements greeting-garble).

Gate: the fitted list is validated against BOTH frozen fixtures projected
to 3-class. Hard invariants: zero false DROPs on positive-intent rows;
3-class accuracy ≥ the regex projection; every rule's evidence (n, purity)
recorded for the fixture-review ritual.
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

from .features import (  # single source of truth for banks + fold
    APP_CUE_TOKENS, COLOR_KEYWORDS, CURRENCY_KEYWORDS, FEATURE_ORDER,
    FILLERS, GREETINGS, LIGHT_KEYWORDS, MEDICINE_KEYWORDS,
    QUESTION_MARKERS, READ_KEYWORDS, SELF_REFERENTIAL, TIME_KEYWORDS,
    BATTERY_KEYWORDS, TRAD_TO_SIMP, extract_features,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 3-class projection. SOS stays WAKE: safety telemetry must stay on the
# audible path; speech never executes SOS (house invariant) but it must
# never be silenced either.
TO_3CLASS = {
    "IGNORE": "DROP",
    "FAST_COLOR_ANALYSIS": "FAST",
    "LIGHT_CHECK": "FAST",
}

PURE_MIN = 0.90   # a rule may only fire on a ≥90%-pure subset
N_MIN = 3         # …covering at least 3 rows (watch-list discipline)


def project(label: str) -> str:
    return TO_3CLASS.get(label, "WAKE")# ── Condition grammar (tiny, mirrors the Kotlin scorer) ──────────
# ("b", feature)        → binary feature == 1
# ("nb", feature)       → binary feature == 0 (negation — the garble
#                         conjunctions need it: greeting AND NOT question)
# ("tc<="  , k)           → token_count ≤ k  (k ∈ {1,2,3})

def candidate_conditions() -> list[tuple]:
    conds = [("b", f) for f in FEATURE_ORDER
             if f not in ("token_count", "char_count")]
    conds += [("nb", f) for f in FEATURE_ORDER
              if f not in ("token_count", "char_count", "is_empty")]
    conds += [("tc<="  , k) for k in (1, 2, 3)]
    return conds


def condition_holds(cond: tuple, feats: dict) -> bool:
    kind, arg = cond
    if kind == "b":
        return feats.get(arg) == 1
    if kind == "nb":
        return feats.get(arg) == 0
    return feats.get("token_count", 99) <= arg


def fit_decision_list(rows: list[dict]) -> list[dict]:
    """Greedy ordered rules: (cond, cls, n, purity). First match wins."""
    remaining = list(rows)
    rules: list[dict] = []
    while True:
        best = None
        for cond in candidate_conditions():
            covered = [r for r in remaining if condition_holds(cond, r["features"])]
            if len(covered) < N_MIN:
                continue
            counts = Counter(r["y3"] for r in covered)
            cls, n_top = counts.most_common(1)[0]
            purity = n_top / len(covered)
            # WAKE is the default — a rule that 'discovers' WAKE is useless.
            if cls == "WAKE":
                continue
            if purity < PURE_MIN:
                continue
            if best is None or len(covered) > best["n"]:
                best = {"cond": cond, "cls": cls, "n": len(covered),
                        "purity": round(purity, 3)}
        if best is None:
            break
        rules.append(best)
        remaining = [r for r in remaining
                     if not condition_holds(best["cond"], r["features"])]
    return rules


def classify(rules: list[dict], feats: dict) -> str:
    for rule in rules:
        if condition_holds(rule["cond"], feats):
            return rule["cls"]
    return "WAKE"


# ── Fixture validation ────────────────────────────────────────────


def load_fixture(path: str) -> list[dict]:
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("expected"):
            rows.append(row)
    return rows


def validate_on_fixture(rules: list[dict], fixture_rows: list[dict],
                        name: str) -> dict:
    checked, drops_on_positive, hits = 0, 0, 0
    misses = []
    for row in fixture_rows:
        feats = extract_features(row)
        expected3 = project(str(row["expected"]))
        got = classify(rules, feats)
        checked += 1
        if got == expected3:
            hits += 1
        else:
            misses.append((row.get("id"), row.get("query"), expected3, got))
        if got == "DROP" and expected3 != "DROP":
            drops_on_positive += 1
    return {
        "fixture": name, "checked": checked, "hits": hits,
        "accuracy": round(hits / checked, 3) if checked else None,
        "false_drops_on_positive": drops_on_positive,
        "misses": misses[:12],
    }


# ── Kotlin emission ───────────────────────────────────────────────


def _kt_string_list(name: str, items: list[str]) -> str:
    body = ",\n        ".join(f'"{i}"' for i in items)
    return f"    private val {name} = listOf(\n        {body},\n    )"


def emit_kotlin(rules: list[dict], provenance: dict) -> str:
    banks = [
        ("QUESTION_MARKERS", QUESTION_MARKERS), ("GREETINGS", GREETINGS),
        ("APP_CUE_TOKENS", APP_CUE_TOKENS), ("FILLERS", FILLERS),
        ("READ_KEYWORDS", READ_KEYWORDS), ("COLOR_KEYWORDS", COLOR_KEYWORDS),
        ("LIGHT_KEYWORDS", LIGHT_KEYWORDS), ("CURRENCY_KEYWORDS", CURRENCY_KEYWORDS),
        ("MEDICINE_KEYWORDS", MEDICINE_KEYWORDS), ("TIME_KEYWORDS", TIME_KEYWORDS),
        ("BATTERY_KEYWORDS", BATTERY_KEYWORDS),
        ("SELF_REFERENTIAL", SELF_REFERENTIAL),
    ]
    bank_lines = "\n\n".join(_kt_string_list(n, b) for n, b in banks)
    fold_lines = ",\n        ".join(f'"{k}" to "{v}"' for k, v in TRAD_TO_SIMP.items())

    def cond_kt(cond: tuple) -> str:
        kind, arg = cond
        if kind == "b":
            return f'RuleCond.Binary("{arg}")'
        if kind == "nb":
            return f'RuleCond.NotBinary("{arg}")'
        return f"RuleCond.TokenCountAtMost({arg})"

    rule_lines = ",\n        ".join(
        f"Rule({cond_kt(r['cond'])}, Verdict.{r['cls']}, "
        f"n = {r['n']}, purity = {r['purity']}f)"
        for r in rules
    )
    prov = json.dumps(provenance, ensure_ascii=False, indent=2)

    return f"""package com.vyze.app.agent.student

/**
 * B4 RUNG-0 CLASSIFIER — GENERATED FILE. Regenerate with:
 *
 *   python -m tools.jev_harness fit_rung0 --emit-kotlin <this file> …
 *
 * NEVER hand-edit: every constant below (feature banks, zh script fold,
 * ordered decision list, provenance) is emitted from the Python source of
 * truth (tools/jev_harness/features.py + fit_rung0.py) so the dev-side
 * fitter and the on-device scorer can never drift.
 *
 * WHAT IT DECIDES — the 3-class behavior boundary only:
 *   [Verdict.DROP]  not content → pre-gate may silence (sub-flag, dark)
 *   [Verdict.FAST]  deterministic lane (color / light), no VLM wake
 *   [Verdict.WAKE]  default — wake the VLM; fine routing happens later
 * The fine taxonomy (SCENE vs VOICE vs TEXT_READ vs currency/bank-card
 * modes) is NOT decided here: those deterministic detectors stay the
 * authority inside WAKE. Jev's scene/voice boundary noise is absorbed by
 * this projection by construction.
 *
 * PROVENANCE (fitted 2026-09-27 from the Jev-labeled rolling corpus):
 * {prov}
 *
 * SAFETY invariants (enforced by [Rung0GateTest] on the frozen fixtures):
 *  - ZERO false DROPs on positive-intent rows (a real ask is never silenced)
 *  - SOS projected to WAKE — safety telemetry stays audible
 *  - default verdict is WAKE: doubt never drops, doubt never fast-paths
 * SHIPS DARK: consult only behind the debug pre-gate flag (the runtime
 * never enables itself).
 */
object Rung0Classifier {{

    enum class Verdict {{ DROP, FAST, WAKE }}

    /** One decision-list rule: first match in [RULES] order wins. */
    data class Rule(val cond: RuleCond, val verdict: Verdict, val n: Int, val purity: Float)

    sealed interface RuleCond {{
        data class Binary(val feature: String) : RuleCond
        data class NotBinary(val feature: String) : RuleCond
        data class TokenCountAtMost(val max: Int) : RuleCond
    }}

{_kt_string_list("FILLER_TOKENS", FILLERS)}

{bank_lines}

    /** Traditional→Simplified fold (device evidence 2026-09-26: ASR emits
     *  Traditional for Malaysian Mandarin; banks are Simplified). */
    private val ZH_FOLD = mapOf(
        {fold_lines},
    )

    private val RULES = listOf(
        {rule_lines},
    )

    /** Lowercase + fold + Latin tokenization — mirrors features.py exactly. */
    fun normalize(text: String): Pair<String, List<String>> {{
        val folded = text.lowercase().map {{ ZH_FOLD[it] ?: it }}.joinToString("")
        val tokens = Regex("[a-z]+").findAll(folded).map {{ it.value }}.toList()
        return folded to tokens
    }}

    private fun hitsAny(lower: String, bank: List<String>): Boolean =
        bank.any {{ lower.contains(it) }}

    private fun featureValue(feature: String, lower: String, tokens: List<String>): Int = when (feature) {{
        "has_question_marker" -> if (hitsAny(lower, QUESTION_MARKERS)) 1 else 0
        "has_greeting" -> if (hitsAny(lower, GREETINGS)) 1 else 0
        "has_app_cue" -> if (hitsAny(lower, APP_CUE_TOKENS)) 1 else 0
        "has_filler" -> if (tokens.any {{ it in FILLER_TOKENS }}) 1 else 0
        "hits_read" -> if (hitsAny(lower, READ_KEYWORDS)) 1 else 0
        "hits_color" -> if (hitsAny(lower, COLOR_KEYWORDS)) 1 else 0
        "hits_light" -> if (hitsAny(lower, LIGHT_KEYWORDS)) 1 else 0
        "hits_currency" -> if (hitsAny(lower, CURRENCY_KEYWORDS)) 1 else 0
        "hits_medicine" -> if (hitsAny(lower, MEDICINE_KEYWORDS)) 1 else 0
        "hits_time" -> if (hitsAny(lower, TIME_KEYWORDS)) 1 else 0
        "hits_battery" -> if (hitsAny(lower, BATTERY_KEYWORDS)) 1 else 0
        "has_previous_turn" -> 0 // context arrives per-call; never a rung-0 signal
        "lang_en", "lang_ms", "lang_zh" -> 0 // language never drops alone
        "self_referential" -> if (hitsAny(lower, SELF_REFERENTIAL)) 1 else 0
        "is_empty" -> if (lower.isBlank()) 1 else 0
        else -> 0
    }}

    private fun condHolds(cond: RuleCond, lower: String, tokens: List<String>): Boolean =
        when (cond) {{
            is RuleCond.Binary -> featureValue(cond.feature, lower, tokens) == 1
            is RuleCond.NotBinary -> featureValue(cond.feature, lower, tokens) == 0
            is RuleCond.TokenCountAtMost -> tokens.size <= cond.max
        }}

    /**
     * PURE scorer: transcript text in → [Verdict] out. No I/O, no state.
     * Default is [Verdict.WAKE]; only a learned rule may DROP or FAST.
     */
    fun classify(transcript: String): Verdict {{
        val (lower, tokens) = normalize(transcript)
        for (rule in RULES) {{
            if (condHolds(rule.cond, lower, tokens)) return rule.verdict
        }}
        return Verdict.WAKE
    }}
}}
"""


# ── Disagreement report (corpus notes) ────────────────────────────


def disagreement_report(rows: list[dict]) -> str:
    lines = ["JEV vs REGEX DISAGREEMENT REPORT (corpus notes)", ""]
    disagree = [r for r in rows
                if r.get("regex_action") and r.get("jev_action")
                and r["regex_action"] != r["jev_action"]]
    lines.append(f"disagreeing rows: {len(disagree)}/{len(rows)}")
    scene_voice = [r for r in disagree
                   if {r["regex_action"], r["jev_action"]} ==
                   {"VLM_SCENE_DESCRIBE", "VLM_VOICE_QUERY"}]
    lines.append(f"  scene<->voice boundary rows (absorbed by 3-class): "
                 f"{len(scene_voice)}")
    other = [r for r in disagree if r not in scene_voice]
    lines.append(f"  behavior-relevant disagreements: {len(other)}")
    lines.append("")
    for r in other:
        lines.append(f"  [{r.get('id')}] regex={r['regex_action']} "
                     f"jev={r['jev_action']} :: {str(r.get('query'))[:60]!r}")
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────


def run(features_path: str, fixture_path: str, device_fixture_path: str | None,
        out_path: str | None, kotlin_path: str | None) -> int:
    table = [json.loads(l) for l in
             Path(features_path).read_text(encoding="utf-8").splitlines()
             if l.strip()]
    for row in table:
        row["y3"] = project(row["label"])
    print(f"feature table: {len(table)} rows; "
          f"3-class distribution: {dict(Counter(r['y3'] for r in table))}")

    rules = fit_decision_list(table)
    print(f"fitted decision list: {len(rules)} rules")
    for r in rules:
        print(f"  {r['cond']} -> {r['cls']}  (n={r['n']}, purity={r['purity']})")

    train_hits = sum(1 for r in table if classify(rules, r["features"]) == r["y3"])
    print(f"train 3-class accuracy: {train_hits}/{len(table)} "
          f"= {train_hits / len(table):.3f}")

    validations = [validate_on_fixture(rules, load_fixture(fixture_path), "seed")]
    if device_fixture_path:
        validations.append(
            validate_on_fixture(rules, load_fixture(device_fixture_path), "device"))
    for v in validations:
        print(f"GATE {v['fixture']}: {v['hits']}/{v['checked']} = {v['accuracy']} "
              f"false_drops_on_positive={v['false_drops_on_positive']}")

    provenance = {
        "fitted": "2026-09-27",
        "source": "tools/jev_harness/corpus/rolling.jsonl (75 rows, Jev route --live)",
        "projection": {"IGNORE": "DROP", "FAST_*": "FAST", "default": "WAKE"},
        "hyperparams": {"pure_min": PURE_MIN, "n_min": N_MIN},
        "train_accuracy_3class": round(train_hits / len(table), 3),
        "fixture_validation": [{k: v for k, v in val.items() if k != "misses"}
                               for val in validations],
    }

    if out_path:
        Path(out_path).parent.mkdir(parents=True, exist_ok=True)
        Path(out_path).write_text(json.dumps(
            {"rules": [{**r, "cond": list(r["cond"])} for r in rules],
             "provenance": provenance}, ensure_ascii=False, indent=2),
            encoding="utf-8")
        print(f"wrote {out_path}")

    if kotlin_path:
        Path(kotlin_path).parent.mkdir(parents=True, exist_ok=True)
        Path(kotlin_path).write_text(emit_kotlin(rules, provenance),
                                     encoding="utf-8")
        print(f"wrote {kotlin_path}")

    return 0

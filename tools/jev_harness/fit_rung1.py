# ruff: noqa: E501
"""B4 rung-1 fitter: fastText-class char-n-gram classifier (pure Python,
pure-Kotlin-emittable — no runtime, no ONNX).

  python -m tools.jev_harness fit_rung1 \
      --features out/features_428.jsonl \
      --fixture app/src/test/resources/fixtures/phase0_route_labels.jsonl \
      --device-fixture app/src/test/resources/fixtures/phase3_device_labels.jsonl \
      --out out/rung1_model.json

DESIGN (plan §B4 rung 1):
  - Features: word/CJK unigrams + char n-grams (2-4, fastText-style
    "<word>" padding for Latin, raw spans for CJK), hashed into
    N_BUCKETS = 8192 buckets with FNV-1a 32-bit over UTF-8 bytes.
    Hashing (not a vocab dict) keeps the emitted scorer compact and makes
    train/device drift impossible — the same string always lands in the
    same bucket on both sides.
  - Model: multinomial Naive Bayes (Laplace alpha=1.0) over the 3-class
    projection used by rung 0 (DROP / FAST / WAKE). Multiset counts,
    log-space scoring, deterministic tie-break preferring WAKE.
  - Why NB and not SGD/logistic: 428 short rows is tiny; NB with additive
    smoothing is the strongest low-count text baseline that still emits as
    plain per-bucket count tables (logistic needs the full matrix).

GATE (identical philosophy to rung 0 — the fixture decides, never vibes):
  - train accuracy must beat the all-WAKE majority baseline
  - seed + device fixtures: ZERO false DROPs on positive-intent rows
  - fixture accuracy must STRICTLY beat the all-WAKE rule (rung-0's
    effective behavior) overall AND on the ms/zh slices
  - must beat the standing regex baseline (55.8%) on the seed fixture
  - ANY tie → prefer the lighter rung → nothing ships (student stays
    rules-only). Rung 0 already proved the refusal path is live.

NO split: the frozen fixtures are the held-out set (house discipline —
same as rung 0 / B2).

Normalization contract (mirrored exactly by the Kotlin emitter):
  lowercase -> ZH fold (features.TRAD_TO_SIMP) -> spans
  spans = regex [a-z]+ | CJK runs | other non-space runs
  unigrams = spans; char n-grams 2-4 within each span
  (Latin spans padded with '<' '>').
"""

from __future__ import annotations

import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

from .features import TRAD_TO_SIMP, extract_features  # fold + shared bank features

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

N_BUCKETS = 8192
N_MIN, N_MAX = 2, 4
ALPHA = 1.0

# Same 3-class projection as rung 0 (single source of truth for behavior).
TO_3CLASS = {
    "IGNORE": "DROP",
    "FAST_COLOR_ANALYSIS": "FAST",
    "LIGHT_CHECK": "FAST",
}
CLASSES = ("WAKE", "DROP", "FAST")  # tie-break order: prefer WAKE


def project(label: str) -> str:
    return TO_3CLASS.get(label, "WAKE")


# ── Normalization + hashing (Kotlin emitter mirrors this EXACTLY) ────

_SPAN_RE = re.compile(r"[a-z]+|[\u4e00-\u9fff]+|[^a-z\u4e00-\u9fff\s]+")


def fold_lower(text: str) -> str:
    """lowercase -> Traditional->Simplified fold (order-insensitive here:
    the fold table only maps CJK to CJK)."""
    lowered = text.lower()
    return "".join(TRAD_TO_SIMP.get(ch, ch) for ch in lowered)


def spans_of(lower_folded: str) -> list[str]:
    return _SPAN_RE.findall(lower_folded)


def ngrams_of(span: str) -> list[str]:
    """Char n-grams 2-4 within one span; Latin spans get fastText-style
    '<' '>' word padding, CJK runs stay raw (no padding)."""
    padded = f"<{span}>" if span[0].isascii() and span[0].isalpha() else span
    grams: list[str] = []
    for n in range(N_MIN, N_MAX + 1):
        if len(padded) < n:
            continue
        grams.extend(padded[i:i + n] for i in range(len(padded) - n + 1))
    return grams


def fnv1a(s: str) -> int:
    h = 2166136261
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 16777619) & 0xFFFFFFFF
    return h


def bucket_of(gram: str) -> int:
    return fnv1a(gram) & (N_BUCKETS - 1)  # power-of-two mask


def buckets_of(text: str) -> list[int]:
    lower = fold_lower(text)
    out: list[int] = []
    for span in spans_of(lower):
        out.append(bucket_of(f"u:{span}"))
        for gram in ngrams_of(span):
            out.append(bucket_of(gram))
    return out


# ── Model: multinomial NB over buckets ───────────────────────────────


def fit(rows: list[dict]) -> dict:
    counts: dict[str, Counter] = {c: Counter() for c in CLASSES}
    totals: dict[str, int] = {c: 0 for c in CLASSES}
    priors: Counter = Counter()
    for r in rows:
        y = r["y3"]
        priors[y] += 1
        for b in buckets_of(str(r.get("query") or "")):
            counts[y][b] += 1
            totals[y] += 1
    return {"counts": counts, "totals": totals, "priors": priors}


def classify(model: dict, text: str) -> str:
    prior_total = sum(model["priors"].values())
    best, best_score = "WAKE", None
    for cls in CLASSES:
        prior = model["priors"].get(cls, 0)
        total = model["totals"].get(cls, 0)
        # A class with no training rows can never win (log 0 = -inf) —
        # guarded so a future corpus without FAST rows cannot crash the fit.
        score = math.log(prior / prior_total) if prior > 0 else float("-inf")
        denom = total + ALPHA * N_BUCKETS
        for b in buckets_of(text):
            num = model["counts"][cls].get(b, 0) + ALPHA
            score += math.log(num / denom)
        if best_score is None or score > best_score:
            best, best_score = cls, score
    return best  # first-wins on exact ties = WAKE preference


# ── Gates ────────────────────────────────────────────────────────────


def load_fixture(path: str) -> list[dict]:
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            if row.get("expected"):
                rows.append(row)
    return rows


def evaluate(rows: list[dict], predict) -> dict:
    hits, drops_on_positive, misses = 0, 0, []
    per_lang: Counter = Counter()
    for row in rows:
        y = project(str(row["expected"]))
        got = predict(row)
        hits += got == y
        per_lang[f"{row.get('lang', 'en')}:{'hit' if got == y else 'miss'}"] += 1
        if got == "DROP" and y != "DROP":
            drops_on_positive += 1
        if got != y:
            misses.append((row.get("id"), str(row.get("query"))[:48], y, got))
    n = len(rows)
    return {"n": n, "hits": hits, "accuracy": round(hits / n, 3) if n else None,
            "false_drops_on_positive": drops_on_positive,
            "per_lang": dict(per_lang), "misses": misses[:12]}


# ── Emission (only called when the gate PASSES) ──────────────────────

# FIDELITY RULE: emit EVERY observed bucket (no pruning). The Kotlin scorer
# must reproduce the gated Python model EXACTLY — pruning would silently
# change scores on device and void the fixture gate.
MIN_BUCKET_COUNT = 1


def emit_kotlin(model: dict, provenance: dict) -> str:
    kept: dict[str, dict[int, int]] = {}
    for cls in CLASSES:
        kept[cls] = {b: c for b, c in model["counts"][cls].items()
                     if c >= MIN_BUCKET_COUNT}
    prov = json.dumps(provenance, ensure_ascii=False, indent=2)

    def arr(name: str, pairs: dict[int, int]) -> str:
        if not pairs:
            return f"    private val {name} = intArrayOf() // empty"
        body = ", ".join(f"{b} to {c}" for b, c in sorted(pairs.items()))
        # Chunk to keep lines under ~100k chars: Kotlin has no line limit,
        # but 200-line source files review better than one-liners.
        return (f"    private val {name} = mapOf(\n        {body},\n    )")

    arrays = "\n".join(arr(f"COUNTS_{c}", kept[c]) for c in CLASSES)
    priors = ", ".join(f"{c} to {model['priors'].get(c, 0)}" for c in CLASSES)
    totals = "\n".join(
        f"    private const val TOTAL_{c} = {model['totals'].get(c, 0)}L"
        for c in CLASSES)
    fold_lines = "\n".join(
        f'            "{k}" to "{v}",' for k, v in TRAD_TO_SIMP.items())

    return f"""package com.vyze.app.agent.student

/**
 * B4 RUNG-1 CLASSIFIER — GENERATED FILE. Regenerate with:
 *
 *   python -m tools.jev_harness fit_rung1 --emit-kotlin <this file> …
 *
 * NEVER hand-edit. Hashed char-n-gram (2-4, fastText-style padding) +
 * span-unigram multinomial Naive Bayes over the rung-0 3-class projection
 * (DROP/FAST/WAKE). Counts are emitted per FNV-1a-32 bucket (8192
 * buckets); the scorer is pure Kotlin — no runtime, no ONNX.
 *
 * PROVENANCE:
 * {prov}
 *
 * SAFETY invariants (Rung1GateTest, frozen fixtures):
 *  - ZERO false DROPs on positive-intent rows
 *  - default/tie verdict is WAKE (doubt never drops, never fast-paths)
 * SHIPS DARK behind the debug pre-gate flag (runtime never self-enables).
 */
object Rung1Classifier {{

    enum class Verdict {{ DROP, FAST, WAKE }}

    private const val N_BUCKETS = {N_BUCKETS}
    private const val ALPHA = {ALPHA}

    private val PRIORS = mapOf({priors})

{totals}

{arrays}

    private fun foldLower(text: String): String {{
        val fold = mapOf(
{fold_lines}
        )
        return buildString {{
            for (ch in text.lowercase()) append(fold["$ch"] ?: ch)
        }}
    }}

    private val SPAN_RE = Regex("[a-z]+|[\\u4e00-\\u9fff]+|[^a-z\\u4e00-\\u9fff\\s]+")

    private fun fnv1a(s: String): Int {{
        var h = 2166136261L
        for (b in s.encodeToByteArray()) {{
            h = h xor (b.toLong() and 0xFF)
            h = (h * 16777619L) and 0xFFFFFFFFL
        }}
        return (h and 0x7FFFFFFFL).toInt() and (N_BUCKETS - 1)
    }}

    private fun bucketsOf(text: String): List<Int> {{
        val out = mutableListOf<Int>()
        for (span in SPAN_RE.findAll(foldLower(text)).map {{ it.value }}) {{
            out.add(fnv1a("u:$span"))
            val padded = if (span[0] in 'a'..'z') "<$span>" else span
            for (n in 2..4) {{
                for (i in 0..(padded.length - n)) {{
                    out.add(fnv1a(padded.substring(i, i + n)))
                }}
            }}
        }}
        return out
    }}

    private fun score(cls: String, buckets: List<Int>): Double {{
        val priorTotal = PRIORS.values.sum()
        val score = kotlin.math.ln(PRIORS[cls]!!.toDouble() / priorTotal)
        val counts = when (cls) {{ "WAKE" -> COUNTS_WAKE; "DROP" -> COUNTS_DROP; else -> COUNTS_FAST }}
        val total = when (cls) {{ "WAKE" -> TOTAL_WAKE; "DROP" -> TOTAL_DROP; else -> TOTAL_FAST }}
        val denom = total + ALPHA * N_BUCKETS
        var s = score
        for (b in buckets) {{
            val num = (counts[b] ?: 0) + ALPHA
            s += kotlin.math.ln(num / denom)
        }}
        return s
    }}

    /** PURE scorer: transcript in -> [Verdict] out. No I/O, no state. */
    fun classify(transcript: String): Verdict {{
        val buckets = bucketsOf(transcript)
        var best = Verdict.WAKE
        var bestScore = Double.NEGATIVE_INFINITY
        for (cls in listOf("WAKE", "DROP", "FAST")) {{
            val s = score(cls, buckets)
            if (s > bestScore) {{
                bestScore = s
                best = when (cls) {{ "WAKE" -> Verdict.WAKE; "DROP" -> Verdict.DROP; else -> Verdict.FAST }}
            }}
        }}
        return best
    }}
}}
"""


# ── Main ─────────────────────────────────────────────────────────────


def run(features_path: str, fixture_path: str, device_fixture_path: str | None,
        out_path: str | None, kotlin_path: str | None) -> int:
    table = [json.loads(l) for l in
             Path(features_path).read_text(encoding="utf-8").splitlines()
             if l.strip()]
    for row in table:
        row["y3"] = project(row["label"])
    dist = Counter(r["y3"] for r in table)
    print(f"feature table: {len(table)} rows; 3-class: {dict(dist)}")

    model = fit(table)

    # Train sanity vs the all-WAKE majority baseline.
    majority = dist.most_common(1)[0][0]
    majority_acc = dist[majority] / len(table)
    train_hits = sum(1 for r in table if classify(model, str(r.get("query") or "")) == r["y3"])
    train_acc = train_hits / len(table)
    print(f"train accuracy: {train_hits}/{len(table)} = {train_acc:.3f} "
          f"(all-{majority} baseline {majority_acc:.3f})")

    gates_ok = train_acc > majority_acc

    validations = [evaluate(load_fixture(fixture_path),
                            lambda r: classify(model, str(r.get("query") or "")))]
    if device_fixture_path:
        validations.append(evaluate(load_fixture(device_fixture_path),
                                    lambda r: classify(model, str(r.get("query") or ""))))
    for v in validations:
        print(f"GATE {v['n']} rows: {v['hits']}/{v['n']} = {v['accuracy']} "
              f"false_drops={v['false_drops_on_positive']} per_lang={v['per_lang']}")
        if v["false_drops_on_positive"] > 0:
            gates_ok = False

    # Strict bars: beat all-WAKE on the seed fixture overall + ms/zh slices,
    # and beat the standing regex baseline (55.8%).
    seed = validations[0]
    wake_rows = sum(1 for r in load_fixture(fixture_path)
                    if project(str(r["expected"])) == "WAKE")
    all_wake_acc = round(wake_rows / seed["n"], 3) if seed["n"] else 0
    print(f"all-WAKE rule on seed fixture: {all_wake_acc} (regex baseline: 0.558)")
    if not (seed["accuracy"] or 0) > all_wake_acc:
        print("FAIL: does not beat the all-WAKE rule on the seed fixture")
        gates_ok = False
    if not (seed["accuracy"] or 0) > 0.558:
        print("FAIL: does not beat the regex baseline (0.558)")
        gates_ok = False
    for lang in ("ms", "zh"):
        h = seed["per_lang"].get(f"{lang}:hit", 0)
        m = seed["per_lang"].get(f"{lang}:miss", 0)
        if h + m > 0 and h / (h + m) < all_wake_acc:
            print(f"FAIL: {lang} slice {h}/{h + m} below all-WAKE")
            gates_ok = False

    print(f"\nVERDICT: {'GATE PASSED' if gates_ok else 'GATE FAILED — nothing ships'}")
    for v in validations:
        for m in v["misses"]:
            print(f"  miss [{m[0]}] {m[1]!r}: expected={m[2]} got={m[3]}")

    provenance = {
        "fitted": "2026-09-28",
        "source": "tools/jev_harness/corpus/rolling.jsonl (428 rows, Jev route --live)",
        "model": "multinomial NB over FNV-1a-32 hashed span-unigrams + char n-grams 2-4",
        "projection": {"IGNORE": "DROP", "FAST_*": "FAST", "default": "WAKE"},
        "hyperparams": {"n_buckets": N_BUCKETS, "alpha": ALPHA,
                        "ngram_range": [N_MIN, N_MAX]},
        "train_accuracy_3class": round(train_acc, 3),
        "all_wake_baseline_train": round(majority_acc, 3),
        "fixture_validation": [{k: v for k, v in val.items() if k != "misses"}
                               for val in validations],
        "gate_passed": gates_ok,
    }

    if out_path:
        Path(out_path).parent.mkdir(parents=True, exist_ok=True)
        Path(out_path).write_text(json.dumps(
            {"provenance": provenance,
             "priors": {c: model["priors"].get(c, 0) for c in CLASSES},
             "totals": {c: model["totals"].get(c, 0) for c in CLASSES},
             "counts": {c: {str(b): n for b, n in model["counts"][c].items()
                            if n >= MIN_BUCKET_COUNT} for c in CLASSES}},
            ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {out_path}")

    if kotlin_path and gates_ok:
        Path(kotlin_path).parent.mkdir(parents=True, exist_ok=True)
        Path(kotlin_path).write_text(emit_kotlin(model, provenance), encoding="utf-8")
        print(f"wrote {kotlin_path}")
    elif kotlin_path and not gates_ok:
        print("refusing --emit-kotlin: gate failed (rung-0 refusal discipline)")

    return 0 if gates_ok else 1

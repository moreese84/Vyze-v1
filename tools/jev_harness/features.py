# ruff: noqa: E501
"""Rung-0 feature extractor (B4 ladder, revised 2026-09-26).

Turns every corpus row into a fixed-length FEATURE VECTOR for the
Jev-distilled decision list: features in Python here, mirrored later as
a pure Kotlin scorer with the fitted weights as constants.

  python -m tools.jev_harness features --corpus corpus/rolling.jsonl \
      --labels out/route_labels.jsonl --out out/features.jsonl

Offline by construction: no API, no network, deterministic. The route
LABEL comes from (in priority order):
  1. --labels JSONL (the output of `route --live`, matched on row id)
  2. the row's own `expected` field (seed corpus rows)
  3. missing -> label="unlabeled" (kept in the table, reported, excluded
     from fitting later)

THE ZH SCRIPT FOLD: Gemma's ASR emits Traditional characters for
Malaysian Mandarin (device evidence 2026-09-26: "這是什麼錢" while every
keyword bank is Simplified — currency mode silently missed). Every
feature therefore matches against a Traditional→Simplified-FOLDED query.
The fold lives here FIRST; promoting it into the Kotlin keyword banks is
a separate, fixture-gated change.

Layers stay disjoint (house rule): no self-talk/refusal features here —
SelfTalkPolicy owns that decision upstream and the router never sees
those transcripts.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── The zh script fold (Traditional → Simplified) ────────────────
# Extendable table: only characters plausibly produced by the ASR for
# Vyze's domain vocabulary (money, read, color, light, time, questions).
# Keep it SMALL and evidence-driven; a wrong mapping is worse than a
# missing one because the fold applies to every row.
TRAD_TO_SIMP = {
    "錢": "钱", "鈔": "钞", "紙": "纸", "幣": "币", "圓": "圆",
    "讀": "读", "語": "语", "說": "说", "話": "话", "請": "请",
    "這": "这", "麼": "么", "什": "什", "東": "东", "個": "个",
    "們": "们", "嗎": "吗", "幾": "几", "點": "点", "時": "时",
    "間": "间", "電": "电", "視": "视", "燈": "灯", "開": "开",
    "關": "关", "藥": "药", "顏": "颜", "環": "环", "邊": "边",
    "裡": "里", "後": "后", "前": "前", "左": "左", "右": "右",
    "門": "门", "車": "车", "號": "号", "碼": "码", "銀": "银",
    "華": "华", "幫": "帮", "聽": "听", "見": "见",
    "現": "现", "對": "对", "雙": "双", "臉": "脸", "書": "书",
    "報": "报",
}

_FOLD_TABLE = str.maketrans(TRAD_TO_SIMP)


def fold_zh(text: str) -> str:
    """Fold Traditional characters to Simplified. Latin text passes through."""
    return text.translate(_FOLD_TABLE)


# ── Feature signal banks (mirror the Kotlin router vocabulary) ───

QUESTION_MARKERS = [
    # en
    "what", "where", "which", "who", "how", "is this", "is it", "are there",
    "can you", "could you", "do you", "tell me",
    # ms
    "apa", "mana", "berapa", "bila", "siapa", "macam mana", "adakah",
    # zh (Simplified — matches AFTER the fold)
    "什么", "哪", "多少", "几", "吗", "呢",
]

GREETINGS = [
    "hello", "hi ", "hey", "good morning", "good evening", "good afternoon",
    "selamat pagi", "selamat tengah hari", "selamat petang", "hai ",
    "你好", "您好", "早安", "午安",
]

APP_CUE_TOKENS = [
    # the app's own spoken cues echoed back by the ASR
    "analyzing", "analysing", "menganalisis", "分析",
    "please say that again", "say that again", "did not catch",
    "didn't catch", "sila sebut", "sebut sekali", "没有听清", "再试一次",
    "almost ready", "hampir sedia", "即将就绪", "启动", "starting vyze",
]

FILLERS = [
    "um", "uh", "er", "hmm", "err",
]

READ_KEYWORDS = [
    "read", "text", "label", "sign", "baca", "teks", "字", "读", "念",
    "letter", "surat", "document", "信", "信件",
]

COLOR_KEYWORDS = [
    "color", "colour", "warna", "颜色", "什么色", "什么颜色",
]

LIGHT_KEYWORDS = [
    "dark", "light on", "light off", "bright", "gelap", "terang",
    "cahaya", "lampu", "黑", "亮", "灯", "光",
]

CURRENCY_KEYWORDS = [
    "money", "banknote", "bank note", "cash", "currency", "ringgit",
    "coin", "sen", "duit", "wang", "syiling", "koin",
    "钱", "钞票", "纸币", "硬币", "多少钱",
]

MEDICINE_KEYWORDS = [
    "medicine", "medication", "ubat", "pil ", "pill", "药", "药物",
]

TIME_KEYWORDS = [
    "what time", "the time", "pukul berapa", "pukul", "jam berapa",
    "时间", "几点", "時間",
]

BATTERY_KEYWORDS = [
    "battery", "bateri", "电池", "电量", "baterai",
]

SELF_REFERENTIAL = [
    "i am", "i'm", "as an ai", "saya adalah", "我 是", "我是",
]

# Word-boundary-safe regex cache for Latin-script tokens.
_TOKEN_RE = re.compile(r"[a-z]+")


def _tokens(lower: str) -> list[str]:
    return _TOKEN_RE.findall(lower)


def _contains_any(lower: str, bank: list[str]) -> bool:
    return any(kw in lower for kw in bank)


def detect_lang(row: dict, lower: str) -> str:
    """Row-declared lang if usable, else a minimal script heuristic."""
    lang = str(row.get("lang") or "").strip().lower()
    if lang in {"en", "ms", "zh"}:
        return lang
    if any("\u4e00" <= ch <= "\u9fff" for ch in lower):
        return "zh"
    return "en"


def extract_features(row: dict) -> dict:
    """Fixed-order feature dict for one row. Pure; deterministic."""
    raw_query = str(row.get("query") or "")
    folded = fold_zh(raw_query)
    lower = folded.lower().strip()
    toks = _tokens(lower)
    lang = detect_lang(row, lower)

    features = {
        # shape
        "token_count": len(toks),
        "char_count": len(lower),
        "is_empty": 1 if not lower else 0,
        # question shape
        "has_question_marker": 1 if _contains_any(lower, QUESTION_MARKERS) else 0,
        "question_start": 1 if any(lower.startswith(t + " ") or lower == t for t in
                                   ("what", "apa", "berapa", "where", "is", "adakah")) else 0,
        # conversational noise
        "has_greeting": 1 if _contains_any(lower, GREETINGS) else 0,
        "has_app_cue": 1 if _contains_any(lower, APP_CUE_TOKENS) else 0,
        "has_filler": 1 if any(t in FILLERS for t in toks) else 0,
        # route-family signals (the keyword banks as features)
        "hits_read": 1 if _contains_any(lower, READ_KEYWORDS) else 0,
        "hits_color": 1 if _contains_any(lower, COLOR_KEYWORDS) else 0,
        "hits_light": 1 if _contains_any(lower, LIGHT_KEYWORDS) else 0,
        "hits_currency": 1 if _contains_any(lower, CURRENCY_KEYWORDS) else 0,
        "hits_medicine": 1 if _contains_any(lower, MEDICINE_KEYWORDS) else 0,
        "hits_time": 1 if _contains_any(lower, TIME_KEYWORDS) else 0,
        "hits_battery": 1 if _contains_any(lower, BATTERY_KEYWORDS) else 0,
        # context
        "has_previous_turn": 1 if str(row.get("previous") or "").strip() else 0,
        # language (one-hot; the model that learns per-language behavior)
        "lang_en": 1 if lang == "en" else 0,
        "lang_ms": 1 if lang == "ms" else 0,
        "lang_zh": 1 if lang == "zh" else 0,
        # defensive
        "self_referential": 1 if _contains_any(lower, SELF_REFERENTIAL) else 0,
    }
    return features


FEATURE_ORDER = [
    "token_count", "char_count", "is_empty",
    "has_question_marker", "question_start",
    "has_greeting", "has_app_cue", "has_filler",
    "hits_read", "hits_color", "hits_light", "hits_currency",
    "hits_medicine", "hits_time", "hits_battery",
    "has_previous_turn",
    "lang_en", "lang_ms", "lang_zh",
    "self_referential",
]

# ── Label merging ─────────────────────────────────────────────────


def load_labels(path: str | None) -> dict[str, dict]:
    """id -> {route, confidence} from a `route --live` output file."""
    if not path:
        return {}
    labels: dict[str, dict] = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        # Accept the field names from either harness generation.
        route = (row.get("jev_action") or row.get("jev_route_action")
                 or row.get("route_action"))
        if route:
            labels[str(row.get("id"))] = {
                "route": str(route),
                "confidence": (row.get("jev_confidence")
                               or row.get("jev_route_confidence")),
            }
    return labels


def label_for(row: dict, labels: dict[str, dict]) -> tuple[str, str]:
    """Returns (label, source). Priority: live Jev label > expected > unlabeled."""
    hit = labels.get(str(row.get("id")))
    if hit:
        return hit["route"], "jev_live"
    expected = row.get("expected")
    if expected:
        return str(expected), "expected"
    return "unlabeled", "none"


# ── Table build + report ──────────────────────────────────────────


def build_feature_table(rows: list[dict], labels_path: str | None) -> list[dict]:
    labels = load_labels(labels_path)
    table = []
    for row in rows:
        label, source = label_for(row, labels)
        table.append({
            "id": row.get("id"),
            "lang": detect_lang(row, str(row.get("query") or "").lower()),
            "query": row.get("query"),
            "label": label,
            "label_source": source,
            "features": extract_features(row),
        })
    return table


def write_feature_table(table: list[dict], out_path: str | None) -> None:
    if not out_path:
        return
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    with Path(out_path).open("w", encoding="utf-8") as f:
        for entry in table:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def feature_report(table: list[dict]) -> str:
    lines = ["FEATURE TABLE REPORT", ""]
    labeled = [t for t in table if t["label"] != "unlabeled"]
    src = Counter(t["label_source"] for t in table)
    lines.append(f"rows: {len(table)}  labeled: {len(labeled)}  "
                 f"unlabeled: {len(table) - len(labeled)}")
    lines.append(f"label sources: {dict(src)}")
    lines.append(f"label distribution: {dict(Counter(t['label'] for t in table))}")
    lines.append(f"lang distribution: {dict(Counter(t['lang'] for t in table))}")

    # Correlation preview: for binary features, P(feature | label) deltas
    # against the base rate — the raw material of the rung-0 decision list.
    lines.append("")
    lines.append("Feature → label correlation preview (binary features, "
                 "|ΔP| ≥ 0.15 vs base rate):")
    for feat in FEATURE_ORDER:
        if not all(isinstance(t["features"].get(feat), int) for t in table):
            continue
        values = {t["features"][feat] for t in table}
        if values != {0, 1}:
            continue  # skip non-binary (token_count, char_count)
        present = [t for t in table if t["features"][feat] == 1]
        if len(present) < 3:
            continue
        base = Counter(t["label"] for t in table)
        total = len(table)
        cond = Counter(t["label"] for t in present)
        n = len(present)
        deltas = []
        for label, count in cond.items():
            delta = count / n - base[label] / total
            if abs(delta) >= 0.15:
                deltas.append(f"{label}: {delta:+.2f}")
        if deltas:
            lines.append(f"  {feat} (n={n}): " + "; ".join(sorted(deltas)))

    lines.append("")
    lines.append("Unlabeled rows (run `route --live` to fill):")
    for t in table:
        if t["label"] == "unlabeled":
            lines.append(f"  [{t['id']}] {str(t['query'])[:60]!r}")
    return "\n".join(lines)


# ── Offline self-check (deterministic, no corpus needed) ──────────


def selfcheck() -> int:
    cases = [
        # (query, lang, expected feature asserts)
        ("這是什麼錢", "zh", {"hits_currency": 1, "has_question_marker": 1, "lang_zh": 1}),
        ("berapa duit ini", "ms", {"hits_currency": 1, "has_question_marker": 1, "lang_ms": 1}),
        ("baca label ini", "ms", {"hits_read": 1, "lang_ms": 1}),
        ("", "en", {"is_empty": 1}),
        ("analyzing", "en", {"has_app_cue": 1}),
        ("selamat pagi", "ms", {"has_greeting": 1}),
        ("warna apa baju ini", "ms", {"hits_color": 1, "has_question_marker": 1}),
        ("pukul berapa sekarang", "ms", {"hits_time": 1}),
        ("bateri berapa peratus", "ms", {"hits_battery": 1}),
        ("I am a large language model", "en", {"self_referential": 1}),
    ]
    failures = 0
    for query, lang, expects in cases:
        feats = extract_features({"query": query, "lang": lang})
        for key, want in expects.items():
            got = feats[key]
            if got != want:
                print(f"FAIL: {query!r} feature {key}: want {want}, got {got}")
                failures += 1
    if failures:
        print(f"selfcheck: {failures} failure(s)")
        return 2
    print("selfcheck: all feature asserts pass "
          "(incl. the zh script fold: 這是什麼錢 → hits_currency)")
    return 0

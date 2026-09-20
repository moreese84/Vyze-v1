# ruff: noqa: E501
"""Harness runners: route-labeling and compliance-audit passes over corpora.

Both runners work in two modes:
  live     — requires TYPESAFE_API_KEY; calls the TypeSafe System One API
             (model: jev-latest unless --model overrides).
  dry-run  — no network, no SDK import needed at runtime; emits rows with
             Jev fields set to None so the whole downstream pipeline
             (JSONL output, reports) can be smoke-tested offline.

Errors follow the SDK taxonomy (typesafe_sdk.exceptions): rate limits and
transient server errors are retried by the SDK's RetryPolicy; everything
else is recorded on the row and the run continues — one bad row never
kills a labeling pass.
"""

import json
import time
from pathlib import Path

from .audit_judgment import (
    audit_questions,
    build_state as audit_state,
    extract as audit_extract,
    lane_of,
)
from .regex_baseline import decide_speech
from .router_judgment import build_state as route_state, extract as route_extract, route_questions

MODEL_DEFAULT = "jev-latest"


def _client(model: str):
    """Build a sync client with a conservative retry policy."""
    from typesafe_sdk import RetryPolicy, TypeSafeClient

    return TypeSafeClient(
        model=model,
        retry=RetryPolicy(max_retries=3),
        timeout=30.0,
    )


def load_corpus(path: str) -> list[dict]:
    """Corpus file: .py with CORPUS, or .json/.jsonl of row dicts."""
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if p.suffix == ".jsonl":
        return [json.loads(line) for line in text.splitlines() if line.strip()]
    if p.suffix == ".json":
        return json.loads(text)
    if p.suffix == ".py":
        namespace: dict = {}
        exec(compile(text, str(p), "exec"), namespace)  # trusted local corpus files only
        return list(namespace["CORPUS"])
    raise ValueError(f"Unsupported corpus format: {p.suffix}")


def route_labels(items: list[dict], *, live: bool, model: str = MODEL_DEFAULT,
                 sleep_s: float = 0.2) -> list[dict]:
    """Label every corpus row with regex baseline + (live) Jev routing."""
    rows: list[dict] = []
    if not live:
        for item in items:
            rows.append({
                "id": item["id"], "lang": item.get("lang"), "query": item["query"],
                "previous": item.get("previous"), "expected": item.get("expected"),
                "regex_action": decide_speech(item["query"]),
                "jev_action": None, "jev_probabilities": None,
                "jev_confidence": None, "jev_fast_path_noul": None,
                "error": "dry-run",
            })
        return rows

    client = _client(model)
    with client:
        for i, item in enumerate(items):
            row = {
                "id": item["id"], "lang": item.get("lang"), "query": item["query"],
                "previous": item.get("previous"), "expected": item.get("expected"),
                "regex_action": decide_speech(item["query"]),
            }
            try:
                result = client.system_one(
                    state=route_state(item), questions=route_questions(),
                )
                row.update(route_extract(result))
                usage = getattr(result, "usage", None)
                if usage is not None:
                    row["input_tokens"] = getattr(usage, "input_tokens", None)
                    row["output_tokens"] = getattr(usage, "output_tokens", None)
                row["error"] = None
            except Exception as exc:  # noqa: BLE001 — record, don't kill the pass
                row.update({
                    "jev_action": None, "jev_probabilities": None,
                    "jev_confidence": None, "jev_fast_path_noul": None,
                    "error": f"{type(exc).__name__}: {exc}"[:300],
                })
            rows.append(row)
            done, total = i + 1, len(items)
            err = f"  ERROR {row['error']}" if row["error"] else ""
            print(f"  [{done}/{total}] {row['id']} regex={row['regex_action']} "
                  f"jev={row.get('jev_action')}{err}")
            if sleep_s:
                time.sleep(sleep_s)
    return rows


def audit_transcripts(items: list[dict], *, live: bool, model: str = MODEL_DEFAULT,
                      echo_threshold: float = 0.5, lang_threshold: float = 0.5,
                      sleep_s: float = 0.2) -> list[dict]:
    """Audit (query, answer) transcript rows for contract compliance.

    Rows need: query, answer; optional: previous, lang, lane.
    Thresholds default to 0.5 (the probability midpoint) — they are run
    parameters, NOT universal rules; sweep them in reports if needed.

    Lane handling (v2): tap-lane rows ("User tapped at position …") carry
    no spoken language, so their language-mirror judgment is recorded but
    NEVER counted as a mismatch — only echo and relevance still apply.
    """
    usable = [i for i in items if i.get("answer") is not None]
    skipped = len(items) - len(usable)
    if skipped:
        print(f"Audit: skipping {skipped} row(s) without an 'answer' field")
    items = usable
    rows: list[dict] = []
    if not live:
        for item in items:
            rows.append({
                "id": item.get("id"), "lang": item.get("lang"),
                "query": item["query"], "answer": item["answer"],
                "lane": lane_of(item),
                "audit_echoed_noul": None, "audit_language_match_noul": None,
                "audit_relevance_level": None, "audit_relevance_probabilities": None,
                "jev_query_language": None, "jev_query_language_confidence": None,
                "error": "dry-run",
            })
        return rows

    client = _client(model)
    with client:
        for i, item in enumerate(items):
            row = {"id": item.get("id"), "lang": item.get("lang"),
                   "query": item["query"], "answer": item["answer"],
                   "lane": lane_of(item)}
            try:
                result = client.system_one(
                    state=audit_state(item), questions=audit_questions(),
                )
                row.update(audit_extract(result))
                row["echoed_flag"] = row["audit_echoed_noul"] >= echo_threshold
                row["language_mismatch_flag"] = (
                    row["lane"] != "tap"
                    and row["audit_language_match_noul"] < lang_threshold
                )
                row["error"] = None
            except Exception as exc:  # noqa: BLE001
                row.update({
                    "audit_echoed_noul": None, "audit_language_match_noul": None,
                    "audit_relevance_level": None,
                    "audit_relevance_probabilities": None,
                    "jev_query_language": None, "jev_query_language_confidence": None,
                    "echoed_flag": None, "language_mismatch_flag": None,
                    "error": f"{type(exc).__name__}: {exc}"[:300],
                })
            rows.append(row)
            done, total = i + 1, len(items)
            err = f"  ERROR {row['error']}" if row["error"] else ""
            print(f"  [{done}/{total}] {row['id']} echo={row.get('audit_echoed_noul')} "
                  f"lang={row.get('audit_language_match_noul')}{err}")
            if sleep_s:
                time.sleep(sleep_s)
    return rows


def write_jsonl(rows: list[dict], path: str) -> None:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} rows → {out}")

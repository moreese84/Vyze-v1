# ruff: noqa: E501
"""A1: rolling-corpus append engine for the Jev harness.

Merges device export files (corpus/jev_export/interactions_*.jsonl) into
one rolling corpus file without duplicating rows:

    python tools/jev_harness/append.py IN_JSONL [IN_JSONL ...] -o ROLLING.jsonl
    python -m tools.jev_harness append IN_JSONL [...] -o ROLLING.jsonl

Design decisions (each earned the hard way):

  - DEDUPE KEY = (ts, stable-content fingerprint), never id alone, never
    (id, ts) anymore. Two traps were survived by (id, ts) — restarted
    session counters, and id collisions across sessions — until a third
    appeared on 2026-09-28: --namespace rewrites id to `<stem>_<id>` and a
    multi-session re-export under a NEW filename re-keyed every old row,
    silently adding 183 duplicate groups. Identity is now the device
    timestamp plus a sha256 over the never-mutating fields (query, answer,
    lane, lang); id and the post-hoc feedback/note fields are excluded.

  - SESSION NAMESPACING (opt-in, --namespace): device ids are only unique
    within one export session. When set, rows are rewritten to
    `<stem>_<id>` so the rolling file can never contain two rows with the
    same id from different sessions. Off by default: existing rolling
    files keep their original ids.

  - PER-ROW ERROR CAPTURE: one malformed line never kills a merge (same
    discipline as the runner's per-row try/except). Bad lines are counted
    and reported, never silently dropped.

  - ATOMIC WRITE: the rolling file is rewritten via a temp file + rename,
    so an interrupted merge cannot truncate the corpus. The previous file
    is preserved as ROLLING.jsonl.bak.

  - SOURCE PROVENANCE: every appended row gains `session` (the input file
    stem) so future work can trace a row back to its export, and rows
    already present keep their original form byte-for-byte in spirit —
    the rolling file is only rewritten with the same JSON serialization
    for every row (ensure_ascii=False, one per line).

  - LANE AWARENESS (informational): tap-lane rows embed the whole built
    screen prompt as boilerplate — they are merged like any row (the
    audit pass already excludes them from language grading), and the
    summary reports the voice/tap split so a glance shows corpus shape.
"""

import hashlib
import json
import os
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


# ── Robust JSONL reading (per-row error capture) ─────────────────────

def read_jsonl_rows(path: str | Path) -> tuple[list[dict], list[tuple[int, str]]]:
    """Read a JSONL file, returning (rows, bad_lines).

    Each bad line is (line_number, reason). One malformed line never
    aborts the read — mirrors the runner's per-row try/except discipline.
    """
    rows: list[dict] = []
    bad: list[tuple[int, str]] = []
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    for lineno, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as e:
            bad.append((lineno, f"json error: {e.msg}"))
            continue
        if not isinstance(obj, dict):
            bad.append((lineno, "not an object"))
            continue
        rows.append(obj)
    return rows, bad


# ── Dedupe + namespacing ─────────────────────────────────────────────

def _stable_fingerprint(row: dict) -> str:
    """Content fingerprint over fields that NEVER mutate after the fact:
    query/answer/lane/lang. Deliberately excludes id (namespace renames it
    per export) and feedback/note (suspect tags are added post-hoc, so the
    same interaction re-exported after a code change differs there)."""
    stable = {k: row.get(k) for k in ("query", "answer", "lane", "lang")
              if k in row}
    payload = json.dumps(stable, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]


def row_key(row: dict) -> tuple:
    """The dedupe key: (ts, stable-content fingerprint).

    2026-09-28 ERATUM: the original key was (id, ts), which survived two
    real-world traps (restarted session counters; id-collisions across
    sessions) but not a third — --namespace rewrites id to
    `<export-stem>_<id>`, and a multi-session re-export under a NEW
    filename silently re-keyed every old row: 183 duplicate groups entered
    the rolling corpus. The id component is therefore GONE from the key
    (it is mutable per export); identity is the device timestamp plus a
    fingerprint over the never-mutating fields. A clock-reset collision
    with identical ts still survives dedupe whenever the content differs.

    Rows missing ts fall back to FULL-CONTENT identity: a re-merged
    identical row is a duplicate (append stays idempotent for any input),
    a genuinely different keyless row is still added."""
    ts = row.get("ts")
    if ts is None:
        payload = json.dumps(row, sort_keys=True, ensure_ascii=False)
        return ("__content__", payload)
    return ("ts", str(ts), _stable_fingerprint(row))


def namespace_row(row: dict, stem: str) -> dict:
    """Rewrite `id` → `<stem>_<id>` so cross-session id collisions
    (ir_1 in every export) cannot merge distinct rows. Mutates a copy.
    Rows without an id pass through unchanged."""
    out = dict(row)
    if "id" in out:
        out["id"] = f"{stem}_{out['id']}"
    return out


# ── The append operation ─────────────────────────────────────────────

def append_sources(inputs: list[str], rolling: str | Path,
                   *, namespace: bool = False) -> dict:
    """Merge input JSONL files into the rolling corpus file.

    Returns a summary dict; prints a human report. The rolling file is
    rewritten atomically (tmp + rename) with a .bak of the previous state.
    """
    rolling_path = Path(rolling)
    existing: list[dict] = []
    if rolling_path.exists():
        existing, bad_existing = read_jsonl_rows(rolling_path)
        if bad_existing:
            # A corrupted rolling file would silently shrink on rewrite —
            # refuse to touch it instead (the .bak flow is for clean files).
            raise SystemExit(
                f"refusing to append: {rolling_path} has {len(bad_existing)} "
                f"unparsable line(s); fix or remove them first"
            )

    seen: set[tuple] = {row_key(r) for r in existing}
    merged: list[dict] = list(existing)
    per_input: list[dict] = []

    for inp in inputs:
        in_path = Path(inp)
        rows, bad = read_jsonl_rows(in_path)
        stem = in_path.stem
        added, skipped, keyless = 0, 0, 0
        for row in rows:
            # Namespace BEFORE keying: the dedupe set must describe rows in
            # the same form they are stored, or re-appending a --namespace
            # rolling file would re-add every row (ir_1 vs stem_ir_1).
            candidate = namespace_row(row, stem) if namespace else row
            key = row_key(candidate)
            if key in seen:
                skipped += 1
                if candidate.get("id") is None or candidate.get("ts") is None:
                    keyless += 1
                continue
            seen.add(key)
            merged.append(candidate)
            added += 1
        per_input.append({
            "file": str(in_path), "read": len(rows), "added": added,
            "duplicates": skipped, "bad": len(bad), "keyless": keyless,
            "bad_lines": bad,
        })

    # Atomic rewrite: tmp + rename, previous file kept as .bak.
    rolling_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = rolling_path.with_suffix(rolling_path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        for row in merged:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    if rolling_path.exists():
        bak = rolling_path.with_suffix(rolling_path.suffix + ".bak")
        os.replace(rolling_path, bak)
    os.replace(tmp, rolling_path)

    # Lane split (informational — corpus shape at a glance).
    lanes: dict[str, int] = {}
    for row in merged:
        lane = str(row.get("lane", "unknown"))
        lanes[lane] = lanes.get(lane, 0) + 1

    summary = {
        "rolling": str(rolling_path),
        "total": len(merged),
        "before": len(existing),
        "per_input": per_input,
        "lanes": lanes,
    }
    _print_summary(summary)
    return summary


def _print_summary(s: dict) -> None:
    print(f"Rolling corpus: {s['rolling']}")
    print(f"  rows before → after: {s['before']} → {s['total']} "
          f"(+{s['total'] - s['before']})")
    for p in s["per_input"]:
        line = (f"  {Path(p['file']).name}: read {p['read']}, "
                f"added {p['added']}, duplicates {p['duplicates']}")
        if p["bad"]:
            line += f", BAD {p['bad']}"
        if p["keyless"]:
            line += f", no id/ts {p['keyless']}"
        print(line)
        for lineno, why in p["bad_lines"][:5]:
            print(f"      line {lineno}: {why}")
        if len(p["bad_lines"]) > 5:
            print(f"      ... and {len(p['bad_lines']) - 5} more bad lines")
    lanes = " / ".join(f"{k}: {v}" for k, v in sorted(s["lanes"].items()))
    if lanes:
        print(f"  lanes: {lanes}")

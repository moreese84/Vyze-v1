# ruff: noqa: E501
"""Self-talk labeling pass: Jev classes every voice-lane corpus row, then
the pass reports DISAGREEMENTS with the on-device SelfTalkPolicy pattern
list — the promotion signal for the next pattern revision.

  python -m tools.jev_harness selftalk --corpus tools/jev_harness/corpus/rolling.jsonl --live
  python -m tools.jev_harness selftalk --corpus ... --live --out out/selftalk_report.txt

Dry-run works offline (no SDK, no network) and emits None fields.
"""

import json
import time
from pathlib import Path

from .selftalk_judgment import build_state, extract, selftalk_questions

MODEL_DEFAULT = "jev-latest"

# Rows Jev flags as NOT clean user speech.
SUSPECT_CLASSES = {"model_self_talk", "not_user_content"}


def _client(model: str):
    from typesafe_sdk import RetryPolicy, TypeSafeClient

    return TypeSafeClient(model=model, retry=RetryPolicy(max_retries=3), timeout=30.0)


def load_rows(path: str) -> list[dict]:
    text = Path(path).read_text(encoding="utf-8")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def selftalk_labels(items: list[dict], *, live: bool, model: str = MODEL_DEFAULT,
                    sleep_s: float = 0.2) -> list[dict]:
    """Classify every row; on-device policy verdict rides along for comparison."""
    from .selftalk_device_mirror import device_policy_verdict

    rows: list[dict] = []
    client = _client(model) if live else None
    questions = selftalk_questions() if live else None

    for item in items:
        query = str(item.get("query", ""))
        row = dict(item)
        row.update({"jev_selftalk_class": None, "jev_selftalk_confidence": None})
        row["device_selftalk"] = device_policy_verdict(query)

        if live and query.strip():
            try:
                state = build_state(item)
                judgment = client.decide(questions["selftalk_class"], state)
                row.update(extract(item, judgment))
                time.sleep(sleep_s)
            except Exception as e:  # one bad row never kills the pass
                row["selftalk_error"] = f"{type(e).__name__}: {e}"
        rows.append(row)
    return rows


def write_report(rows: list[dict], out_path: str | None) -> None:
    """Console + file report: agreement rate and the misses that matter."""
    ok = [r for r in rows if r.get("jev_selftalk_class")]
    lines = ["SELF-TALK TEACHER REPORT", ""]

    if not ok:
        lines.append("No live Jev rows (dry-run or all errored) — nothing to compare.")
        report = "\n".join(lines)
        print(report)
        if out_path:
            Path(out_path).write_text(report, encoding="utf-8")
        return

    # The gate: rows Jev calls suspect that the DEVICE POLICY passed clean.
    misses = [
        r for r in ok
        if r["jev_selftalk_class"] in SUSPECT_CLASSES and not r["device_selftalk"]
    ]
    # False positives: device dropped, Jev says genuine user speech.
    false_drops = [
        r for r in ok
        if r["jev_selftalk_class"] == "user_speech" and r["device_selftalk"]
    ]

    lines.append(f"rows compared:            {len(ok)}")
    lines.append(f"Jev-flagged suspect:      {sum(1 for r in ok if r['jev_selftalk_class'] in SUSPECT_CLASSES)}")
    lines.append(f"MISSED BY DEVICE POLICY:  {len(misses)}  <- next pattern revision")
    lines.append(f"device false drops:       {len(false_drops)}  <- patterns too aggressive")
    if misses:
        lines.append("")
        lines.append("Missing shapes (add to SelfTalkPolicy.SELF_TALK_PATTERNS):")
        for r in misses[:15]:
            lines.append(f"  [{r['jev_selftalk_class']}] {str(r.get('query'))[:80]!r}")
    if false_drops:
        lines.append("")
        lines.append("Over-dropped (pattern too wide):")
        for r in false_drops[:15]:
            lines.append(f"  {str(r.get('query'))[:80]!r}")

    report = "\n".join(lines)
    print(report)
    if out_path:
        Path(out_path).write_text(report, encoding="utf-8")

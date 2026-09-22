# ruff: noqa: E501
"""CLI for the Jev harness.

  python -m tools.jev_harness route [--corpus FILE] [--out FILE] [--live]
  python -m tools.jev_harness audit --corpus FILE [--out FILE] [--live]
  python -m tools.jev_harness append EXPORT_JSONL [...] -o ROLLING.jsonl

Dry-run (default) needs no API key and exercises the full pipeline
offline. --live requires TYPESAFE_API_KEY in the environment and the
typesafe-sdk package installed. `append` is always offline (A1: rolling
corpus growth — dedupes on id+ts, never drops rows silently).
"""

import argparse
import sys
from pathlib import Path

# Windows consoles default to a legacy codepage (cp1252); reports contain
# non-ASCII (queries in zh/ms, arrows). Reconfigure to UTF-8 before printing.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from .queries import CORPUS
from .reports import audit_report, routing_report
from .runners import MODEL_DEFAULT, audit_transcripts, load_corpus, route_labels, write_jsonl


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="tools.jev_harness",
        description="Vyze × TypeSafe Jev evaluation harness (Phase 0).",
    )
    parser.add_argument("--model", default=MODEL_DEFAULT,
                        help=f"Jev model name (default: {MODEL_DEFAULT})")
    sub = parser.add_subparsers(dest="cmd", required=True)

    def add_common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--corpus", default=None,
                       help="corpus file: .py (CORPUS list), .json, or .jsonl; "
                            "default = built-in seed corpus")
        p.add_argument("--out", default=None,
                       help="output JSONL path (default: prints report only)")
        p.add_argument("--live", action="store_true",
                       help="call the TypeSafe API (requires TYPESAFE_API_KEY)")
        p.add_argument("--sleep", type=float, default=0.2,
                       help="seconds between live API calls (default: 0.2)")

    p_route = sub.add_parser("route", help="label queries: regex + Jev routing")
    add_common(p_route)

    p_audit = sub.add_parser("audit", help="audit transcripts: echo/language/relevance")
    add_common(p_audit)

    p_append = sub.add_parser(
        "append",
        help="A1: merge device exports into a rolling corpus file",
    )
    p_append.add_argument("inputs", nargs="+",
                          help="export JSONL files (corpus/jev_export/interactions_*.jsonl)")
    p_append.add_argument("-o", "--out", required=True,
                          help="rolling corpus JSONL path (created if missing, .bak kept)")
    p_append.add_argument("--namespace", action="store_true",
                          help="rewrite ids as <session-stem>_<id> so cross-session "
                               "id collisions (ir_1 in every export) cannot merge rows")

    args = parser.parse_args(argv)

    # A1 append never needs a corpus/model — dispatch before that machinery.
    if args.cmd == "append":
        from .append import append_sources
        append_sources(args.inputs, args.out, namespace=args.namespace)
        return 0

    corpus_path = args.corpus or "builtin"
    items = CORPUS if args.corpus is None else load_corpus(args.corpus)
    if not items:
        print(f"No rows found in {corpus_path}", file=sys.stderr)
        return 2
    print(f"Corpus: {corpus_path} ({len(items)} rows), model={args.model}, "
          f"mode={'LIVE' if args.live else 'dry-run'}")

    if args.cmd == "route":
        rows = route_labels(items, live=args.live, model=args.model,
                            sleep_s=args.sleep)
        print()
        print(routing_report(rows))
        out = args.out or "out/route_labels.jsonl"
    else:
        rows = audit_transcripts(items, live=args.live, model=args.model,
                                 sleep_s=args.sleep)
        print()
        print(audit_report(rows))
        out = args.out or "out/audit_results.jsonl"

    write_jsonl(rows, Path(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

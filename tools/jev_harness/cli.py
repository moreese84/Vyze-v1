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

    p_selftalk = sub.add_parser(
        "selftalk",
        help="L4 teacher: class who spoke (user/model/not-content) and "
             "compare against the on-device SelfTalkPolicy",
    )
    add_common(p_selftalk)

    p_features = sub.add_parser(
        "features",
        help="B4 rung 0: extract the feature table for the distilled "
             "decision list (offline; pairs corpus rows with route labels)",
    )
    add_common(p_features)
    p_features.add_argument(
        "--labels", default=None,
        help="route-labels JSONL (out/route_labels.jsonl from 'route --live'); "
             "falls back to each row's 'expected' field when absent",
    )

    p_fit = sub.add_parser(
        "fit_rung0",
        help="B4 rung 0: fit the 3-class decision list on the labeled "
             "feature table, validate on frozen fixtures, optionally emit "
             "the Kotlin classifier",
    )
    p_fit.add_argument("--features", required=True,
                       help="feature table JSONL (from 'features --out')")
    p_fit.add_argument("--fixture", required=True,
                       help="seed fixture JSONL (phase0_route_labels.jsonl)")
    p_fit.add_argument("--device-fixture", default=None,
                       help="device fixture JSONL (phase3_device_labels.jsonl)")
    p_fit.add_argument("--emit-kotlin", default=None,
                       help="emit the generated Kotlin classifier to this path")
    p_fit.add_argument("--out", default=None,
                       help="weights JSONL path (provenance + rules)")

    p_fit1 = sub.add_parser(
        "fit_rung1",
        help="B4 rung 1: fit the hashed char-n-gram Naive Bayes on the "
             "labeled feature table, gate on frozen fixtures (zero false "
             "drops, beats all-WAKE + regex baseline per language), "
             "optionally emit the Kotlin scorer",
    )
    p_fit1.add_argument("--features", required=True,
                        help="feature table JSONL (from 'features --out')")
    p_fit1.add_argument("--fixture", required=True,
                        help="seed fixture JSONL (phase0_route_labels.jsonl)")
    p_fit1.add_argument("--device-fixture", default=None,
                        help="device fixture JSONL (phase3_device_labels.jsonl)")
    p_fit1.add_argument("--emit-kotlin", default=None,
                        help="emit the generated Kotlin scorer to this path "
                             "(refused when the gate fails)")
    p_fit1.add_argument("--out", default=None,
                        help="model JSON path (provenance + counts)")

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

    # B4 rung 0 fitting: consumes the feature TABLE (not raw corpus rows)
    # and has no --corpus — dispatch before the corpus machinery.
    if args.cmd == "fit_rung0":
        from .fit_rung0 import run as fit_run
        return fit_run(args.features, args.fixture, args.device_fixture,
                       args.out, args.emit_kotlin)

    # B4 rung 1 fitting: same contract as rung 0, NB over hashed n-grams.
    if args.cmd == "fit_rung1":
        from .fit_rung1 import run as fit1_run
        return fit1_run(args.features, args.fixture, args.device_fixture,
                        args.out, args.emit_kotlin)

    corpus_path = args.corpus or "builtin"
    items = CORPUS if args.corpus is None else load_corpus(args.corpus)
    if not items:
        print(f"No rows found in {corpus_path}", file=sys.stderr)
        return 2

    # B4 rung 0: offline feature extraction — no model machinery needed,
    # but it does consume the loaded corpus rows.
    if args.cmd == "features":
        from .features import build_feature_table, feature_report, write_feature_table
        table = build_feature_table(items, args.labels)
        print(feature_report(table))
        write_feature_table(table, args.out or "out/features.jsonl")
        return 0

    print(f"Corpus: {corpus_path} ({len(items)} rows), model={args.model}, "
          f"mode={'LIVE' if args.live else 'dry-run'}")

    if args.cmd == "route":
        rows = route_labels(items, live=args.live, model=args.model,
                            sleep_s=args.sleep)
        print()
        print(routing_report(rows))
        out = args.out or "out/route_labels.jsonl"
    elif args.cmd == "selftalk":
        from .selftalk_runner import selftalk_labels, write_report
        rows = selftalk_labels(items, live=args.live, model=args.model,
                               sleep_s=args.sleep)
        print()
        write_report(rows, None)
        out = args.out or "out/selftalk_labels.jsonl"
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

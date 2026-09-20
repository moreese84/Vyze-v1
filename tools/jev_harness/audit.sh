#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# One-command live audit for Vyze transcripts.
#
#   bash tools/jev_harness/audit.sh
#
# What it does:
#   1. Loads TYPESAFE_API_KEY from the environment or from .env (repo root
#      or tools/jev_harness/.env) — save your key once, never type it again.
#   2. Picks the NEWEST interactions_*.jsonl in corpus/jev_export/ — no
#      filename typing, no $NEWEST shell tricks.
#   3. Pre-flight guard: refuses to audit a stale export (pre-fix signature:
#      every row "lang": "unknown"). This is what kept re-showing you the
#      old result — auditing the old file always reproduces the old answers.
#   4. Runs the live audit, one output file per corpus (no clobbering).
#
# Override switches:
#   FORCE=1                  audit anyway despite the stale-file guard
#   DRY=1                    dry-run (no API key, no API calls)
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root, wherever you invoke from

CORPUS_DIR="tools/jev_harness/corpus/jev_export"

# ── 1. API key ───────────────────────────────────────────────────────
if [ -z "${TYPESAFE_API_KEY:-}" ]; then
  for envfile in .env tools/jev_harness/.env; do
    if [ -f "$envfile" ] && grep -q "TYPESAFE_API_KEY" "$envfile" 2>/dev/null; then
      TYPESAFE_API_KEY=$(grep -E "^TYPESAFE_API_KEY=" "$envfile" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '\r')
      export TYPESAFE_API_KEY
      break
    fi
  done
fi
if [ -z "${TYPESAFE_API_KEY:-}" ] && [ "${DRY:-0}" != "1" ]; then
  echo "ERROR: no API key."
  echo "  One-time setup — save your key to a file, then never think about it again:"
  echo "    echo 'TYPESAFE_API_KEY=your-key-here' > .env"
  exit 1
fi

# ── 2. Newest corpus file ────────────────────────────────────────────
NEWEST=$(ls -t "$CORPUS_DIR"/*.jsonl 2>/dev/null | head -1 || true)
if [ -z "$NEWEST" ]; then
  echo "ERROR: no .jsonl corpus files in $CORPUS_DIR"
  echo "  Copy the export from your phone's Downloads folder:"
  echo "    phone: Download/interactions_<epoch>.jsonl"
  echo "    laptop: D:\\Vyze\\tools\\jev_harness\\corpus\\jev_export\\"
  exit 1
fi
echo "Corpus:     $NEWEST"
echo "Modified:   $(date -r "$NEWEST" "+%Y-%m-%d %H:%M" 2>/dev/null || stat -c '%y' "$NEWEST" 2>/dev/null | cut -d. -f1)"

# ── 3. Pre-flight: stale-export guard ────────────────────────────────
CHECK=$(python - "$NEWEST" <<'EOF'
import json, sys, time
rows = []
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line:
            try: rows.append(json.loads(line))
            except Exception: pass
if not rows:
    print("EMPTY 0")
    sys.exit()
total = len(rows)
unknown = sum(1 for r in rows if str(r.get("lang", "unknown")) == "unknown")
ts = max((int(r.get("ts", 0)) for r in rows if r.get("ts")), default=0)
fresh = time.strftime("%Y-%m-%d %H:%M", time.localtime(ts / 1000)) if ts else "?"
print(f"{total} {unknown} {fresh}")
EOF
)
TOTAL=$(echo "$CHECK" | awk '{print $1}')
UNKNOWN=$(echo "$CHECK" | awk '{print $2}')
NEWEST_ROW=$(echo "$CHECK" | awk '{print $3}')
echo "Rows:       $TOTAL (newest row recorded: $NEWEST_ROW)"

if [ "$TOTAL" = "0" ] || [ "$TOTAL" = "EMPTY" ]; then
  echo "ERROR: corpus file has no parseable rows."
  exit 1
fi

if [ "$TOTAL" = "$UNKNOWN" ] && [ "${FORCE:-0}" != "1" ]; then
  echo ""
  echo "┌─────────────────────────────────────────────────────────────────┐"
  echo "│ STOPPED: this is the OLD pre-fix export.                        │"
  echo "│ Every row has lang=unknown — the old exporter's signature.      │"
  echo "│ Auditing it re-measures the OLD app's answers (the numbers you  │"
  echo "│ have seen repeatedly). This guard exists so that can't happen.  │"
  echo "│                                                                 │"
  echo "│ To get a fresh one:                                             │"
  echo "│   1. Use Vyze on the phone (speak a few queries)                │"
  echo "│   2. Close it, reopen it (auto-export fires on startup)         │"
  echo "│   3. Copy Download/interactions_<epoch>.jsonl from the phone    │"
  echo "│      into tools/jev_harness/corpus/jev_export/                  │"
  echo "│      (new filename — a number DIFFERENT from 1789815166971)     │"
  echo "│                                                                 │"
  echo "│ To audit this file anyway:  FORCE=1 bash tools/jev_harness/audit.sh │"
  echo "└─────────────────────────────────────────────────────────────────┘"
  exit 1
fi
if [ "$UNKNOWN" != "0" ] && [ "$UNKNOWN" != "$TOTAL" ]; then
  echo "Note:       $UNKNOWN/$TOTAL rows carry lang=unknown (mixed old+new export — new rows are the post-fix ones)"
fi

# ── 4. Run ───────────────────────────────────────────────────────────
BASE=$(basename "$NEWEST" .jsonl)
OUT="out/audit_${BASE}.jsonl"
MODE="--live"
[ "${DRY:-0}" = "1" ] && MODE=""

echo "Output:     $OUT"
if [ "${DRY:-0}" = "1" ]; then echo "Mode:       dry-run (no API calls)"; else echo "Mode:       LIVE (one Jev call per row)"; fi
echo ""
exec python -m tools.jev_harness audit --corpus "$NEWEST" $MODE --out "$OUT"

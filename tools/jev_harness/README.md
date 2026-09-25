# Vyze × TypeSafe Jev harness (Phase 0)

**Jev is cloud-only and runs on the dev machine. Nothing in this folder ever
ships in the APK** — the release binary contains no Jev code and no network
code of ours (the precise offline guarantee, including one documented
library-level exception, lives in `docs/eval/OFFLINE_GUARANTEE.md`). The
harness exists to (a) audit the prompt pipeline and (b) produce the labeled
corpus from which the offline **student router** (Phase 3) is distilled.

## Architecture in one line

```
dev machine, online:   corpus ──► Jev API ──► labels + audit reports
                                              │
phone, offline forever:        student rules (distilled in Phase 3) ◄┘
```

## Setup (dev machine only)

```sh
pip install typesafe-sdk          # API key: https://console.typesafe.ai/
export TYPESAFE_API_KEY=...       # never commit this
```

Dry-run needs neither the SDK nor a key.

## Usage

```sh
# 1. Smoke-test the whole pipeline offline (no key needed)
python -m tools.jev_harness route                      # built-in seed corpus
python -m tools.jev_harness audit --corpus transcripts.jsonl

# 2. Live labeling against the seed corpus
python -m tools.jev_harness route --live

# 3. Live labeling over a bigger corpus file (.py / .json / .jsonl)
python -m tools.jev_harness route --corpus corpus/device_queries.jsonl --live \
    --out out/route_labels.jsonl

# 4. Audit recorded (query, answer) transcripts — the echo / mirror reports
#    (rows need at least: query, answer; optional: id, lang, previous)
python -m tools.jev_harness audit --corpus corpus/transcripts.jsonl --live

# 5. A1: merge new device exports into the rolling corpus (offline,
#    idempotent — safe to re-run; dedupes on id+ts)
python -m tools.jev_harness append corpus/jev_export/interactions_*.jsonl \
    -o corpus/rolling.jsonl
#    add --namespace to rewrite ids as <session>_<id> so the per-export
#    id counter restart (ir_1 in every session) can never collide
```

## What it measures

**Routing pass (`route`)** — per query, one batched Jev request:
- `route_action` — `Choice` over the real `RouterDecision.Action` taxonomy
  (+ explicit `none_of_these`), with per-option probabilities and confidence
- `is_fast_path` — `Noul`: could this skip the VLM wake?

It compares **Jev vs regex baseline vs expected** overall and per language,
lists **regex-wrong/Jev-right rows** (the student's training signal), and
shows the confidence distribution so thresholds are chosen from *our* data.

**Audit pass (`audit`)** — per (query, answer) transcript, one batched request:
- `echoed_question` — `Noul` (the echo-bug regression metric)
- `language_matches_query` — `Noul` (en/ms/zh mirroring compliance)
- `relevance` — `Score` (0–2, averaged)

**Self-talk teacher pass (`selftalk`)** — per voice-lane row, one `Choice`:
- `selftalk_class` — who spoke: `user_speech` / `model_self_talk` /
  `not_user_content`. Compares Jev's verdict against the Python mirror
  of the on-device `SelfTalkPolicy` (`selftalk_device_mirror.py`) and
  reports **MISSED BY DEVICE POLICY** (new hallucination shapes → next
  pattern revision) and **device false drops** (patterns too aggressive).
  Tap-lane rows are skipped (no spoken language — the speech filter never
  sees them). See "The self-talk ritual" below.

## The self-talk ritual (anti-hallucination maintenance loop)

Run this after every batch of new device sessions (roadshow, dogfooding,
new languages/accents). It keeps the on-device L4 guarantee true as the
corpus grows: **Vyze never speaks what it has no evidence for.**

```sh
# 0. Prereq (once): put the key in tools/jev_harness/.env (git-ignored):
#    TYPESAFE_API_KEY=...

# 1. New sessions on the device → export + pull (see the workflow above)
#    adb shell am broadcast -n com.vyze.app/.debug.InteractionLogExportReceiver
#    adb pull /sdcard/Android/data/com.vyze.app/files/jev_export/ corpus/

# 2. Merge into the rolling corpus (idempotent):
python -m tools.jev_harness append corpus/jev_export/interactions_*.jsonl \
    -o corpus/rolling.jsonl

# 3. Run the teacher (one cheap Choice call per voice row):
python -m tools.jev_harness selftalk --corpus corpus/rolling.jsonl --live

# 4. Read the report:
#    MISSED BY DEVICE POLICY: N  → new hallucination shapes. For each:
#      a. Is it ACTUALLY model self-talk/refusal (not app-cue echo — the
#         router owns those, and tap-lane rows don't count)?
#      b. Add the pattern to SelfTalkPolicy.SELF_TALK_PATTERNS (Kotlin)
#         AND selftalk_device_mirror.py (Python) — keep them in sync,
#         same order.
#      c. Add the verbatim transcript as a named test case
#         (SelfTalkPolicyTest) — it can never silently regress.
#      d. Re-run this pass: the shape must move out of MISSED.
#    device false drops: N  → a pattern is too wide. Narrow it the same
#      way; a false drop means REAL user speech was silently discarded —
#      fix before any release promotion.

# 5. Watch-list discipline: a flagged shape with <3 corpus occurrences
#    is NOT a pattern (over-fitting risk). Note it, wait for repeats.

# 6. Commit: policy + mirror + tests together, one commit.
```

First run (2026-09-25, 75 rows → 65 voice): 1 genuine gap caught and
fixed (polite-refusal "I'm sorry, I cannot fulfill this request."),
0 false drops — the loop works. Cost: ~65 one-cent API calls per run.

## Corpus conventions

Rows are dicts: `id`, `lang` (`en|ms|zh`), `query`, `expected`
(action name or null), optional `previous` (follow-up context), optional
`answer` (audit rows only), optional `note`. See `queries.py` for the seed
set, including follow-up echo traps like "what about this?" / "bagaimana
dengan ini?" / "那这个呢？".

Two corpus kinds:
1. **Seed corpus** (`queries.py`, committed): hand-written, balanced, safe
   to commit.
2. **Device corpus** (`corpus/`, git-ignored): real dogfooding transcripts
   from the debug-only on-device export (Tier 1 of the data strategy —
   see `docs/eval/OFFLINE_GUARANTEE.md`; release users have no automatic
   channel by design, and Tier 2 share-sheet diagnostics is the planned
   pre-release addition). **Never commit real usage transcripts** — they
   can contain health/banking context.

   Workflow (debug build installed on the test device):

   ```sh
   adb shell am broadcast -n com.vyze.app/.debug.InteractionLogExportReceiver
   adb pull /sdcard/Android/data/com.vyze.app/files/jev_export/ corpus/
   # merge every export into the rolling corpus (idempotent), then audit it:
   python -m tools.jev_harness append corpus/jev_export/interactions_*.jsonl \
       -o corpus/rolling.jsonl
   python -m tools.jev_harness audit --corpus corpus/rolling.jsonl --live
   ```

   The exporter merges both transcript stores (camera-lane
   `interaction_records` + text-lane `vyze_memory` interaction rows),
   threads follow-up adjacency into `previous`, and never emits the built
   VLM prompt — only the extracted user query and the spoken answer.

## Known limits (documented, not discovered-the-hard-way)

- **Jev's primary language is English.** Per TypeSafe's docs, other
  languages including CJK have *lower accuracy* today. The per-language
  breakdown in the routing report exists precisely to measure that gap
  before any student rule is distilled from ms/zh labels.
- Confidence ≠ permission to act: thresholds in this harness are run
  parameters validated on our data, never universal rules.
- `SOS` from speech is telemetry only — Vyze triggers SOS by gesture; the
  router never executes it.

## Gate discipline (from the comprehensive plan)

- **Phase 2→3 gate**: Jev routing agreement ≥ regex baseline on the same
  corpus. If Jev can't beat the regex on *our* data, the student has
  nothing to learn — stop, keep the regex.
- **Phase 3→4 gate**: distilled student ≥ regex baseline on a frozen
  `labels.jsonl` snapshot, with per-language coverage intact.

## Migration note

The retired `eval_vyze_router.py` (repo root) used the pre-v1
`pydantic_ai` integration, a wrong tool enum, and free-text confidence —
superseded by this harness. Safe to delete.

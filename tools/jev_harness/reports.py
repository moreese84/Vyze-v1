# ruff: noqa: E501
"""Report generation: routing agreement and compliance summaries.

All math is plain, inspectable, and printed as text — no dashboard, no
dependencies. The numbers these functions produce are the Phase 2 gate
evidence:

  - Jev routing agreement vs regex baseline (does Jev have something to
    teach the student?)
  - echo rate + language-mirror compliance (prompt-pipeline health)
"""

from .taxonomy import LANGID_CONFIDENCE_BAR, RELEVANCE_NUMERIC, SPEECH_ROUTABLE


def routing_report(rows: list[dict]) -> str:
    """Summarize a labeling pass: Jev vs regex vs expected, overall + per lang."""
    ok = [r for r in rows if r.get("error") is None and r.get("jev_action")]
    if not ok:
        return (
            "ROUTING REPORT — no live Jev rows (dry-run or all rows errored). "
            "Nothing to compare yet."
        )

    n = len(ok)
    jev_expected = sum(1 for r in ok if r["jev_action"] == r["expected"])
    regex_expected = sum(
        1 for r in ok if r["expected"] and r["regex_action"] == r["expected"]
    )
    jev_regex_agree = sum(1 for r in ok if r["jev_action"] == r["regex_action"])

    lines = [
        "ROUTING REPORT",
        f"  labeled rows:                {n}",
        f"  Jev vs expected accuracy:    {jev_expected}/{n} = {jev_expected / n:.1%}",
        f"  regex vs expected accuracy:  {regex_expected}/{n} = "
        f"{regex_expected / n:.1%}"
        if any(r["expected"] for r in ok) else
        "  regex vs expected accuracy:  n/a (no ground truth)",
        f"  Jev–regex agreement:         {jev_regex_agree}/{n} = {jev_regex_agree / n:.1%}",
    ]

    # Confidence distribution — per SKILL.md, thresholds are evaluated on
    # OUR data, so show the shape before anyone picks a gate.
    confs = sorted(r["jev_confidence"] for r in ok)
    if len(confs) >= 4:
        def pct(p: float) -> float:
            return confs[min(len(confs) - 1, int(p * (len(confs) - 1)))]
        lines += [
            "  Jev confidence percentiles:  "
            f"p25={pct(0.25):.2f} p50={pct(0.50):.2f} p75={pct(0.75):.2f}",
        ]

    # Where regex is wrong and Jev is right — the student's training signal.
    wins = [
        r for r in ok
        if r["expected"] and r["jev_action"] == r["expected"]
        and r["regex_action"] != r["expected"]
    ]
    lines.append(f"  regex-wrong/Jev-right rows:  {len(wins)} (student training signal)")
    for r in wins[:10]:
        lines.append(
            f"    [{r['id']}] ({r['lang']}) \"{r['query'][:48]}\" "
            f"regex→{r['regex_action']} jev→{r['jev_action']}"
        )

    # Non-routable outcomes (none_of_these / SOS telemetry).
    nonroutable = [r for r in ok if r["jev_action"] not in SPEECH_ROUTABLE]
    if nonroutable:
        lines.append(
            f"  non-routable Jev choices:    {len(nonroutable)} "
            f"(none_of_these / SOS telemetry)"
        )

    # Per-language breakdown — the mirror-mandate dimension.
    lines.append("  per-language (Jev vs expected):")
    for lang in sorted({r["lang"] for r in ok if r["lang"]}):
        sub = [r for r in ok if r["lang"] == lang]
        hit = sum(1 for r in sub if r["jev_action"] == r["expected"])
        lines.append(f"    {lang}: {hit}/{len(sub)} = {hit / len(sub):.1%}")

    # Fast-path Noul shape.
    fasts = [r["jev_fast_path_noul"] for r in ok if r.get("jev_fast_path_noul") is not None]
    if fasts:
        hi = sum(1 for f in fasts if f >= 0.5)
        lines.append(
            f"  fast-path Noul ≥ 0.5:        {hi}/{len(fasts)} "
            f"(candidate fast paths to distill)"
        )

    return "\n".join(lines)


def audit_report(rows: list[dict]) -> str:
    """Summarize an audit pass: echo rate, language compliance, relevance."""
    ok = [r for r in rows if r.get("error") is None
          and r.get("audit_echoed_noul") is not None]
    if not ok:
        return (
            "AUDIT REPORT — no live audit rows (dry-run or all rows errored). "
            "Nothing to compare yet."
        )

    n = len(ok)
    echoed = sum(1 for r in ok if r["echoed_flag"])
    # v2: language-mirror compliance is judged over VOICE rows only. Tap
    # rows ("User tapped at position …") carry no spoken language, so they
    # are excluded from the mirror denominator entirely (lane missing on
    # pre-v2 result files → treated as voice, backward compatible).
    voice = [r for r in ok if r.get("lane") != "tap"]

    # v3: split AMBIGUOUS-GARBLE rows out of the compliance denominator.
    # When Jev itself cannot confidently identify the query's language
    # (Choice confidence below the bar), neither a match nor a mismatch is
    # decidable evidence — the on-device answer to "Chika Sama" cannot be
    # graded against a language nobody (model included) can name. Ungraded
    # rows are listed separately so they stay visible as telemetry instead
    # of silently dragging compliance down (baseline audit: ir_8 at 0.41
    # confidence dropped ms compliance to 1/3 = 33.3% though the app's
    # behavior there was already optimal).
    ambiguous = [
        r for r in voice
        if r.get("jev_query_language_confidence") is not None
        and r["jev_query_language_confidence"] < LANGID_CONFIDENCE_BAR
    ]
    graded = [r for r in voice if r not in ambiguous]
    lang_ok = sum(1 for r in graded if not r["language_mismatch_flag"])

    # Ordered relevance levels → numeric average.
    rel_vals = []
    for r in ok:
        level = r.get("audit_relevance_level")
        if level in RELEVANCE_NUMERIC:
            rel_vals.append(RELEVANCE_NUMERIC[level])
    rel_avg = (sum(rel_vals) / len(rel_vals)) if rel_vals else None

    lines = [
        "AUDIT REPORT",
        f"  audited transcripts:         {n} ({len(voice)} voice, {n - len(voice)} tap)",
        f"  echo rate (Noul ≥ 0.5):      {echoed}/{n} = {echoed / n:.1%}",
    ]
    if graded:
        lines.append(
            f"  language-mirror compliance:  {lang_ok}/{len(graded)} = "
            f"{lang_ok / len(graded):.1%} (voice rows, confident langid)"
        )
        if ambiguous:
            lines.append(
                f"  ambiguous-garble rows:       {len(ambiguous)} "
                f"excluded from compliance (Jev langid confidence < {LANGID_CONFIDENCE_BAR:.2f})"
            )
            for r in ambiguous[:10]:
                lines.append(
                    f"    [{r['id']}] conf={r['jev_query_language_confidence']:.2f} "
                    f"\"{r['query'][:48]}\""
                )
    else:
        lines.append(
            "  language-mirror compliance:  n/a (tap-only corpus — no spoken language)"
        )
    if rel_avg is not None:
        lines.append(f"  mean relevance (0–2):        {rel_avg:.2f}")

    # Echo offenders — the regression class we just fixed.
    offenders = [r for r in ok if r["echoed_flag"]]
    if offenders:
        lines.append("  echo offenders:")
        for r in offenders[:10]:
            lines.append(
                f"    [{r['id']}] q=\"{r['query'][:40]}\" "
                f"a=\"{r['answer'][:40]}\" noul={r['audit_echoed_noul']:.2f}"
            )

    # Per-language compliance (graded voice rows only — tap rows have no
    # language, ambiguous-garble rows are excluded above).
    # Group by the Jev langid label when present (live rows), falling back to
    # the exporter's inferred lang — old result files predate the Jev label.
    def eff_lang(r: dict) -> str | None:
        return r.get("jev_query_language") or r.get("lang")

    voice_langs = sorted({l for l in (eff_lang(r) for r in graded) if l})
    if voice_langs:
        lines.append("  per-language mirror compliance (graded voice rows):")
        for lang in voice_langs:
            sub = [r for r in graded if eff_lang(r) == lang]
            good = sum(1 for r in sub if not r["language_mismatch_flag"])
            label = " (Jev-labeled)" if any(r.get("jev_query_language") == lang for r in sub) else ""
            lines.append(f"    {lang}{label}: {good}/{len(sub)} = {good / len(sub):.1%}")
    else:
        lines.append("  per-language mirror compliance: n/a (no graded voice rows)")

    # Langid disagreements — exporter says one language, Jev another. This is
    # the detector-miss signal: a garbled Malay query the on-device exporter
    # labeled 'en'/'unknown' while Jev (and the user's ear) say 'ms'. Only
    # CONFIDENT Jev labels count — an ambiguous label is not a disagreement,
    # it is undecidable (those rows are excluded above).
    disagreed = [
        r for r in graded
        if r.get("jev_query_language") and r.get("lang")
        and r["jev_query_language"] != r["lang"]
        and "unknown" not in (r["jev_query_language"], r["lang"])
    ]
    if disagreed:
        lines.append(
            f"  langid disagreements:        {len(disagreed)} "
            "(exporter vs Jev — detector misses / distillation signal)"
        )
        for r in disagreed[:10]:
            lines.append(
                f"    [{r['id']}] exporter={r['lang']} jev={r['jev_query_language']} "
                f"\"{r['query'][:48]}\""
            )

    return "\n".join(lines)

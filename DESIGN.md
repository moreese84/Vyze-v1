# Vyze Pitch Deck — DESIGN.md

Ground truth recorded from the built artifact (`pitch/deck.html`, PDF `pitch/Vyze-constructor-start-deck.pdf`), seed 758bc1fb. Deck v2: 14 slides — added "The Team" (Maurice Anak David Alam + Hillarry Thomson Henry Tinggang) as chart row 10, market slide re-pinned to Malaysia beachhead (61K JKM) with WHO 2.2B / 2050 1.7B IAPB projections, model designation corrected to **Gemma 4 E2B** throughout (owner-corrected; never "Gemma 3n").

## World: The Snellen Chart

The deck is an eye chart for the pitch. Each slide is a chart row: the argument steps from the large and obvious (43 million people are blind) down to the fine print that proves precision (safety doctrine, evidence) and lands on the final approvable row (the ask). The register is clinical print — a test plate, not a tech keynote.

## Palette

| Token        | Value     | Role |
|--------------|-----------|------|
| `--board`    | `#FBFBF8` | clinical white board (ground) |
| `--ink`      | `#101010` | optotype black (primary text, 1.5px rules) |
| `--muted`    | `#5c5c58` | body/description text |
| `--faint`    | `#8b8b85` | registry, footers, labels |
| `--erule`    | `#d8d8d2` | hairline dashed rules |
| `--red`      | `#C4321F` | duochrome red: risk, refusals, chart-state accent |
| `--green`    | `#1E7A44` | duochrome green: verified, shipped, "read this line" |

Color is state, not decoration. Red and green reply to each other only where the argument is refuse-vs-pin, fail-vs-pass.

## Typography

- **Optician Sans** (OFL) — all display/optotype lines and row numerals, uppercase, near-white space between glyphs. Character: test-chart letterform, slightly condensed, flat terminals.
- **Public Sans** (variable, OFL) — body, labels, bold emphasis via `font-variation-settings:'wght' 700–800`.
- **IBM Plex Mono** (variable, OFL) — registry rows, footers, tags, data-style annotations. Letter-spacing .12–.18em, uppercase.

## Slide grammar (every slide)

1. Registry row at top: left "Chart row NN", center section name, right "vyze" (mono, uppercase, faint).
2. 1.5px solid black rule under the registry, full bleed with 64px margins.
3. Green mono chart label "Chart row NN · Read this line", then the slide's optotype headline.
4. Content is organized into ruled rows/columns that hang off hairlines and dashed rules; no cards, no shadows, no fills except the one solid-black `tag` and the `tr.us` table row highlight `#efefe9`.
5. Footer: mono hairline rule, "Vyze — Offline AI Vision Assistant" left, "NN / 13" right.

## Signature elements

- Cover: giant VYZE optotype row, red 100% ON-DEVICE line as the second chart row, thesis set like an engraved legend.
- Deterministic/verbatim structure: bordered box rows for spoken scripts (slide 6), grid-bound layer stack for the architecture lane table (slide 5).
- The duochrome refusal/pin pairing with real spoken scripts as "voice fine print".

## Motion

None. The artifact is a print/PDF surface; slides are static by contract.

## Not used (deliberate refusals)

Gradient/glow/soft cards (category default), kickers as decoration, icon systems, photos, drop shadows, rounded containers beyond the 2px-radius `tag`.

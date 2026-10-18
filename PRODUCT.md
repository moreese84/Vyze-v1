# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Blind and low-vision users in Southeast Asia, initial focus Malaysia (confirmed by owner materials: JKM figure, MAB/NCBM channels, trilingual street) who need an assistant that sees for them: scene description, reading, object/medicine identification, light and color checks, SOS. Secondary audiences: caregivers who set up and teach the phone; blindness associations and NGOs as distribution partners. Current surface being designed: a founder's pitch deck (HTML→PDF artifact) for the Constructor Start Batch 5 application — audience is program judges and investors.

## Product Purpose

Vyze is an offline Android AI vision assistant that speaks the visual world to blind and low-vision users. It runs a Gemma 4 E2B multimodal model fully on-device via LiteRT-LM inside a zero-network APK: no connectivity required, no per-query cost, no personal data leaving the phone. Success means a blind user with an affordable Android phone can independently handle daily visual tasks in their own language.

## Positioning

The only vision assistant that is simultaneously fully offline, trilingual (en/ms/zh), and tuned for mid-range and budget Android hardware. Cloud incumbents (Be My Eyes, Aira, Seeing AI) require connectivity, per-use cost, and send visual data to servers; Vyze's on-device architecture removes all three. Its safety doctrine — deterministic, curated answers for medicine and currency with a never-guess policy instead of model improvisation — is a trust moat competitors cannot copy without abandoning their cloud architecture.

## Operating Context

Daily-carry phone assistant: single tap = scene description, double tap = voice ask, plus light-check, color identification, and SOS gestures. Interaction logging stays on-device; exports are manual and owner-initiated. Features ship dark behind debug flags and are promoted only after real-device verification. Model file (2.59 GB) ships alongside the APK on device storage.

## Capabilities and Constraints

Confirmed: on-device scene description; voice Q&A in three languages; deterministic date/time answers; curated medicine knowledge base; color and light tools; SOS gesture; on-device visual memory; distilled routing (StudentRouter outperforming regex baseline in frozen fixtures); graceful degradation tiers for low-RAM devices. Hard constraints: zero network at runtime (documented in docs/eval/OFFLINE_GUARANTEE.md); no fabricated answers in safety contexts; runs within 4 GB RAM on Tier 2/3 devices. Open product work: local RAG knowledge book (G1 architecture decided, not built); MIGRATION_5_6 SQL bug pending (G2).

## Brand Commitments

Name: Vyze. Voice: warm, direct, trustworthy — the product never guesses at safety-critical answers and says so plainly. Founding team: **Maurice Anak David Alam** (founder & systems architect — end-to-end: LiteRT-LM GPU inference, camera/OCR pipeline, streaming TTS, multilingual routing) and **Hillarry Thomson Henry Tinggang** (co-founder, blind co-developer & lead UX — daily real-condition user, voice-first UX breakthrough source, bridge to Malaysian blind-welfare channels MAB/NCBM). Malaysia beachhead: 61,000+ registered visually impaired Malaysians (JKM data). No logo, palette, or visual identity exists yet; nothing may be invented and presented as an existing brand asset.

## Evidence on Hand

Public repo: github.com/moreese84/Vyze-v1. Working APK verified on real devices (Infinix GT 50 Pro Tier 2; TRL 5 outdoors with blind co-applicant per prior CIP Spark materials). 238-row labeled interaction corpus; StudentRouter 86.5% vs 55.8% regex baseline on a frozen 52-row fixture; distillation harness in tools/jev_harness. NO user counts, retention, revenue, pilots, or press exist beyond the founder-team's own field work — none may be implied. Contact: mauricedavidalam@gmail.com (on the deck's closing slide). Malaysia VI registry figure in use: 68,665 registered (DOSM PWD Statistics 2024, released 11 Dec 2025), with peer-reviewed national estimates putting bilateral blindness near 2% of all ages — registration is a large undercount, per owner.

## Product Principles

1. Trust over capability: a confident wrong answer in a safety context is worse than an honest "I don't know."
2. Offline is the product, not a fallback: connectivity, data exposure, and subscriptions are the incumbents' failure modes.
3. The user's language first: en/ms/zh is scope, not roadmap.
4. Degrade with dignity: budget hardware gets a real experience, not a broken one.
5. Verify on device before shipping: nothing is real until it runs on the owner's phone.

## Accessibility & Inclusion

The product's users are blind and low-vision; every output is spoken, every interaction is gestural or high-contrast. The pitch deck artifact must itself be legible: strong contrast, clear hierarchy, no decoration that obscures meaning.

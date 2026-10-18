# Brain swap: Gemma 4 E2B → MiniCPM-V-4.7-1B (VL int8vis) — finalized plan

Status: **M0.3 + M1 code executed — corridor session pending** · Last revised: 2026-10-16
· Decision made by product owner.

Product decision update (2026-10-16): MiniCPM is the production brain for the whole
fleet NOW (not gated on the corridor) — **engineering constraint kept:** phones with
< ~4 GB RAM (Tier 0) still can't run it (peak 2.3–2.5 GB) — the bypass stays.
**Single-model rule (owner, reinforced 2026-10-16): no phone carries two brains.**
Production ALWAYS loads MiniCPM; a missing file is a loud not-found error, never a
silent swap. GEMMA IS NOT PRODUCTION-REACHABLE: it exists only as a debug-corridor
A/B entry (debug build + deliberate sideload to force it).

## 0. Decision summary

Swap the on-device brain from `gemma-4-E2B-it.litertlm` (2.59 GB) to
`MiniCPM-V-4.7-1B-VL_int8vis.litertlm` (1.70 GB), same LiteRT-LM runtime family.
**Speech input stays on native platform ASR (`SpeechRecognizer`) — no bundled ASR,
no second runtime.** The Gemma-only raw-audio rescue lane dies and is replaced by an
explicit spoken degraded-mode notice.

Target win: smaller footprint and — if the corridor holds on a Helio — a major PREFILL
cut (HF-measured `int8vis` CPU TTFT is 2.2–3.0 s on a Pixel 8a vs our 38–40 s Gemma
CPU-vision prefill; Helio numbers are unmeasured and this plan measures them).

**Why this file:** the HF card recommends `_int8vis` exactly for 8-GB-class Android
phones with Arm Mali GPUs (the fp16-vision build rebooted phones in earlier
conversions). Our Hot 60i reference is 7.76 GB RAM, mt6768, Mali — inside that class.

## 1. The artifact (verified on HF, litert-community/MiniCPM-V-4.7-1B)

| File | Size | Pick for | sha256 (head) |
|---|---|---|---|
| `MiniCPM-V-4.7-1B-VL_int8.litertlm` | 2.21 GB | fp16-vision devices | `4c9c30b2…` |
| **`MiniCPM-V-4.7-1B-VL_int8vis.litertlm`** | **1.70 GB** | **8 GB-class Android + Arm Mali (us)** | `20c73b5a…` |
| `MiniCPM-V-4.7-1B_int8.litertlm` | 1.22 GB | text only — NOT usable (no vision) | `92d7d2e5…` |

Bundle contract (fast_vlm): one image per message, resized to a fixed **448×448** (no
tiling), **64 image tokens** spliced into the prompt, 1-D positions (measured 9/9
grounded vs the canvas-position upstream path). No video, no multi-image, no 4×
"finer detail" mode. Tool calls are returned as text, not parsed (we do not use
function calls). Chat template ships Instruct mode with **thinking OFF** (an empty
`<think>\n\n</think>` pair) — no thinking-token leak to strip. Stops:
`<|im_end|>` and `</s>` (thinking off), no start token needed (template emits
`<|im_start|>` itself).

HF-measured performance table (phones):

| Phone | Backend | Image TTFT | Decode | Peak RAM |
|---|---|---|---|---|
| Pixel 8a (8 GB, Mali, Tensor G3) | CPU (int8vis) | 2.2–3.0 s | ~21 tok/s | 2.3–2.5 GB |
| Galaxy S26 (Adreno) | GPU vision+GPU | **0.93 s** | 32.5 tok/s | 4.0 GB |
| Galaxy S26 | CPU | 1.95 s | 37.5 tok/s | 2.0 GB |

Critical HF caveat: on the Pixel 8a the GPU decoder engine **did not build** (memory
safety line during signature init) — expect CPU backend on our 8 GB Mali device too,
and do not count on GPU for this model.

## 2. HF reading CHANGED three things vs the earlier sketch

1. **Runtime-version gate (NEW, blocking).** Our APK pins `litertlm-android:0.16.1`
   (documented at `app/build.gradle.kts:175`, see also the SendChannel suppression
   note at `:144`). The bundle was converted with litert-lm-builder 0.15.0 and tested
   on runtime **0.18.0**, carrying Qwen3.5-hybrid decoder (GatedDeltaNet linear
   attention) + fast_vlm executor metadata. 0.16.1 may reject or mis-load it.
   → M0.1 is a load-probe; if it fails, upgrading the runtime becomes a numbered
   milestone with a Gemma regression check (Gemma must still load + pass corridor
   after any upgrade).
2. **Hybrid recurrent-state risk (NEW).** HF: one Engine per conversation — "a
   Conversation opened on an Engine that already served another one continues from
   that conversation's recurrent state" (LiteRT-LM issue #3165; broken answers on
   2nd/3rd conversation on one Engine). Vyze uses one long-lived Engine with one
   Conversation per query. → corridor double-run + a third query now double as the
   stability probe. Fallback if polluted: Engine-per-query (init cost measured on the
   60i before deciding; even +6 s init per query is small next to the old 40 s wall,
   but it is a real cost to price).
3. **Image-resolution contract (SHARPENED).** The runtime resizes to 448×448 whatever
   we feed. Our controller currently downscales Tier-2 scene frames to 192 px for
   Gemma's CPU-vision lane — that feed would be *upscaled* lossily by this bundle.
   → controller preprocessing becomes brain-aware: feed ≥448 px equivalent for
   MiniCPM (keep Gemma feeds unchanged until promotion).

Also dropped: the "thinking-token strip" seam from the earlier sketch — the bundle
already runs thinking-off; corridor still verifies no `.Private` block reaches TTS.

## 3. Architecture — what is shared vs reworked

```
        UNCHANGED (routing/product layer, all model-agnostic)
        PreGatePolicy · privacy branch · SensitiveIdPolicy · lane dispatch
        DynamicPromptBuilder (content only) · TTS pacing · dead-air filler
        EmbeddingGemma lane (separate engine, untouched)
        Tier system · RAM pre-flight · sideload convention · debug tooling

                    │ model-aware seams (the whole M1 scope)
        ┌───────────▼───────────────────────────────────────────────┐
        │ VlmEngineManager (LiteRT-LM)                              │
        │  MODEL_FILE + selector   turn builder branch (ChatML)     │
        │  backend chain (CPU-only for MiniCPM)  context ≤ 4096     │
        │  controller preprocessing per brain      audio-lane guard │
        └───────────────────────────────────────────────────────────┘
```

## 4. Pipelines, lane by lane

| Lane | Today (Gemma) | Swapped (MiniCPM int8vis) |
|---|---|---|
| Voice command | SpeechRecognizer → text → router → brain | **identical** (native ASR, per decision) |
| Scene narration | CPU-vision 192 px, PREFILL 38–40 s warmed | fast_vlm, 1 image → 64 tokens, runtime 448×448; TTFT measured in M2 |
| OCR / tap lane (1024 px stills) | Gemma binds patches at 256 px budget | runtime resizes to 448×448 — quality gate is the known unknown |
| Text-only queries | Gemma text decode | same decoder family, int8; ~20 tok/s on 8 GB-class phones |
| Raw-audio rescue | `Content.AudioBytes` → audio tower | **dies** → spoken degraded-mode notice ("voice backup unavailable — try again somewhere quieter") |
| EmbeddingGemma / hotspots (planned G1) | separate 485 MB engine | untouched — orthogonal to the brain |

## 5. Milestones

- **M0.1 Load-probe (existing APK, no code):** push the file to Downloads, force the
  selection, launch, read init log. If 0.16.1 rejects → **M0.2 runtime upgrade to
  0.18.x** in a branch: update gradle, revisit the SendChannel suppression target
  (`app/build.gradle.kts:144`), verify Gemma E2B still loads and answers (regression),
  then continue.
- **M0.3 Debug model selector — DONE (2026-10-16):**
  `com.vyze.app.debug.BrainToggleReceiver` + `BrainStickyProvider`
  (existing pattern, `app/src/debug` + debug manifest):
  `--es brain minicpm|gemma|auto` (no extra = cycles auto → minicpm → gemma),
  sticky across OEM kills, release builds untouched.
- **M1 Engine seams — DONE (2026-10-16), `compileDebugKotlin` +
  `testDebugUnitTest` both exit 0 (new `BrainTurnFormatTest` 6/6):**
  1. `BrainModel` enum (`core/BrainModel.kt`): file names, capabilities,
     `PROD_DEFAULT = MINICPM_V47_1B`, `DEFAULT_TRIAL_ORDER = [minicpm, gemma]`.
  2. Resolution: single brain — `resolveBrainForInit()` returns PROD_DEFAULT
     (no fallback trial loop; owner's no-redundancy rule); debug override wins
     and fails LOUD on a missing file; not-found error names the active
     brain's file. `isModelOnDisk()` = the MiniCPM file.
  3. Turn format: `buildTurnPrompt(brain, …)` dispatcher — Gemma keeps
     `companionBuildGemmaTurnPrompt`; MiniCPM = new
     `companionBuildChatTurnPrompt` (plain content, NO Gemma tags — the
     bundle's ChatML template renders roles; same anti-echo contract:
     labeled history, current query last). JVM-tested.
  4. Backend: `backendPlanFor(brain, tier)` — MiniCPM = CPU-only single step
     on every tier (HF: GPU decoder fails to build on 8 GB Mali); context
     tokens clamped by the bundle's 4096 KV budget; `ExperimentalFlags` reset
     to engine defaults for MiniCPM (fixed 64-token vision, no MTP).
  5. Feed: `clampTargetDimension` = FLOOR at 448 px for MiniCPM (no
     downsampling — the runtime resizes to 448×448, so old 192/256 feeds
     would upscale lossily). Gemma tier caps unchanged.
  6. Audio lane: `transcribeAudio` guards `supportsAudioInput` — MiniCPM
     fires the spoken degraded notice (`AUDIO_RESCUE_DEGRADED_MESSAGE`),
     never silent. `isModelOnDisk()` = any trial-order brain's file.
  7. Watchdog budgets + RAM pre-flight thresholds left unchanged — upper
     bounds; re-tune only from corridor numbers (M2).
- **M0.2 runtime upgrade — DONE (2026-10-16, device-verified):**
  gradle 0.16.1 → **0.18.0** (`app/build.gradle.kts`), clean compile (API was
  source-compatible; coroutines note kept). Regression PASSED: forced-gemma
  load + warm-up on the new runtime. Concurrent fix: `audioBackend = null`
  when `BrainModel.supportsAudioInput == false` — the real conversational
  blocker on both runtimes.
- **M2 Corridor gate** on the 60i — NEXT, phone already live on MiniCPM (CPU,
  warm-up completes). Gateway list: fridge scene ×2 **plus a third query**
  file → `adb push MiniCPM-V-4.7-1B-VL_int8vis.litertlm /storage/emulated/0/Download/`,
  force the brain (`--es brain minicpm`), launch. One session, three brains
  A/B/C (debug selector): fridge scene ×2 **plus a third query**
  (recurrent-state stability), brand/label read (ir_215 lesson), receipt/
  long-text OCR probe (448 px contract), ms output ✓, zh output ✓, EN control ✓,
  `[PIPELINE-BREAKDOWN]` per run (now brain-labeled), RAM peak, engine init
  time, second/third-query stability. Gemma file STAYS on the phone (fallback
  path must keep working).
- **M3 Prompt retune:** DynamicPromptBuilder text vs MiniCPM habits — calibration,
  not rewrite; zh voice is MiniCPM's strength, ms is the unknown.
- **M4 Promote or close:** see §6.

## 6. Decision rules (drafted now, debated never)

| Corridor outcome | Action |
|---|---|
| Passes all gates AND TTFT meaningfully beats 38–40 s | Promote: already the prod brain per the 2026-10-16 owner decision (`BrainModel.PROD_DEFAULT`) — verify README provenance; Gemma file becomes documented rollback |
| Passes quality, TTFT ~equal | Decide footprint (−0.31 GB vs gpu build) vs **audio-rescue loss** — require explicit owner sign-off |
| Brand/OCR reads fail at 448 px | Permanently close for this quantization; record why; revert to Gemma path |
| Recurrent-state pollution breaks 3rd query | Price Engine-per-query init; only promote if that price still beats today |
| ms/zh fails | Close (language corridor is non-negotiable) |

## 7. Risks, priced

1. Brand/long-text OCR at fixed 448×448 + 64 tokens — the sharp end of the gate.
2. Hybrid recurrent state vs our long-lived-Engine pattern — probe in M2; fallback
   priced, not fatal.
3. Runtime version — RESOLVED (2026-10-16, device-measured): the 0.16.1 probe
   LOADED the MiniCPM bundle fine, but every Conversation creation failed with
   `NOT_FOUND: tf_lite_audio_encoder_hw not found in the model` — root cause
   was OUR hardcoded `audioBackend = Backend.CPU()` in EngineConfig, not the
   runtime version. Fixed: executor separation (audioBackend = null for
   audio-less brains per litert-lm Config.kt) + runtime upgraded to 0.18.0
   (the bundle's tested version). Device-verified: MiniCPM warm-up completes;
   forced-Gemma still loads + warm-up completes on 0.18.0 (GPU).
4. Audio rescue loss — accepted by decision (native ASR), made loud in UX, revisited
   only if debug-export data shows Recognizer failure is common on the fleet.
5. Engine-per-query init on Helio — measured in M2 only if the probe fails.

## 8. Rollback

With the single-model rule (owner), phones carry ONLY the MiniCPM file —
freeing 2.59 GB by deleting the Gemma sideload. ROLLBACK is a deliberate act,
not automatic: re-sideload the Gemma file + force `--es brain gemma` in a
debug build (release cannot return to Gemma without a code change). The debug
selector keeps forced-brain switching for exactly that scenario if the
corridor result is ever ambiguous in the field.

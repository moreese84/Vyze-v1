# Vyze — Offline AI Vision Assistant

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue?logo=kotlin)
![MiniCPM-V](https://img.shields.io/badge/MiniCPM--V-4.7--1B-orange)
![ML Kit](https://img.shields.io/badge/ML%20Kit-OCR-red)
![LiteRT-LM](https://img.shields.io/badge/LiteRT--LM-0.16.1-green)
![Google ADK](https://img.shields.io/badge/Google_ADK-Kotlin_0.2.0-4285F4?logo=google)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange)
![License](https://img.shields.io/badge/License-GPLv3-blue?logo=gnu-bash)

> **Vyze** is a fully offline AI vision assistant for visually impaired users. Powered by MiniCPM-V-4.7-1B (1B multimodal vision-language model) running on-device via Google's LiteRT-LM framework, with an agent-orchestration layer built on Google ADK for Kotlin. Zero internet dependency. Zero subscription costs. Works anywhere.

---

## What Vyze Does

Vyze speaks what the camera sees — instantly, privately, offline.

- **Scene Description** — "A wooden chair is directly ahead, about 3 steps away"
- **Text Reading** — Reads labels, signs, prescriptions, packaging and menus aloud — in whole sentences, in the text's original language, to the end. Voice "read this" queries capture a full-resolution still (~2400px) so entire letters and dense documents read back completely
- **Product & Brand Naming** — Identifies packaged goods by brand and product type as printed ("Maggi instant noodle packet"), then reads the rest
- **Currency Reading** — Reads the value on banknotes and coins aloud, with a strict never-guess rule
- **Bank Card Identification** — Identifies bank name and card type (debit/credit/ATM) from logos and printed text, with a strict never-guess rule
- **Medicine Lookup** — Cross-references OCR against a local drug database to answer "what medicine is this?"
- **Multi-Language** — Auto-detects your spoken language and responds in it (English, Malay, Chinese, and more)
- **Text-Only Q&A** — Answers general-knowledge questions ("what is paracetamol used for?") without the camera
- **Barcode & QR Detection** — ML Kit barcode scanning runs alongside OCR on every text query; retail products and banknotes are identified by their barcode and logged to scan history
- **Conversational Follow-ups** — "What is this?" → "A red Maggi packet" → "What about the one behind it?" — recent voice exchanges are remembered so pronouns resolve and answers stay conversational
- **Auto-Learned Brevity** — Interrupt answers repeatedly and Vyze quietly learns to answer in fewer sentences; let answers play and it relaxes again. No settings, no UI
- **Gesture Map** — Single tap: look (grid-tagged) · Double tap: ask by voice · Long press: light check · Triple tap: color · Triple tap + hold: SOS · With TalkBack on: tap left = look · tap right = ask · double-tap-and-hold left = light check · color & SOS in the TalkBack actions menu
- **Hands-Free Voice Sessions** — After a double-tap question the mic stays open for follow-ups; pick your voice by voice ("voice settings")
- **Noisy-Room Rescue** — When the recognizer gets lost in a crowd, your repeat is retried fully offline — via Gemma's audio encoder when that debug brain is sideloaded, the system recognizer otherwise. Malay/Chinese first queries on English phones are retried automatically in ms-MY/zh-CN before giving up
- **Precise Code Reading** — Vehicle plates, serial and reference numbers are spoken character by character ("Q L B, three four six nine") — never as a quantity
- **Voice Bug Reporting** — Say "I want to make a report" to file issues hands-free
- **Audio** — Speaks through the media stream at exactly your phone's volume, so the hardware volume buttons work normally

---

## Architecture

```
Voice Input → SpeechRecognizer → Language Detection
    │                                  │
    ▼                                  ▼
Camera Frame ──→ ML Kit OCR ──→ DynamicPromptBuilder
    │            (fast text)        │
    │                              ▼
    └──────────────────→ MiniCPM-V-4.7 (LiteRT-LM)
                              │
                              ▼
                        Sentence Buffer → TTS → User
```

### Core Components

| Component | File | Responsibility |
|---|---|---|
| **VlmEngineManager** | `core/VlmEngineManager.kt` | MiniCPM-V-4.7-1B engine lifecycle, adaptive CPU-first/GPU backend chain (background build + swap), fresh-state rotation, inference |
| **VyzeCoreController** | `core/VyzeCoreController.kt` | Pipeline orchestrator, session isolation, sentence streaming |
| **VyzeMasterAgent / AdkAgentManager** | `agent/VyzeMasterAgent.kt`, `agent/AdkAgentManager.kt` | Single Master Orchestrator: one agent, all native tools bound directly (OCR/TTS/haptics/spatial + direct LiteRT-LM VLM inference), one-pass dispatch; manager invokes it directly with timeout/error fallback to the native legacy dispatch |
| **VyzeShadowRouter / VyzeAgentRuntime** | `agent/VyzeShadowRouter.kt`, `agent/VyzeAgentRuntime.kt` | Pure gesture/speech routing decisions + session episodes + eval evidence; facade preserving the fragment call surface |
| **DynamicPromptBuilder** | `core/DynamicPromptBuilder.kt` | Intent-based prompt construction, language mirroring |
| **OcrHelper** | `vision/OcrHelper.kt` | ML Kit on-device OCR (Latin + Chinese) |
| **TTSManager** | `speech/TTSManager.kt` | Platform TextToSpeech (Google TTS engine preferred), utterance tracking, voice switching, prosody & pronunciation smoothing |
| **BarcodeHelper** | `vision/BarcodeHelper.kt` | ML Kit on-device 1D/2D barcode detection (EAN/UPC/QR/Data Matrix) |
| **ThermalPowerController** | `core/ThermalPowerController.kt` | Event-driven thermal governor (4-tier policy ladder); consulted read-only by the agent lane |
| **PreferenceLearner** | `memory/PreferenceLearner.kt` | Silent behavioral adaptation — learns answer brevity from speech-active interrupts |
| **CameraSetupDelegate** | `ui/delegates/CameraSetupDelegate.kt` | CameraX frame extraction, snapshot capture, full-resolution ImageCapture stills for OCR text reads |
| **CameraFragment** | `ui/fragments/CameraFragment.kt` | UI, speech callbacks, auto-snapshot loop |
| **MemoryRepository** | `memory/MemoryRepository.kt` | Vector similarity search, adaptive intelligence |
| **AudioCapture** | `device/AudioCapture.kt` | 16 kHz mono float32 recorder for the model-native ASR rescue |
| **MainActivity** | `MainActivity.kt` | Speech recognition, lifecycle, TTS orchestration |
| **ReportManager** | `speech/ReportManager.kt` | Voice-driven bug reporting via email |

### Project structure

```text
app/src/main/java/com/vyze/app/
├── core/        Pipeline orchestration: controller, prompt builder, VLM engine
├── vision/      Perception: OCR, barcode, color analysis, scene embeddings
├── speech/      Voice output: TTS management, report capture
├── device/      Hardware interaction: gestures, haptics, flashlight, mic capture
├── ui/          Fragments, delegates, views, viewmodels
├── memory/      Adaptive intelligence: preference learning, scene memory
├── data/        Room database: DAOs, entities, repositories
├── agent/       ADK agent lane: shadow router, live query agent, session episodes, eval
└── util/        Crash logging
```

---

## Key Features

### Fully Offline
All inference runs on-device — including the TTS engine. No cloud APIs, no data uploads, no subscriptions, no Google TTS dependency. Works underground, on planes, in rural areas.

### Fast Response Times
- **OCR fast-path**: ~150ms (ML Kit only — skips the VLM when confidence ≥ 85%)
- **OCR + VLM**: ~300-500ms (ML Kit text + model interpretation)
- **Full-page document reads (voice)**: +~200-400ms high-res still capture before OCR — buys ~4x glyph height for dense pages
- **Scene queries**: seconds, not minutes — GPU-served fastest, CPU-first pre-swap slower (≈7 s measured mid-session on a reference Mali phone)
- **First audio**: within ~3 characters of generated text (sentence-buffered streaming)

### Adaptive Memory
After every scan Vyze stores a lightweight visual fingerprint plus the scene description in a local Room database. Before analyzing a new frame it searches recent fingerprints; when a frame strongly matches a scan from the last 24 hours, the prior description is injected into the prompt as context — so the answer can open with "this looks like the box you scanned earlier" instead of describing from zero. Memory is context, never a substitute: every scan still analyzes the live frame fresh.

### Memory Poisoning Guards
Because injected memory can teach the model its own past mistakes, retrieval is fail-closed in three layers: **failure pre-labeling** — barge-ins, wrong-language answers, quick repeats and text-grounding misses are tagged as suspects at store time (`suspect_*` markers) and suspect rows are never re-injected; **provable-template detection** — rows that verbatim-contain prompt examples from older prompt eras (concrete anchors like "dark blue shirt" in en/ms/zh, or literal `[color] [object]` bracket echoes) are excluded from retrieval immediately and purged from the database at startup; and **refusal filtering** — canned non-answers ("Text is unclear." and its localized forms, matched as whole outputs) stay in history but are never injected as prior-scene memory, so a refusal can never anchor the next turn. The prompt itself carries no quotable example sentences — rules are abstract prose — because a quoted example is a completion target the model will happily speak back verbatim.

### Conversational Follow-ups (Dialogue Memory)
During hands-free voice sessions Vyze remembers the last two exchanges and injects them into follow-up prompts, so pronouns resolve across turns ("what about the one behind it?") and the model answers the follow-up directly instead of re-describing the scene. Dialogue memory expires when the conversation window closes or after 60 s of silence, is capped at ~150 tokens, and is skipped entirely for tap, continuous, currency and bank-card reads. Follow-up answers also generate faster — a shorter answer is fewer tokens to synthesize on low-end phones.

### Auto-Learned Brevity (Preference Learning)
Vyze silently adapts answer length to observed behavior: a capture triggered while the previous answer is still being spoken counts as an interruption. Three interrupts within five minutes shifts to concise answers, nine to one-sentence answers — enforced both in the prompt and by tightening the output-token budget (fewer tokens = faster generation). Every answer that plays to completion erodes one step of interrupt evidence. Upward adaptation persists in the memory database; decay never writes. Continuous auto-captures never count as interruptions — leaning back and listening is not impatience.

### Scan History
OCR text, barcodes/QR, currency reads, bank cards and color analyses are persisted to a Room-backed scan history at detection time — including on the OCR fast-path and OCR-fallback paths.

### Multi-Language
Automatically detects your spoken language via SpeechRecognizer and mirrors it to both the model's output and the TTS voice. Supports English, Malay, Chinese, and any language with an installed TTS voice pack. No hardcoded language lists — uses Android's dynamic Locale resolution.

Mirroring is **symmetric**: the prompt names the output language explicitly for every language — English included — and a bottom-of-prompt reminder fires unconditionally. Few-shot examples are complete natural sentences (subject + verb + spatial phrase, not comma-chained attribute lists), rendered in the active language, so answers sound like a person talking rather than bullet points — in all three languages, including on back-and-forth code switches.

### Hybrid OCR Pipeline
ML Kit handles fast text extraction (80-150ms), the model handles interpretation and context. OCR fast-path skips the VLM entirely when ML Kit confidence ≥ 85%, reducing latency to ~150ms for clear text.

Capture resolution follows the query type: explicit voice text reads ("read this", "baca") capture a **full-resolution still** (~2400px via a dedicated `ImageCapture` use case) so entire letters and dense documents read back completely — the 960×720 analyzer stream cannot resolve a full page at reading distance. Taps, pointing and scene queries stay on the analyzer stream with zero added latency; text on the tapped object is read from the 720p pre-pass. A dense-document sanity gate suppresses the fast-path when a high-res read returns a suspiciously short single line (ML Kit can score high confidence on a page it only partially recognized) — the read then goes to the model's adaptive token budget instead of speaking a garbled fragment.

### Vision Input Scaling
Frames are proportionally downscaled to the model's vision contract without rigid center-cropping, preserving spatial accuracy — with a feed floor so a lossy small frame is never upscaled back up.

### Sentence-Buffered TTS Streaming
Tokens stream directly to TTS as they generate. First audio fires after just a few characters — no waiting for full response. Post-first flushes coalesce ~2 sentences into one utterance (eliminating the per-utterance hardware seam between sentences), and the optional first-fragment fast-start joins its continuation with listing intonation instead of a full-stop seam.

### Continuous Mode
Point-and-describe loop with thermal safety — automatically throttles capture interval from 4s to 8s after 3 minutes of continuous use to prevent SoC throttling. Each auto-capture is first gated by a ~1 ms visual-similarity check against the last spoken scene: unchanged scenes skip the multi-second VLM run entirely (huge battery and latency saving), while any real change triggers a fresh description.

### Voice Bug Reporting
Say "I want to make a report" (English/Malay/Chinese) to enter report mode. Speak your issue, and the app saves a device-annotated report file and pre-fills an email — one tap to send.

### Voice Settings by Voice
Double tap, then say "voice settings" to audition every installed voice hands-free — "next" to hear the next voice, "use this" to keep it, "cancel" to stop. The choice persists per language. The double-tap cue stays a short "Listening." so it never delays the mic or nags.

### Audio Focus & TalkBack
Vyze holds audio focus for the whole session and speaks through the **media stream** at exactly the phone's volume, so the hardware volume buttons keep working and every utterance is stable. Vyze now **works with TalkBack on**: a transparent 50/50 overlay turns the screen into two exploration zones — the left half is *Look* (tap to focus, double-tap to describe the scene, double-tap-and-hold for light check), the right half is *Ask* (tap to focus, double-tap for a voice question) — and **Analyze center color** / **Trigger emergency SOS** ride the TalkBack actions menu on either half. The onboarding tutorial is dual-script: it plays through Vyze's TTS when TalkBack is off, and is queued through TalkBack natively (announceForAccessibility) when it's on, so the two voices never talk over each other. The overlay activates and deactivates live with touch exploration, so the full five-gesture physical map is untouched whenever TalkBack is off.

### Text-Only Q&A (no camera)
Questions that don't reference the visual scene — "what is paracetamol used for?", "bagaimana cara mengikat tali?" — are answered by the model's text decoder alone: no camera capture, no image inference. Faster and cheaper, and it works without pointing the phone at anything. Anything that could be pointing at the visible scene is conservatively routed to the camera instead: questions mentioning "this / here / in front of me / see / look", questions about a visible referent by pronoun ("what is he doing", "what is she wearing" — word-boundary matched, so "the weather" is unaffected), and visual action predicates ("doing / wearing / holding / carrying"). A missed text-only route just costs a slightly slower camera answer — a wrongly routed sight question would answer blind.

### Agent Orchestration (Google ADK)
Vyze layers **Google ADK for Kotlin** (`google-adk-kotlin-core-android:0.2.0` + KSP processor) on top of the native pipeline as a pure orchestration layer — every native subsystem (CameraX capture, TTSManager, OCR fast path, Embedding Gates, `ThermalPowerController`) stays 100% intact underneath:

- **Shadow router** — a pure decision function mirrors the legacy gesture/speech dispatch taxonomy and logs `SHADOW-ROUTE:` decisions without executing anything; a real ADK `BaseAgent` runs those decisions through the `InMemoryRunner` event pipeline.
- **Live query agent** — text-only voice questions can be answered end-to-end on the agent path: a `BaseAgent` orchestrates inside the ADK `InMemoryRunner`, carries persona/brevity context as ADK session state, and calls the VLM engine through a watchdog-disciplined wrapper (`analyzeTextDirect`) that never touches the engine's internal `generationMutex` or session handling.
- **Session episodes** — one episode per ADK session with idle eviction (5 min, shrunk to 90 s under thermal pressure) plus a defense-in-depth orphan-session sweep every 60 s from a lifecycle-aware ticker in `CameraFragment`.
- **Eval-driven promotion** — every agent-lane attempt is tallied (answered / failed / declined / declined-busy) in a bounded 200-entry ring; `shouldPromoteToLive()` reports whether the window meets the promotion bar (≥30 attempts, ≥20 answered, ≥80% answer rate). The flag flip itself is always manual.
- **Ships dark** — the live lane is flag-gated off in production; declines fall back to the exact legacy dispatch path, so the agent lane changes nothing until it is deliberately enabled after on-device eval review.
- **Invariants** — `isCapturing` CAS gate untouched, `ThermalPowerController` consulted read-only only, no frame access from the text-only lane (a sightless answer to a sight question is never allowed).

### Noisy-Room Rescue (model-native ASR)
When Android's recognizer gives up (no match, timeout, audio error — the classic "lost in a room full of people" case), Vyze speaks "Please say that again", records the repeat via `AudioCapture` (16 kHz mono float32 PCM), and retries it fully offline — one attempt per session, no Google services, no beep loops. Transcription uses the Gemma bundle's built-in audio encoder when that debug-corridor brain is deliberately sideloaded; the production MiniCPM bundle has no audio tower, so the system-recognizer legacy path handles the retry.

### Reading Whole Panels
Tap or ask about dense packaging and the full text is read to the end: an adaptive output budget is sized to the OCR text actually found (up to ~800 words), the inference watchdog re-arms while tokens are still flowing so long reads are never cut off mid-way, and the prompt forbids letter-by-letter spelling and premature stops. Brand pronunciation is corrected before synthesis ("Maggi" is spoken MAY-ghee, not MAY-jee).

### Offline TTS
Vyze speaks through the Android platform TextToSpeech engine, preferring the Google TTS engine for voice quality and language coverage (system-default fallback), with fully on-device voice packs — no network, no subscriptions. Queue-based speech with barge-in support: a tap or new voice query immediately interrupts any active utterance.

---

## Hardware Acceleration — Tiered Engine Initialization

Vyze classifies each device into a hardware tier at startup (total RAM via `ActivityManager.MemoryInfo` + GPU-class SoC heuristics from `Build.HARDWARE`) and picks the backend chain accordingly — this fixes the "m0 was canceled" class of silent GPU-init failures on mid-range MediaTek chipsets:

| Tier | Device class | Backend plan | Context | MTP (speculative decoding) |
|---|---|---|---|---|
| **Tier 1** | ≥ ~8 GB RAM + flagship GPU (Snapdragon 8-series, Dimensity 8000/9000, Tensor) | `Backend.GPU()` | 4096 tokens | Enabled |
| **Tier 2** | ~6–8 GB RAM, or mid-range GPU / Mali drivers | `Backend.GPU()` → fallback `Backend.CPU(4 threads)` | 3072 tokens | Disabled (always on CPU) |
| **Tier 3** | < ~6 GB RAM | `Backend.CPU(4 threads)` directly | 2048 tokens | Disabled |
| **Tier 0** | < ~4 GB RAM | None — VLM bypassed entirely | — | — |

Plans are executed adaptively: an unknown device starts serving on CPU **immediately** (no cold-start wall) while the GPU engine builds in the background behind a partial wake lock, then swaps in at the first quiet instant — queries keep answering on CPU the whole time. The GPU/CPU verdict is persisted per device (stale after 24 h), and SoC classes with known-bad GPU drivers are pre-filtered straight to CPU. Fresh-state rotation — a clean engine built in the background after each conversation to clear LiteRT-LM's shared recurrent state (issue #3165) — is gated the same way: it only spawns a second engine when ≥ 3 GB RAM is free.

On Tier 0 devices the VLM is skipped; a friendly message is spoken via TTS while fast-path tools (OCR, haptics, light check, color analysis) remain fully operational.

Additional safeguards at engine creation:

- **Guarded builds** — every engine build is serialized (`engineInitMutex`), time-budgeted (90 s GPU / 60 s CPU) with a drain-and-close path for timed-out native builds, and catches all C++/JNI driver errors, `OutOfMemoryError` and `UnsatisfiedLinkError` instead of letting them propagate as uncaught Job cancellations. A VLM init failure can never cancel the parent CoroutineScope or break non-VLM features.
- **Circuit breaker** — after 3 consecutive init failures the engine degrades gracefully with a friendly spoken message instead of retrying forever.
- **Memory hygiene** — GC + framework memory trim run BEFORE the native side allocates KV-cache tensors on Tier 2/3; tier-aware image dimension caps (512/384/256 px), visual-token budgets, and memory-pressure thresholds (500/700/900 MB) apply at inference time.
- **Warm-up** — a text-only dummy inference pre-compiles GPU kernels (or warms the CPU decoder) to eliminate first-inference cold-start latency.

### Mid-Tier Device Support
- Pre-flight RAM check rejects devices with insufficient memory (prevents OOM crashes)
- `ActivityManager.isLowRamDevice()` detection adjusts timeouts for constrained devices
- Runtime memory monitoring during inference with 400MB pressure warning threshold

---

## Tech Stack

| Component | Technology |
|---|---|
| **VLM Engine** | MiniCPM-V-4.7-1B (1.70 GB, multimodal) via LiteRT-LM 0.16.1 — adaptive chain: CPU-first serving + background GPU build + swap, GPU→CPU(4T) fallback on mid-range, CPU-only on low-RAM; VLM bypassed < ~4 GB |
| **Agent Orchestration** | Google ADK for Kotlin (`google-adk-kotlin-core-android:0.2.0` + KSP processor) — shadow router, live query agent, session episodes, eval |
| **OCR** | Google ML Kit Text Recognition (Latin + Chinese) |
| **Barcode** | Google ML Kit Barcode Scanning (1D + 2D) |
| **Camera** | CameraX 1.3.1 |
| **TTS** | Android platform TextToSpeech (Google TTS engine preferred), offline voice packs |
| **Speech Recognition** | Android SpeechRecognizer + offline rescue paths (Gemma audio-encoder when sideloaded, system-recognizer legacy fallback) |
| **Database** | Room 2.7.0 (adaptive memory + vector search) |
| **Coroutines** | kotlinx-coroutines 1.10.1 |
| **Language** | Kotlin 2.3.0 |
| **Build** | Gradle 8.14, AGP 8.11 |

---

## Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34
- ARM64 device (GPU/NPU recommended; CPU serving supported via the adaptive chain)

### Debug Build

```bash
./gradlew.bat assembleDebug
```

### Model Setup

Vyze needs two sets of models:

1. **MiniCPM-V-4.7-1B (VLM)** — push to your device:
   ```bash
   adb push MiniCPM-V-4.7-1B-VL_int8vis.litertlm /storage/emulated/0/Download/
   ```
   The app checks both `/storage/emulated/0/Download/` and the app-scoped external files directory. A missing bundle fails loudly with the exact filename — there is no silent fallback to another model. (The `gemma-4-E2B-it.litertlm` bundle is optional: debug builds only, for the A/B corridor.)

2. **TTS** — no setup needed. Vyze uses the Android platform `TextToSpeech` engine (no bundled models since v1.2).

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Agent Lane (ADK) verification

The ADK lane ships dark. To exercise it on-device, enable the agent flag in a debug build, then:

```bash
# Shadow routing decisions (log-only, nothing executed)
adb logcat -s VyzeShadowRouter | grep SHADOW-ROUTE

# Eval window + promote-to-live verdict, logged every 60s while the camera screen is up
adb logcat -s CameraFragment | grep "ADK eval"

# Thermal policy verification (Android 10+: override the OS thermal status)
adb shell cmd thermalservice override-status 2   # MODERATE — episode idle window shrinks to 90s
adb shell cmd thermalservice override-status 4   # CRITICAL — VLM halted, OCR reads still work
adb shell cmd thermalservice override-status 0   # clear override
```

The promotion bar (≥30 attempts, ≥20 answered, ≥80% answer rate, declines ≤ half the window) informs a **manual** flag flip — the runtime never enables itself.

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | CameraX preview and frame analysis |
| `RECORD_AUDIO` | Voice command recognition |
| `READ_EXTERNAL_STORAGE` | Model file access (legacy) |
| `MANAGE_EXTERNAL_STORAGE` | Model file access (Android 11+) |

---

## Performance Optimizations

| Optimization | Impact |
|---|---|
| OCR fast-path (≥85% confidence) | ~150ms text reads — skips the VLM entirely; partial dense-page reads fall through to the model |
| High-res still capture (voice text reads) | Full-page OCR — ~4x glyph height vs the 960×720 analyzer stream |
| Sentence-buffered TTS streaming | First audio in <500ms |
| Punctuation-guided clause flushing | Natural pauses at commas/periods |
| Greedy decoding (topK=1, temp=0.1) | Fastest possible token generation |
| Memoized prompt rule blocks | Six static rule + few-shot strings built once — no per-turn re-assembly |
| Scene-gate embedding reuse | One embedding pass per continuous capture (gate + baseline from the same frame) |
| Reused luminance row buffer | Zero allocations per idle sample (~every 600ms) |
| GPU warm-up (dummy 1x1 inference) | Eliminates first-inference cold start |
| Prompt trimming (~250 chars prefill) | ~60% faster prefill vs untrimmed |
| Dynamic aspect ratio (no center-crop) | Preserves spatial accuracy, faster encoding |
| JPEG quality 75 + pre-allocated buffer | 15-30ms faster frame encoding |
| ML Kit OCR pre-pass | 10-30x faster text extraction |
| Aggressive speech endpoints (300ms) | Faster voice query recognition |
| Session isolation (UUID gating) | Prevents stale results from previous queries |
| Watchdog timer (15s) | Prevents indefinite ANALYZING state |
| Agent-lane maintenance ticker (60s) | Episode eviction + orphan ADK session sweep; thermal-shrunk windows keep the in-memory session store bounded |
| Scene-unchanged gating (continuous mode) | Skips the VLM when similarity ≥ 0.9 vs last spoken scene |
| Coalesced sentence flushing | ~2 sentences per utterance — removes inter-sentence seams |
| Continuous mode thermal safety (3min) | Prevents SoC throttling on mid-tier chips |
| Adaptive backend chain | CPU-first serving with background GPU build + swap — per-device verdict persisted 24 h |
| Pre-flight RAM check | Prevents OOM crashes on constrained devices |

---

## Credits & Acknowledgements

| Contributor | Contribution |
|---|---|
| [**ctaxnagomi**](https://github.com/ctaxnagomi) | Architecture Review & Orchestrator Design Specification (agentic AI systems) |

---

## License

Licensed under the GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.

This project is free and open source software: you may use, study, share and
improve it. Vision inference runs on the MiniCPM-V-4.7 open weights (Gemma E2B retained for debug A/B) via LiteRT-LM
(Apache-2.0); model weights are obtained by opt-in download or sideload and are
not redistributed from this repository.

---

*Built for accessibility. Designed for independence. Runs everywhere.*

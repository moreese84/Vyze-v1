# Whole-app gap audit — 2026-11-08

HEAD at audit time: `eba6c2f` (Lexicon correction for recital spans).
Method: 8 parallel area reviewers (implementation + tests, ~200 files opened), one
skeptic pass, one gap follow-up, one synthesis merge. 121 raw findings, 6 dedupe
saves → **115 ranked findings: 9 P1 / 38 P2 / 56 P3 / 12 P4**.

Caveats (read first):
- The skeptic judge received empty input (area submissions exceeded the inline
  budget, a pipeline artifact) — findings below are single-reviewer claims with
  file:line evidence, NOT adversarially verified. Treat P1/P2 as strong leads.
- Agent-lane findings are down-weighted: Gemma/agent is debug-corridor only,
  MiniCPM is the production brain.
- Line numbers refer to `eba6c2f` and drift as files change.
- Overlaps with the earlier read-pipeline audit are marked [PRIOR].

## P1 — privacy / safety / crash / data-loss (9)

**#1 [FIXED 2026-11-09]** `util/CrashLogFile.kt:93` — Crash log with PII copied to public Downloads
after every inference. Callers: `VlmEngineManager.kt:2528` (response take(200),
prompt take(120)) + `MainActivity.kt:2061` (voice transcript) + OCR/barcode/
memory/answer takes in `VyzeCoreController`. `MANAGE_EXTERNAL_STORAGE` (manifest:43)
makes the gate passable. Fix: debug-only export + stop logging content.
[Spot-check: REAL — this is the file I've pulled all session. Note: my device
workflow depends on the export; keep a debug path.]

**#2 [FIXED 2026-11-09]** `core/VyzeCoreController.kt:2010` — OCR fast-path speaks sensitive reads
verbatim. No sensitiveId/isPreciseRead exclusions. Siblings: direct-read :2054,
thermal :2225, OCR fallback :2303. [PRIOR — my P1, independently confirmed.]

**#3 [FIXED 2026-11-09]** `data/ScanRepository.kt:50` — Bank-card + OCR content stored plaintext,
unbounded, no redaction. (App-private storage, so narrower than #1 — threat
model is root/backup extraction.)

**#4 [FIXED 2026-11-09]** `data/InteractionRecord.kt:38` — Full VLM prompt persisted verbatim
(OCR/card/medication text, no redaction/truncation). Same nuance as #3.

**#5 [FIXED 2026-11-09]** `speech/ReportManager.kt:84` — Spoken report saved to public Downloads
(`Vyze_Reports`), world-readable. Same class as #1.

**#6 [SCOPED 2026-11-09]** `AndroidManifest.xml:43` — `MANAGE_EXTERNAL_STORAGE` all-files access;
`PermissionsFragment.kt:114` routes users to the all-files page. Scope down.

**#7 [FIXED 2026-11-09]** `app/build.gradle.kts:42` — Release falls back to debug keystore with
hardcoded "android" passwords when release.keystore is absent. Must fix before
any Play release.

**#8 [FIXED 2026-11-09 — v1 never existed; fallback + chain test]** `data/VyzeDatabase.kt:167` — Missing 1→2 migration; `ALL_MIGRATIONS`
starts at 2→3, v1 upgrades throw at open. Fix before any release to upgraders.

**#9 [DISPUTED 2026-11-09 — dead code; live path already timed]** `performance/VyzeAdaptiveBackend.kt:395` — `calibrate()` has no timeout;
a hung GPU build wedges startup calibration (`GPU_WARMUP_ROUND_TIMEOUT_MS:293`
never enforced). Startup wedge on flaky drivers.

## P2 — functional bugs (38)

**#10 [FIXED 2026-11-09]** `core/DynamicPromptBuilder.kt:296` — SensitiveIdPolicy is prompt-only;
no output-side guard substitutes the pinned refusal or scrubs digits when the
model disobeys. Fix: post-generation guard when sensitiveIdMode armed.

**#11 [FIXED 2026-11-09 — fail-safe message]** `agent/VyzeMasterAgent.kt:100` — Undecodable frame → blank 1×1 bitmap
sent to VLM instead of failing safe. (Debug-lane only.) Undecodable bytes now answer "I couldn't read that camera
frame." — never a blank bitmap to the engine, never a blind VLM answer either (VyzeMasterAgentTest drives the real
ADK runner: zero generateImage calls asserted, observed red pre-fix).

**#12 [FIXED 2026-11-09 — device verification required]** `device/GestureDetectorHelper.kt:75` — Every triple-tap arms the
emergency SOS timer; callers must forward onTouchEvent or SOS fires spuriously.
[VERIFY URGENT — safety-critical if true.]

**#13 [FIXED 2026-11-09 — device verification required]** `MainActivity.kt:1290` — Offline Gemma branch kills online mic
(recognizer nulled, init only at :258).

**#14 [FIXED 2026-11-09 — device verification required]** `ui/fragments/PermissionsFragment.kt:69` — Permanent-denial nav loop
(camera ⇄ permissions forever on never-ask-again).

**#15 [DISPUTED 2026-11-09 — dead code path, not fixed]** `core/VyzeCoreController.kt:2030` — Fast-path/direct-read/thermal exits
leave guarded-lane flags stale (skip onComplete clearing); later text queries
inherit currency/card/ID/date state. [Note: my label-recital lane routes
through onComplete, so it is NOT affected.]

**#16 [FIXED 2026-11-09 — sync idempotent onOpen seed]** `data/MedicineDatabaseCallback.kt:22` — Upgraded DBs get empty
medicines table (seed only in onCreate, migration 5→6 has no backfill). Seed is now 41 idempotent
INSERT..WHERE NOT EXISTS statements executed synchronously in onOpen (inside the open lock), so fresh
installs, upgrades, and destructive fallbacks are all seeded before the first query returns; the second
Room instance is gone (MedicineSeedTest executes the real statements against the real migration table).

**#17 [FIXED 2026-11-09 — same onOpen fix]** `data/MedicineDatabaseCallback.kt:25` — Fire-and-forget pre-population
races readers; first medicine queries see an empty table. Same fix as #16: no more background launch, readers
always see seeded data.

**#18 [FIXED 2026-11-09 — DB v7]** `data/MemoryDao.kt:19` — upsert never updates (REPLACE keys on
autoGenerate id, no unique category+key) → duplicate brevity rows.

**#19 [FIXED 2026-11-09]** `data/InteractionDao.kt:39` — LIKE escaping dead (no ESCAPE clause).

**#20 [FIXED 2026-11-09 — DateTimeFormatter]** `util/CrashLogFile.kt:44` — Shared SimpleDateFormat raced off-lock.
log()/logError() formatted outside the lock through one mutable-Calendar instance (cross-talk proven red by a
32-thread distinct-values hammer vs a java.time oracle). All four sites now route through an immutable
DateTimeFormatter; HH:mm:ss.SSS shape unchanged (CrashLogFileTest).

**#21 [FIXED 2026-11-09]** `core/VyzeCoreController.kt:3925` — Bare-substring keyword banks
misfire: 'card'→cardboard/discard, 'kad'→kadar, 'wang'→bawang (currency!),
'tablet/capsule/dos'→medicine. [HIGH relevance to label reads.]

**#22 [FIXED 2026-11-09 — boundary-matched latin]** `agent/VyzeShadowRouter.kt:134` — contains('read') matches
bread/thread/already → misroutes to VLM_TEXT_READ. (Debug-lane only.) Latin keywords are word-boundary matched now
(CJK stays substring); "reading" keeps its legacy routing explicitly (VyzeShadowRouterTest, observed red pre-fix).

**#23 [FIXED 2026-11-09]** `core/VyzeCoreController.kt:3490` — shouldReadDirectly conf≥0.85
shortcut defeats the dense-doc sanity gate (partial fragments spoken).

**#24 [FIXED 2026-11-09 — device check on colors]** `ui/delegates/CameraSetupDelegate.kt:616` — NV21 packing ignores
rowStride/pixelStride → sheared frames on padded-stride devices. [Relevant to
low-tier device variety.]

**#25 [FIXED 2026-11-09]** `vision/OcrHelper.kt:181` — lineConfidence unboxes platform-type
confidence with no null guard → whole read silently returns (null, 0f).

**#26 [FIXED 2026-11-09 — 2400×1800 4:3 target]** `ui/delegates/CameraSetupDelegate.kt:288` — High-res still 2400×1350
is 16:9, contradicting the 4:3 no-crop comment → doc edges cropped. Target is now 2400×1800 (same horizontal glyph
detail, cropped rows restored) via internal HIGH_RES_STILL_WIDTH/HEIGHT consts; the exact-4:3 aspect is pinned by
integer cross-multiplication in CameraSetupDelegateTest. No downstream 16:9 assumptions found (only comments, fixed).
Device gate: full-page doc read keeps top/bottom lines (CameraX resolves the target to the nearest supported size).

**#27 [FIXED 2026-11-09]** `vision/ColorAnalyzer.kt:114` — Hues 335–345° classified Brown in both
overloads → wrong spoken colors.

**#28 [FIXED 2026-11-09 — device verification required]** `speech/TTSManager.kt:626` — speakQueued actually flushes (calls
speakImmediate) → clips in-flight answers for queue-expecting callers.

**#29 [FIXED 2026-11-09 — device verification required]** `speech/TTSManager.kt:529` — Debounce returns false without terminal
callbacks, hanging speakThenCallback. [Real; my recital chain handles it via
the accepted-check; fragment relies on SPEAKING_TIMEOUT.]

**#30 [FIXED 2026-11-09 — device verification required]** `speech/TTSManager.kt:925` — Permanent audio focus ignores LOSS (only
logs) → TTS talks over calls.

**#31 [FIXED 2026-11-09 — device verification required]** `speech/TTSManager.kt:867` — notifyFlushed skips rate/voice restore.
Caller check done: stop()/onDestroy paths were safe (voice restored first, rate self-heals), but the QUEUE_FLUSH
barge-in path was LIVE — flushing a voice-flipped (zh) span leaked the flip, so the barging-in utterance spoke
with the wrong voice. notifyFlushed now runs the same per-id restores as onUtteranceError (both idempotent, so a
late engine callback is a no-op). No JVM seam (TTSManager needs the engine); device gate: zh recital + barge-in
mid-zh-span, next utterance must use the user voice.

**#32 [FIXED 2026-11-09]** `MainActivity.kt:653` — Gemma-primary path keeps stale replay audio
(no clearLastCapture) → suspect replay can reuse prior-session bytes.

**#33 [FIXED 2026-11-09]** `core/VyzeCoreController.kt:506` — Confidence/repetition abort fires
BOTH terminal callbacks (fallback + 'No response from model').

**#34 [FIXED 2026-11-09 — split dev/user messages]** `core/VlmEngineManager.kt:591` — Model-not-found surfaces raw paths +
adb instructions via user-facing onError; controller speaks 'check your
connection' for a missing sideload. The paths+adb dump stays dev-facing (logcat + crash log); onError now
carries one short line ("<brain> model file not found.") that keeps LoadingFragment's adb-hint trigger, and the
engine flags the miss so the controller speaks a pinned trilingual prompt naming the real fix (place the file in
Downloads) instead of the connection sentence (InitFailureMessageTest). Device gate: ms/zh ear check.

**#35 [FIXED 2026-11-09]** `core/VyzeCoreController.kt:2968` — Text lane omits the Malay ×4/3
token headroom the image lane applies → Malay text-only answers truncate
mid-word. [Directly hits the trilingual gates.]

**#36 [FIXED 2026-11-09 — ignore-case]** `agent/VyzeMasterAgent.kt:148` — Haptic fast path is case-sensitive
(no ignore-case) → 'Double tap' falls through to VLM. (Debug-lane only.) Regex takes RegexOption.IGNORE_CASE; the
match+normalize lives in companionHapticPatternForCommand (VyzeMasterAgentTest unit + runner pins).

**#37 [FIXED 2026-11-09 — shared canonicalizer]** `agent/VyzeMasterAgent.kt:150` — Multi-word haptic patterns never fire
yet report Done ('DOUBLE TAP' vs underscore-only allowlist). (Debug-lane only.) One shared companionNormalizeHapticPattern
(Locale.ROOT, separators→"_", single allowlist) feeds the fast path AND the tool gate; unknown shapes return null so
the fast path falls through to VLM instead of lying (VyzeMasterAgentTest).

**#38 [FIXED 2026-11-09 — per-turn field resolution]** `agent/AdkAgentManager.kt:164` — Engine rebind is a silent no-op
(write-only bridgeProvider). (Debug-lane only.) The agent captured the initialize-time parameter; it now reads the
current field per turn, as the field's own KDoc always claimed — rebindEngine (and per-turn executeWithBridge) takes
effect (AdkAgentManagerTest end-to-end through the real runner, observed red pre-fix).

**#39 [DISPUTED 2026-11-09 — backoff is consulted at every read]** `performance/VyzeAdaptiveBackend.kt:175` — recordGpuFailure() never
invalidates the persisted GPU verdict → GPU still honored during backoff.

**#40 [FIXED 2026-11-09 — file deleted, zero refs]** `performance/VyzeGracefulDegradation.kt:38` — Slow-VLM degradation
unimplemented (canDegrade true only when engine-not-ready/thermal-blocked,
never slow-but-ready). Worse than unimplemented: the whole 187-line object had ZERO references in every source set
(its KDoc claimed "the snapshot trigger consults at key points" — false; the vlmWillingToStartInMs slow-signal param
was ignored and no caller ever passed one). Deleted the file, not fixed. Suite + assembleDebug green prove no refs
remain. (VyzePerformanceConfig keeps its own same-named VLM_FIRST_TOKEN_GRACE_MS const — untouched.)

**#41 [FIXED 2026-11-09 — deleted, zero callers]** `performance/VyzePerformanceRouter.kt:394` — decide() OCR-first path
is dead (bestAvailableOcr stub always null). Correction: the stub returned an EMPTY result (text null, conf 0), so the
path always planned "empty OCR, don't speak" — and decide() had zero callers anyway. Deleted decide() + bestAvailableOcr
+ taskTypeName + RoutePlan/RouteDecision/OcrResult/MIN_SPEAK_NOW_CONFIDENCE (~300 lines with #51); survivors are the live
classifyIntent (controller-called, JVM-pinned) and TASK_* buckets. Suite + assembleDebug green prove no refs remain.

**#42 [FIXED 2026-11-09 — monotonic clock + corrupt→ABANDONED]** `performance/FrameBudgetProbe.kt:63` — Wall-clock + unclamped latency
(clock step-back can fake E2_VIABLE). Default clock is now SystemClock.elapsedRealtime (monotonic; tests inject fakes,
so no JVM breakage), and a negative-latency close now lands as ABANDONED — counted, never averaged — instead of
poisoning the mean (FrameBudgetProbeTest: stepped-back clock + verdict-drag pins, both observed red pre-fix).

**#43 [FIXED 2026-11-09 — deleted, zero callers]** `performance/VyzeLocalTelemetry.kt:118` — readRecentRows() loads whole
files unbounded → OOM risk on ever-growing daily files. Deleted, not fixed: zero callers in every source set
(main/debug/test). A NOTE at the deletion site requires any future diagnostics reader to tail-read instead of
readLines(). Full suite + assembleDebug green prove no references remain. (Persisted telemetry is now write-only.)

**#44 [FIXED 2026-11-09 — device verification required]** `MainActivity.kt:252` — Back press always finish()es the app instead
of popping the back stack.

**#45 [FIXED 2026-11-09 — device verification required]** `ui/fragments/TtsSettingsFragment.kt:143` — setSelection before
setAdapter can reset a saved Malay/Chinese voice to English + speak on entry.
[User-visible, nasty for trilingual users.]

**#46 [FIXED 2026-11-09 — placebo buttons removed]** `ui/fragments/TtsSettingsFragment.kt:448` — Fake mode activation
(reading/medicine buttons only speak 'activated', set no flag).

**#47 [FIXED 2026-11-09 — device verification required]** `device/FlashlightManager.kt:51` — Torch state set optimistically,
future never observed. toggleTorch now observes the enableTorch future: torchEnabled flips only on success, a
generation counter drops stale out-of-order completions, and every path completes the optional onDone with the
CONFIRMED state. autoTorch announces only when the command took effect (silence on failure: the condition persists,
the next tick retries, success speaks — no wrong "on" for a dark torch, no retry-tick spam). No JVM seam (CameraX);
device gate: dark→torch on + announcement, bright→off + announcement, light-check readout unchanged.

## P3 — robustness / edges (56, incl. test holes)

**#48 [FIXED 2026-11-09 — agent already gated]** `core/VyzeCoreController.kt:2835` — Thermal CRITICAL halt bypassed by
text/expansion/agent lanes (never read thermalPolicy).

**#49 [FIXED 2026-11-09 — sensitive-first precedence]** `core/SensitiveIdPolicy.kt:68` — 'serial' carve-out unscoped:
'nombor siri kad pengenalan' bypasses the pinned refusal. [Privacy-adjacent.] Sensitive keywords are now checked
before the money-serial carve-out: a query naming an identity document refuses even with a serial word present;
pure money-serial asks still fall through to allowed (SensitiveIdPolicyTest).

**#50 [NO-CHANGE 2026-11-09 — release-unreachable by construction]** `agent/VyzeShadowRouter.kt:173` — Voice transcripts in logcat + eval
ring, no redaction (200 records). (Debug-lane only.) shadowEnabled ships false and the only setter lives in src/debug
(ShadowRouteToggleReceiver); CameraFragment triple-gates on the flag, so neither the logDecision transcript nor the
in-memory eval ring can see user content on release. On debug builds with shadow manually enabled, content logging is
the corridor's purpose and consistent with the P1 #1 debug policy. Redacting would degrade the debug tool for zero
release gain.

**#51 [FIXED 2026-11-09 — deleted with #41, zero callers]** `performance/VyzePerformanceRouter.kt:198` — runOcrFirstPipeline dead
V1 scaffolding: no timeout/cancel, leaks BarcodeHelper. Same dead region as #41 (zero callers in every source set);
deleted together with OcrFirstPayload/BarcodeResult/wouldPreferVlmForHumor/firstSentenceForTts. Suite + assembleDebug
green prove no refs remain.

**#52 [FIXED 2026-11-09]** `ui/delegates/CameraSetupDelegate.kt:550` — takeHighResSnapshot has no
timeout (takeSnapshot has 1000ms) → stuck HAL wedges isCapturing, drops all
later captures.

**#53 [FIXED 2026-11-09]** `ui/fragments/LoadingFragment.kt:150` — Safety timeout misses hung
init (fires only when !initStarted). [Related to the 4.5-min init silence seen
on device — that was deferred-start delay, this is mid-pipeline stall.]

**#54 [FIXED 2026-11-09 — scope cancel + fresh-engine close]** `core/VlmEngineManager.kt:3051` — close() leaked the parked
fresh-state engine and never cancelled the scope, so background builds (and their wake locks) outlived destroy. Now cancels the scope
first — in-flight builds unwind through their wake-lock-releasing finallys instead of publishing into a closed manager — then closes
engine, parked GPU engine, and parked fresh-state engine. Single-shot by construction: the scope is a val, fragment-scoped managers are
dropped after destroy, and the app-shared manager is never closed. No JVM seam (device: exit + reopen under a slow build).

**#55 [FIXED 2026-11-09 — scope cancel + handler purge]** `MainActivity.kt:116` — asrScope/mainHandler leaked past onDestroy
(post-destroy mic restarts and status posts could fire). onDestroy now cancels asrScope and purges mainHandler before super. Residual:
an in-flight blocking recordSpeech still runs to its bounded end, but its post lands in the purged handler and no new capture can
launch. No JVM seam (device: exit mid-voice-session, confirm mic released).

**#56 [FIXED 2026-11-09 — unwire in onDestroyView]** `ui/fragments/CameraFragment.kt:1552` — Speech callbacks never
deregistered (leak + re-fire from dead fragment). New unwireSpeechCallbacks() nulls all four activity callbacks in onDestroyView,
mirroring the onViewCreated wire; sole-setter verified (only CameraFragment assigns them). No JVM seam (device: navigate away
mid-listening, confirm no dead-fragment callbacks).

**#57 [FIXED 2026-11-09 — onStop]** `ui/fragments/CameraFragment.kt:167` — Maintenance ticker never stopped (started in
onStart, stopMaintenanceTicker had zero callers; the view lifecycleScope only cancels at onDestroyView, so the ticker ran while
backgrounded). New onStop override stops it, symmetric with onStart. No JVM seam (device: background, confirm ticks stop in logcat).

**#58 [FIXED 2026-11-09 — listener + callback release]** `ui/delegates/GestureRouter.kt:98` — detach() reset the helper but
left the touch listener on the view (listener → router → Activity context + fragment callbacks). attach() now retains the container
view and detach() clears its listener, drops the ref, and resets the three action callbacks (router + callbacks are recreated per
view, so clearing is safe). No JVM seam (device: rotation/exit, confirm no stale-gesture leaks).

**#59 [FIXED 2026-11-09 — second animator field]** `ui/fragments/LoadingFragment.kt:485` — Infinite scaleY animator was
fire-and-forget (scaleX was stored and cancelled; scaleY ran forever against the detached logo view). Stored in pulseAnimatorY,
cancelled in stopPulseAnimation, cancelled + nulled in onDestroyView. No JVM seam (device: pass the loading screen, confirm no
orphan animator frames).

**#60 [FIXED 2026-11-09 — sampled decode + recycle + binding]** `ui/fragments/GalleryFragment.kt:61` — Full-res decode, no
recycle, leaked binding (OOM on large photos). Decodes now downsample so the longest edge fits 2048px on both the P+ path
(ImageDecoder target sample size) and the legacy path (bounds-then-sampled decodeStream, replacing getBitmap); the live bitmap is
tracked, the previous one recycled on replace, and onDestroyView drops the drawable, recycles, and nulls the binding (which never
had an onDestroyView). Sample math JVM-pinned (GallerySampleSizeTest, 9 cases).

**#61 [FIXED 2026-11-09 — 256KB tail rotation]** `util/CrashLogFile.kt:126` — File grows unbounded (MAX_ENTRIES caps
memory only; no rotation until init). Past 256KB appendToFile rewrites the file keeping its last 200 lines plus a
ROTATED marker (the pre-rotation read is cap-bounded, never an #43-style unbounded read); flush() is routed through
the same cap. Freshest crash evidence always survives (CrashLogFileTest rotation + no-rotation pins).

**#62 [HALF-FIXED 2026-11-09]** `data/ScanRepository.kt:22` — Scan/error tables never self-prune (scan prune now wired at startup; error table still unwired).

**#63 [FIXED 2026-11-09 — header/lock/cells/retention]** `performance/VyzeLocalTelemetry.kt:97` — Telemetry writer: no HEADER,
no rotation, unlocked appends (torn lines), unsanitized model fields shift CSV
columns. All four: HEADER row written on file creation only (no mid-file header, no migration); header check-then-act
+ append under one lock (single caller today — guards the new race window + future callers); deviceModel/socModel via
a CSV sanitizer that keeps spaces but maps commas→semicolons and strips quotes/newlines; 30-day telemetry_*.csv
retention sweep on every write (total, foreign files untouched). All four seams JVM-pinned incl. an 8-thread append
hammer (VyzeLocalTelemetryTest). Also corrected the ROWS_PER_FILE KDoc (per-batch, never rotated).

**#64 [FIXED 2026-11-09 — latch + classifier]** `core/VyzeCoreController.kt` — Telemetry blind/mislabeled. Text/expansion lanes
never armed (KDoc-admitted); fast exits, thermal halts, watchdogs, and aborts never recorded; onError always UNKNOWN; the two loose arm
fields could double-emit when a main-thread watchdog raced an engine-callback completion. Now: a synchronized one-shot TurnTelemetryLatch
(arm/consume/disarm/retag) replaces the fields; image/text/expansion arm per turn; fast exits (refusal/fast-path/direct-read) record success
(the pinned refusal is the designed terminal output); thermal halts record THERMAL_BLOCK (checkThermalHalt covers text+expansion, image is
inline); watchdogs record TIMEOUT before cancelling; cancelInference is the abort choke-point (USER_CANCEL — no-op when idle or already
recorded); continuous-mode suppressions disarm with no row; the recital retags to the degradation bucket before its manual onComplete;
agent-direct ops write with local timing (TEXT_ONLY/VLM_IMAGE, timeout-vs-blank split by the elapsed clock) since image-direct holds no
isInferring CAS and must never clobber a native arm; onError maps through companionClassifyTelemetryFailure (OOM > thermal > timeout >
cancel > gpu > npu > low_ram > warmup > UNKNOWN, device-evidenced markers). The success-before-judgment reorder was already in place
(record precedes cleanup/judgment at onComplete) — verified, not re-changed. Deliberate non-rows: declined/busy/invalid triggers (never
armed) and instant answers (no inference — CameraFragment voice ladder). Pinned by TurnTelemetryLatchTest (10, incl. a 16-thread
single-winner race) + TelemetryFailureClassifierTest (21).

**#65 [FIXED 2026-11-09 — sealed TurnResult]** `agent/VyzeAgentRuntime.kt:244` — DECLINED_BUSY never recorded (all
map to FAILED). (Debug-lane only.) execute()/executeWithBridge now return Answered/DeclinedBusy/Failed instead of a
shared null (blank answers count as Failed, matching the documented contract); the runtime records each outcome
exactly. Only 2 callers existed (1 prod + 1 test); tryLiveRouteTextOnly keeps its String? shape, so the eval suite
has zero churn. Pinned by a latch-deterministic concurrency test at both manager and runtime level (AdkAgentManagerTest,
VyzeAgentEvalTest).

**#66 [FIXED 2026-11-09 — device verification required]** `core/VlmEngineManager.kt:2047` — Per-inference onTokenGenerated
wrapper never restored → closures nest unboundedly, every token traverses all
prior turns' wrappers. [Slow leak + per-token overhead growth.] Replaced per-turn WRAPPING with a single volatile
firstTokenHook: each turn installs its timing lambda, the dispatch site fires it before delivery, and the turn's
finally clears it only if still installed. Clearing-to-null (vs restoring a captured outer) is required because
cancelInference never joins — overlapped turns would otherwise resurrect stale wrappers. No JVM seam (engine needs
Context+LiteRT); device gate: multi-turn session normal, one #67 timing line per turn in logcat.

**#67 [FIXED 2026-11-09]** `core/VlmEngineManager.kt:2075` — First-token timing misattributes
lane-wait as prefill → both BREAKDOWN lines mislabel the Tier-3 latency
surface. [VERIFY — our prefill numbers sit on these lines.]

**#68 [FIXED 2026-11-09 — shared threshold]** `performance/VyzeAdaptiveBackend.kt:133` — recentOverheat reuses
validation age; one overtempCount vetoes hardware for 24h. Veto now shares OVERTEMP_FALLBACK_THRESHOLD with the
fallback (a lone transient overheat no longer vetoes); the window still keys off validation age by documented design
(no dedicated overheat timestamp — the pipeline is unwired, so minting one is unjustified). Existing threshold-level
test stays green; sub-threshold pin added (PolicyTest).

**#69 [FIXED 2026-11-09 — read-side enforcement]** `performance/VyzeAdaptiveBackend.kt:211` — Overtemp fallback wedges
device on CPU (non-atomic commits; reset clears counter only, verdict stays). The redundant preferredBackend=CPU
verdict write is deleted — enforcement is read-side via isCpuOnlyForDevice (counter >= threshold), so cooldown fully
lifts the fallback and the non-atomic second commit is gone with it. Counter-only reset is now complete by
construction. No JVM test (SharedPreferences; function has zero callers — the pipeline is unwired).

**#70 [FIXED 2026-11-09 — NPU gated on GPU]** `performance/VyzeAdaptiveBackend.kt:115` — NPU can be sole non-CPU path
despite the doc ban. NPU now appends only when GPU is already chained (was: capable hardware + CPU preference +
opt-in → [NPU, CPU]). Pre-existing [GPU, NPU, CPU] pin stays green (PolicyTest). Note: a stored NPU preference can
therefore never lead a chain — that is the doc's design (NPU rides along, never leads), not this fix's invention.

**#71 [FIXED 2026-11-09 — real chip-id patterns]** `performance/VyzeAdaptiveBackend.kt:353` — NPU detect dead for real
HW ids (needs literal dimensity/snapdragon; real: mt6899/sm8550) + locale-unsafe
lowercase. Matches mt68xx/mt69xx (Dimensity) and sm8xxx (flagship Snapdragon) chip ids plus tensor/bare marketing
literals, all Locale.US. Pinned gaps (follow-ups): board codenames (kona), Exynos, 7-series Snapdragon —
asserted negative in PolicyTest so extending coverage trips the suite honestly.

**#72 [FIXED 2026-11-09 — device verification required]** `core/VyzeCoreController.kt:2740` — Expansion lane drops pinned
contracts (no sensitiveId/date/medicineMiss/readRecital) + fixed 128 tokens
(ignores thermal cap, ms headroom, adaptive budget). ['Tell me more' on
sensitive topics unguarded.] The four flags are snapshotted when the main-lane prompt is built (all final there —
completion clears the live flags) and the expansion inherits them; the snapshot clears with the anchor in all three
reset sites (same lifecycle, never stale). Budget is now thermal-cap + locale-headroom via shared rules (the
`in 1 until base` rule extracted verbatim to companionThermalCappedTokens, used by both lanes; brevity still
overridden by design; no adaptive OCR budget — this lane runs no OCR pre-pass). Rule pinned in RouteTest; the
flag-wiring half has no JVM seam — device gate: "tell me more" after a MyKad/bank-card read must not speak digits.

**#73 [NO-CHANGE 2026-11-09 — enforced at the repo layer]** `core/VyzeCoreController.kt:3553` — buildMemoryContext has no
suspect-feedback filtering (similarity/age/length only); template-echo never-reinject claim unenforced in core. Verified airtight
upstream instead: both builders are private with one call site each, and both inputs arrive pre-filtered — findSimilar excludes
suspect-marked, anchor-era-parrot, and canned-refusal rows (MemoryRepository.kt:137-141) and recallByText applies the same triple
guard (MemoryRepository.kt:200-205), with SuspectMarker.hasSuspectMark covering every suspect_*-prefixed tag including template-echo.
DAO access is confined to the repository and getRecentInteractions has zero callers, so no unfiltered pipe reaches a prompt. A second
filter in the builders would be dead code by construction. The guard predicate is JVM-pinned instead (SuspectMarkerTest, 6 cases).

**#74 [FIXED 2026-11-09 — first-person veto]** `agent/StudentRouter.kt:188` — 'I'm analyzing this photo' swallowed as
self-talk (any cue-token transcript routes IGNORE). (Debug-lane only.) A surviving first-person pronoun now vetoes the hybrid match:
after cue+deictic stripping, a remainder containing i/im/ive/id/ill/my/me/mine/saya/aku (token-exact) or 我 returns routable — a
speaker referring to themselves is a human turn, never recaptured TTS. Pure-cue echoes (empty remainder, no pronoun, no question
marker) still IGNORE. Pinned in StudentRouterTest (EN contracted + uncontracted + MS routable, bare cue still ignored).

**#75 [FIXED 2026-11-09 — typed IgnoreFamily]** `agent/PreGatePolicy.kt:77` — Pre-gate families couple to reason
substrings (rewording silently drops to PASS_THROUGH). (Debug-lane only.) RouterDecision gains a defaulted ignoreFamily field
(NONE/EMPTY_TRANSCRIPT/APP_CUE_ECHO/APP_CUE_HYBRID/GREETING_GARBLE/FILLER_ONLY/OUT_OF_DOMAIN), set at all six student IGNORE sites;
evaluate() switches on the enum instead of reason text, with identical behavior (echo+hybrid silent, greeting+filler gentle,
empty+out-of-domain+none pass through). All 14 PreGatePolicyTest cases pass unchanged; the taxonomy itself is pinned by family
assertions in StudentRouterTest.

**#76 [FIXED 2026-11-09 — latin/CJK split + companion]** `agent/VyzeMasterAgent.kt:199` — READ_INTENT_REGEX misses CJK +
'reading' (word boundaries). (Debug-lane only.) Split into whole-word latin matching plus CJK substring matching (same shape as the
#22 shadow taxonomy), added "reading" to the latin set, and exposed the pure companionIsReadIntent used by the fast path (which also
moves the call site from default-locale to Locale.ROOT lowercase, a latent #78-class bug on the same lines). Pinned in
VyzeMasterAgentTest (latin/CJK/reading match; bread/already/non-reads don't).

**#77 [FIXED 2026-11-09 — parameter removed]** `agent/VyzeMasterAgent.kt:340` — read_scene_text requires includeChinese
but ignores it. (Debug-lane only.) Removed: OcrHelper always merges both recognizers and exposes no latin-only mode, so the
required flag taught the model a knob that did nothing. Declaration is now parameterless (Schema(type=OBJECT), same as
get_camera_frame); the stale "accepted for contract parity" comment is corrected. Pinned by a declaration-honesty test in
VyzeMasterAgentTest (required + properties empty).

**#78 [FIXED 2026-11-09 — Locale.ROOT via #37 helper]** `agent/VyzeMasterAgent.kt:433` — Default-locale uppercase breaks
matching (Turkish 'warning'→'WARNİNG'). (Debug-lane only.) Fixed incidentally by the #37 shared canonicalizer: both
the fast path and the tool gate now uppercase with Locale.ROOT (VyzeMasterAgentTest).

**#79 [FIXED 2026-11-09 — episodes + gemma flag]** `agent/VyzeAgentRuntime.kt:341` — releaseForTests leaks lane state
across tests. (Test-only.) It reset the runner, eval window, and two flags but left open episodes (evictIdle only drops stale ones)
and gemmaAlwaysEnabled set. SessionEpisodeManager gains a synchronized clear(), and releaseForTests now clears episodes plus all
three flags (the production-controller binding is app-lifetime, deliberately untouched). Pinned by VyzeAgentRuntimeTest
(self-cleaning via finally). AMENDED by P4 #105: the episode store this entry's clear() covered is deleted (production never
opened an episode) — the flags half stands, the test is retargeted to flags-only.

**#80 [FIXED 2026-11-09 — device-observable]** `vision/OcrHelper.kt:85` — Recognizer catch blocks swallow without
logging (missing Chinese model indistinguishable from no-text). Both catches now Log.w which recognizer failed with
the SDK exception class+message (SDK-side, never user content). No JVM seam (Bitmap + Task API); no maintained test
added — same precedent as #28-31. Visible in logcat only when a model is actually broken/missing.

**#81 [FIXED 2026-11-09]** `vision/OcrHelper.kt:111` — Merger never dedups across recognizers
(same printed line from both engines joined/duplicated). [Could double-speak
lines in recitals.]

**#82 [FIXED 2026-11-09]** `vision/OcrHelper.kt:179` — Zero-element lines default to 0.85
confidence → inflates merge confidence gating the 0.85 fast-path. [Interacts
with P1 #2.]

**#83 [DISPUTED 2026-11-09 — dead code, zero callers]** `performance/VyzePerformanceRouter.kt:410` — firstSentenceForTts
returns unbounded whole text (skips truncation, straight to TTS).

**#84 [FIXED 2026-11-09 — fire-time session grant]** `MainActivity.kt:1880` — Busy-retry reopens dead mic (no
voiceSessionWanted check). onError already gates at error time, but the 1s-delayed retry fired unconditionally — the session can
abort mid-delay. Fixed at the choke-point instead of the one call site: startListeningSafely now drops when the session is no
longer wanted, checked when the posted block RUNS. Safe for all five callers (the only setter raises the flag immediately before
calling; the three delayed retries are exactly the dead-mic holes). No JVM seam (Activity + recognizer).

**#85 [FIXED 2026-11-09 — delay tied to the grace]** `ui/fragments/CameraFragment.kt:2936` — Follow-up mic (450ms) beats
the 600ms AudioTrack drain grace → captures TTS tail. Confirmed on the non-streaming answer path (speakThenCallback fires at engine
onDone with no grace; the streaming/agent/instant paths already route through waitForTtsDrain). FOLLOW_UP_OPEN_DELAY_MS is now
AUDIO_DRAIN_GRACE_MS + 50 (compiler-enforced, both internal consts) — +200ms on follow-up opens for a mic that can't hear the
answer. The 500ms cue/retry delays are untouched (one-word cue tail unproven on the hot path; retry is post-error with no speech
playing) — device gate: if logs ever show the cue captured as a query, tie the cue delay too. Pinned by CameraFragmentTimingTest
(relationship, not values).

**#86 [FIXED 2026-11-09 — boundary-matched latin]** `speech/ReportManager.kt:63` — contains('report') matches
airport/reporter → misroutes queries into report mode. Correction: "airport" never matched (no "report" substring) —
the real class was reporter/unreported embeddings. Latin triggers are word-boundary matched now (CJK substring);
inflections (reporting/reported/reports) and Malay affixed forms (laporan/melapor/melaporkan) are explicit keywords
so boundary matching doesn't regress them. Pure companionIsReportTrigger pins without a Context (ReportManagerTest).

**#87 [FIXED 2026-11-09 — KDoc corrected, single slot by design]** `core/VyzeCoreController.kt:1427` — onUtteranceCompleted observer
clobbered on controller rebuild (documented additive). No second observer exists (sole assigner: controller init) and
the controller is app-scoped, so re-assignment correctly drops the stale closure — the only defect was the KDoc
promising multi-observer. Comment-only fix; converts to an observer list only if a second concern ever needs it.

**#88 [FIXED 2026-11-09 — center ROI + structured scope]** `ui/delegates/GestureRouter.kt:116` — Color analysis blocks main
thread (full-view bitmap + draw) + unstructured MainScope persistence. Triple-tap now draws a 96px center crop (the analyzer samples
the middle 20%, so the answer class is unchanged; the draw stays on main as View requires) and runs analysis + persistence on a
router-owned SupervisorJob scope cancelled in detach() — in-flight work dies with the view, DB errors speak a failure instead of
crashing via an uncaught MainScope exception, and the bitmap recycles in a finally. Tiny views speak "unknown" as before (no junk
persist). ROI centering JVM-pinned (GestureRouterRoiTest, 3 cases).

**#89 [FIXED 2026-11-09 — fail-fast Looper guard]** `device/AudioCapture.kt:130` — Blocking 8s recordSpeech, no
main-thread guard (KDoc says off-main, unenforced). Now refuses with null (the documented failure value every caller handles) when
called on the main thread. Both current callers already run in the IO asrScope, so nothing breaks. No JVM seam (the guard's true
branch needs a real main looper).

**#90 [FIXED 2026-11-09 — device verification required]** `device/FlashlightManager.kt:23` — Public mutable camera raced across
threads (only torchEnabled @Volatile). Field is now @Volatile (bound main-thread, read analyzer-thread; a stale null
meant the torch never fired in the dark). No JVM seam (CameraX); device gate rides with #47 (dark-room torch on/off +
announcements).

**#91 [FIXED 2026-11-09 — fail-fast require]** `data/InteractionRecord.kt:76` — Embedding BLOB deserialization
unvalidated (corrupt → wrong-length FloatArray). deserializeEmbedding now requires exactly 1024 bytes (symmetric
with serialize's require); the similarity search already skips failed rows per record, so corrupt BLOBs degrade
recall instead of crashing. Blast radius was already contained (cosineSimilarity requires equal sizes downstream) —
this moves the failure to the boundary with a clear message (InteractionRecordTest: round-trip + short/empty/long).

**#92 [FIXED 2026-11-09 — 500-id chunks]** `data/InteractionDao.kt:58` — deleteByIds unsafe at extremes (empty
IN(), purge-scale variable limit). The caller already guarded empty, but a 1000-parrot purge exceeded the legacy
999-variable limit (minSdk 26) and died silently inside the purge's try/catch. companionDeleteIdChunks splits ids
into 500-id IN-safe chunks (empty→no chunks); the purge loops over them (MemoryRepositoryTest).

**#93 [FIXED 2026-11-09 — single-thread writer + sync error path]** `util/CrashLogFile.kt:34` — Synchronous file I/O on caller thread
(unlike IO-confined Room repos). log()/logContent() now enqueue on a daemon single-thread executor (order preserved, file ref
captured at enqueue time, logcat stays synchronous as the real-time source of truth) while logError() keeps synchronous
caller-thread writes — crash evidence never waits for a queue — and flush() becomes a bounded drain barrier called by both crash
paths and the Downloads export. The two file-asserting tests flush before reading (same assertions, async contract); new pins for
flush-drains-queued and logError-without-flush (CrashLogFileTest).

**#94 [FIXED 2026-11-09 — release-r8 CI job]** `.github/workflows/build.yml:67` — CI never validates release/R8
(debug gates only). New release-r8 job runs :app:minifyReleaseWithR8 on every push/PR and uploads mapping.txt — proven locally to
need no keystore (signing is a later task), so rules, missing classes, and full-mode renames are all gated. (The kotlin-metadata R8
warnings in its output are pre-existing/environmental — same on the unmodified baseline.)

**#95 [FIXED 2026-11-09 — consumer rules + dead keeps out]** `app/proguard-rules.pro:20` — Overbroad keeps (whole ML Kit/MediaPipe/
GMS/camera trees), no rules for app entities/ADK tools. Deleted: the MediaPipe keep (dependency absent from the graph, the code,
and the cache — 0 R8 seeds) and the ML Kit + CameraX whole-tree keeps (their AARs ship consumer proguard.txt, verified in the
Gradle cache). R8 re-run green: mlkit seeds 1938→88, camera 11491→184, consumer keeps active. Deliberately kept: LiteRT + TFLite
(JNI GetMethodID-by-name class, one with device evidence) and GMS (no consumer rules + Binder/Parcelable core path — unproven to
narrow without a release device test). Deliberately absent: entity/ADK keeps — Room ships its own rules with generated (non
reflective) access, export JSON is manual, and the ADK lane passes tools explicitly with Map args and string-only state; the file
now documents all three verdicts so nobody re-adds a tree. Also folded the duplicated databinding keep (.** covers .*).

**#96–103** Test holes: agent fast-path/tools/rebind (#96), TTS queue/flush/
debounce/voice-flip (#97), ColorAnalyzerTest never invokes prod code (#98 —
FIXED 2026-11-09 for the classifier: all 44 tests now invoke
companionClassifyColor/companionEnglishName; bitmap paths still untested),
OCR merger/confidence/Barcode/Embedding (#99 — merger/confidence FIXED
2026-11-09 via OcrHelperTest), MemoryRepository/PreferenceLearner/
roundtrip/prune/CrashLogFile (#100 — CrashLogFile gates covered 2026-11-09),
GracefulDegradation/decide/telemetry (#101 — telemetry dir covered
2026-11-09), AppState machine/ViewModel/GestureRouter/permission flows
(#102), SOS/torch/haptics (#103).

## P4 — dead code / cleanup (12)

**#104 [FIXED 2026-11-09 — dead arm removed]** `core/VyzeCoreController.kt:2466` — Frame-retention reuse branch dead
(bitmap already recycled at :2453). Confirmed: the reuse arm required the inference bitmap to be both detached and unrecycled, but
the finally above recycles every detached bitmap first — unreachable in all three aliasing cases. Retention now always
re-downscales the live frame (still alive; recycled after). No behavior change possible.
**#105 [FIXED 2026-11-09 — episode machinery deleted]** `agent/VyzeShadowRouter.kt:192` — Dead thermal param, vacuous
episodes/maintenanceTick, duplicated KDoc. (Debug-lane only.) Deleted: SessionEpisodeManager (production never opened an episode —
the master lane deletes its ADK session per turn, so orphans are impossible by construction; thermalStatusProvider had zero readers
including tests), the runtime episodes field, maintenanceTick, the ticker's tick call (its shadow-eval logging stays), and 8 tests
of the deleted surface (Section 6 + tick tests; the AdkAgentManager session-leak assert is the real net and stays). releaseForTests
loses the episodes.clear() line the #79 fix added — the flags half of #79 stands. Zero code references remain.
**#106 [FIXED 2026-11-09 — overload deleted]** `vision/ColorAnalyzer.kt:134` — Legacy overloads with zero callers.
Deleted the context-free analyzeCenterColor overload, its private classifyColorEnglish helper, and the UNKNOWN_COLOR const (whose
only other user was one vacuous assertNotNull line, removed with it). The Context overload is the only entry point; the ROI consts
stay (the live overload uses them).
**#107 [FIXED 2026-11-09 — fail-fast onError]** `ui/delegates/CameraSetupDelegate.kt:564` — Context-null branch
wedges isCapturing if reached (defensive-only today). The `context ?: return` fired after onCaptureStart with no onError,
stranding the caller until the capture timeout (which then misreported "timed out"). Now fails fast with an immediate onError
before anything is posted. No JVM seam (CameraX).
**#108 [FIXED 2026-11-09 — dead code + config out]** `performance/VyzeAdaptiveBackend.kt:402` — Dead startMs, unused
imports, triple-sourced 0.72/0.85/0.65 thresholds across 4 files. Deleted the unread startMs and three unused imports
(SharedPreferences, Build, LocaleListCompat). The config side collapsed to one dead source: all five OCR-first routing consts
(0.72/0.85/0.65 + the two neighbors) had zero readers — the controller's private DIRECT_READ/OCR_FAST_PATH consts are the live
thresholds — so the whole section went. The other 0.65/0.85 literals (ASR confidence, TTS rate, white point) are distinct live
domains, not duplicates — untouched.
**#109 [NO-CHANGE 2026-11-09 — screens retained for wiring]** `res/navigation/nav_graph.xml:66` — gallery_fragment +
action_camera_to_tts unreachable in release. Verified unreachable everywhere including debug (only three navigate() calls exist in
the app, none target gallery/tts). Kept deliberately: these are user-facing screens (gallery just gained its #60 hardening) the
owner may wire next — deleting screens without instruction would exceed a P4. Cost is ~15 XML lines plus R8-eligible classes.
**#110 [NO-CHANGE + dead helper removed 2026-11-09]** `ui/fragments/CameraFragment.kt:2066` — Continuous mode release-dead
(debug receiver only). [Known — continuous is debug-only by design.] Design confirmed (toggle called only from
ContinuousToggleReceiver), so the mode stays. Removed the zero-caller startContinuousLoop helper inside it — the toggle posts the
runnable directly.
**#111 [FIXED 2026-11-09 — file deleted]** `res/xml/accessibility_service_config.xml:1` — Dead a11y config ships
in APK (no service declared). Verified zero references (manifest, code, all source sets) and deleted the file plus the emptied
res/xml/. Assemble green confirms the manifest merge is unaffected.
**#112 [FIXED 2026-11-09 — ladder pinned]** `core/ThermalPowerController.kt:211` — No JVM tests for thermal
governor or engine lifecycle. The status→policy map is now a pure companionPolicyFor (tierOf delegates; behavior identical) pinned
by ThermalPowerControllerTest (6 cases: all seven OS statuses, unknown-stays-permissive, tier ordering, per-tier invariants — the
OS integers are compile-time constants, so no device needed). Engine lifecycle keeps no JVM seam (LiteRT native — device-verified;
the adaptive-backend policy around it was already pinned separately).
**#113 [NO-CHANGE 2026-11-09 — stale finding]** `speech/ReportManager.kt:61` — Zero tests (matching + public-dir write
unpinned). Stale: ReportManagerTest already pins both named items (trilingual trigger matching incl. the #86 boundary cases, plus
the #5 private-directory resolution). The "public-dir" phrasing predates the #5 move to app-private storage. saveReport assembly
itself needs a Context and stays unpinned — no JVM seam.
**#114 [FIXED 2026-11-09 — moved to assets]** `LICENSE-VYZE-NOTICE.txt:1` — Misplaced under java/, never packaged.
Moved to app/src/main/assets/LICENSE-VYZE-NOTICE.txt, where it ships with the APK. Unreferenced in code (no about-screen reads it
yet) — the move fixes placement, not readership. Assemble green.
**#115 [HALF-FIXED 2026-11-09 — test moved; split has no referent]** `device/AudioCapture.kt:1` — Package drift (device/ unassigned in
split; ColorAnalyzerTest at test root). ColorAnalyzerTest moved to com.vyze.app.vision with its package decl (self-import dropped).
The device/ half is unresolvable as written: no module/package split plan exists anywhere in the repo, and both the main and test
trees already contain device/ — nothing to assign.

## Below-cut extras (from area notes)

Memory-data: PreferenceLearner unsynchronized counters; ErrorLogRepository
full-row count + un-LIMITed queries; Medicine/InteractionDao unescaped LIKE;
VyzeMemoryEntity.value unbounded; default Room journal/WAL; exportSchema=false.
Sweep: thirdTapDownTime write-only; CaptureStopPolicyTest pins stale 600/800ms
vs prod 400/450ms (prod constants unpinned); taskAffinity="" unusual.
No hardcoded keys/endpoints in main; debug isolation verified (21 src/debug
files, 9 exported receivers, CI release-manifest guard).

## Unresolved (10, carried)

Skeptic-gap meta item; MemoryRepository bodies vs finding #73; ui handling of
#33's double-terminal (spoken vs displayed); LiteRT native timing; agent
production call sites + TEXT_KEYWORDS parity for #22; ThermalPowerController
policy body; calibrate-timeout wiring; BarcodeHelper.close() need; src/debug
bodies beyond InteractionLogExportReceiver; res/raw wav bytes.

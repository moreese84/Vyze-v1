"""Single source of truth for the Vyze routing taxonomy.

Mirrors `RouterDecision.Action` in
app/src/main/java/com/vyze/app/agent/VyzeShadowRouter.kt.

Keep BOTH in sync: the harness labels (Jev) and the future on-device
student router must speak exactly the same action names. When the Kotlin
enum changes, change it here and re-run the harness.
"""

# Human-readable system description embedded in every Jev `state`. It gives
# the model just enough product context to judge spoken queries, and
# deliberately promises that the router never sees the camera image.
SYSTEM_DESCRIPTION = (
    "Vyze is an offline Android accessibility assistant for blind and "
    "low-vision users. The user speaks a request; the app answers by voice. "
    "A rear camera points where the user is facing. Routing happens BEFORE "
    "any vision model runs: the router sees only the transcribed speech, "
    "never the image."
)

# Choice criteria for the routing action. Names MUST equal the Kotlin
# enum names of RouterDecision.Action, plus one explicit no-match option
# ("none_of_these") per TypeSafe guidance: include a no-match outcome when
# nothing may fit — the model cannot choose an omitted value.
ACTION_CRITERIA = {
    "VLM_SCENE_DESCRIBE": (
        "Describe the scene broadly: 'what is this', 'what is in front of "
        "me', 'describe this room'. Requires the vision model."
    ),
    "VLM_TEXT_READ": (
        "Read text aloud: labels, signs, letters, documents, screens — "
        "anything containing written words the user wants read out. Runs "
        "the on-device OCR pipeline."
    ),
    "VLM_VOICE_QUERY": (
        "A spoken question that is none of the other kinds and still needs "
        "the scene or general knowledge: 'is someone near me', 'how much "
        "money is this note', 'what breed is that dog'. Requires the "
        "vision model."
    ),
    "FAST_COLOR_ANALYSIS": (
        "Identify a color by pixel sampling: 'what color is this', 'is "
        "this the red one'. Deterministic; no vision-model wake."
    ),
    "LIGHT_CHECK": (
        "Ambient light question: 'is it dark in here', 'is the light on'. "
        "Deterministic; no vision-model wake."
    ),
    "SOS": (
        "The user sounds in danger or urgently needs a person: fallen, "
        "hurt, lost, panic, 'help me'. NOTE: Vyze currently triggers SOS "
        "from a gesture, never from speech; this label is safety "
        "telemetry only."
    ),
    "IGNORE": (
        "Not a usable request: empty or unintelligible speech, or clearly "
        "not something a camera assistant answers ('play music', 'call my "
        "sister', 'tell me a joke')."
    ),
    "none_of_these": (
        "A genuine assistant request, but none of the listed kinds fit it."
    ),
}

# The only actions the speech branch of the on-device router can execute
# today (see VyzeShadowRouter.decideSpeech). SOS is judged but never
# executed from speech; gestures are outside the speech router entirely.
SPEECH_ROUTABLE = {
    "VLM_SCENE_DESCRIBE",
    "VLM_TEXT_READ",
    "VLM_VOICE_QUERY",
    "FAST_COLOR_ANALYSIS",
    "LIGHT_CHECK",
    "IGNORE",
}

# Noul criteria for the fast-path question: "could this be answered
# without waking the vision-language model?" Mirrors the intent of the
# retired eval script's is_fast_path field.
FAST_PATH_CRITERIA = {
    "true": (
        "A correct response can be produced with deterministic on-device "
        "pipelines only (OCR text extraction, pixel color sampling, "
        "ambient light sensing) without waking the vision-language model."
    ),
    "false": (
        "Answering correctly requires the vision-language model (scene "
        "understanding, visual reasoning, or knowledge about what the "
        "camera sees)."
    ),
}

# Score levels for answer relevance in the audit runner (ordered).
RELEVANCE_LEVELS = [
    "does not answer the query at all",
    "partially answers the query or is vague",
    "directly answers the query",
]

# Ordered levels → numeric value for averaging (0 = irrelevant, 2 = direct).
RELEVANCE_NUMERIC = {level: idx for idx, level in enumerate(RELEVANCE_LEVELS)}

# Choice criteria for the query-language (langid) judgment in the audit
# pass. Vyze's mirror contract covers exactly these three languages; the
# exporter emits the same vocabulary (InteractionLogRow.inferLanguage), so
# Jev labels drop straight into the per-language compliance buckets. A
# tap-lane screen instruction carries no spoken language; 'unknown' is the
# explicit no-match outcome per the TypeSafe candidate-coverage rule.
QUERY_LANGUAGE_CRITERIA = {
    "en": (
        "English: Latin script with English vocabulary or English question "
        "forms (what, where, is, the)."
    ),
    "ms": (
        "Malay (Bahasa Malaysia): Latin script with Malay vocabulary or "
        "question forms (apa, ini, itu, saya, mana, baca). Includes ASR "
        "garble of Malay speech whose tokens are not plausible English."
    ),
    "zh": (
        "Chinese: Han script (CJK characters), or romanized Mandarin "
        "utterances that are not plausible English or Malay."
    ),
    "unknown": (
        "No spoken language to identify: a screen-tap instruction, pure "
        "numbers/symbols, or text with no decidable language evidence."
    ),
}

# Jev Choice confidence below which a langid label is treated as AMBIGUOUS:
# the row cannot be graded for mirror compliance because neither a match
# nor a mismatch is decidable evidence. This is a run parameter, NOT a
# universal rule — sweep it on real corpora (the baseline device audit put
# genuinely ambiguous garble at 0.31 and confident labels at 0.98+, so the
# midpoint bar cleanly separates them).
LANGID_CONFIDENCE_BAR = 0.5

# ruff: noqa: E501
"""Jev teacher for the L4 anti-hallucination stack (dev machine only).

The on-device L4 layers are PURE code: SpeechGatePolicy (Layer 0, capture
floor) and SelfTalkPolicy (Layer 1, transcript patterns). Pure patterns
can silently rot — the corpus grows, new hallucination shapes appear, and
nothing notices until a user hears the model talk to itself. Jev supplies
the dev-side validation loop, same discipline as the router student:

  selftalk   Choice — is this transcript (a) the user speaking, (b) the
             MODEL speaking as itself (self-ID/refusal), or (c) not
             transcribable content at all (silence fill / app cue)?

Every row of the rolling corpus gets labeled. Rows where Jev says "model"
or "not-content" but SelfTalkPolicy says "clean" are NEW hallucination
shapes the pattern list missed — the promotion signal for the next
pattern revision. Jev never ships; only its distilled verdicts do.

Judgment design per SKILL.md: ONE narrow Choice over one state (the
transcript + its capture speechMs when known); the three classes are
mutually exclusive and exhaustive for voice-lane rows.
"""

from .taxonomy import SYSTEM_DESCRIPTION

# The three mutually exclusive classes of a voice-lane transcript.
SELF_TALK_CRITERIA = {
    "user_speech": (
        "A real utterance by the human user — a question, request, or "
        "greeting directed at the assistant, in any of the supported "
        "languages (English, Bahasa Malaysia, Mandarin Chinese), including "
        "imperfect ASR renderings of their speech"
    ),
    "model_self_talk": (
        "The ASSISTANT speaking as itself rather than transcribing the "
        "user: self-identification (\"I am a large language model\", \"I'm "
        "an AI\"), refusal-speak (\"Saya tidak dapat memproses\", \"我无法"
        "处理\"), or any first-person statement about the assistant's own "
        "nature or capabilities"
    ),
    "not_user_content": (
        "Neither user speech nor model self-talk: recaptured app prompts "
        "(\"Analyzing scene\", \"Please say that again\"), ambient noise "
        "rendered as text, or fragments with no addressable content"
    ),
}


def selftalk_questions() -> dict:
    """Fresh question objects per call (never reuse SDK objects)."""
    from typesafe_sdk import Choice

    return {
        "selftalk_class": Choice(
            instructions=(
                "This text is what a voice assistant's speech transcriber "
                "produced from a microphone clip. WHO is speaking, or is it "
                "speech at all? The assistant serves a blind user who asks "
                "about the visual world; its own prompts to the user "
                "(\"Analyzing scene\", \"Please say that again\") are "
                "sometimes re-captured by the microphone. " + SYSTEM_DESCRIPTION
            ),
            criteria=SELF_TALK_CRITERIA,
        ),
    }


def build_state(item: dict) -> dict:
    """State for the self-talk judgment: the transcript + capture evidence."""
    return {
        "transcript": str(item.get("query", "")),
        # Classified speech milliseconds from the capture (CAPTURE DETAIL),
        # when the row carries it. -1 = unknown (older exports).
        "capture_speech_ms": item.get("speech_ms", -1),
        "previous_answer": item.get("previous"),
    }


def extract(item: dict, judgment) -> dict:
    """Flatten the judgment onto the corpus row (harness row convention)."""
    return {
        "jev_selftalk_class": judgment.choice,
        "jev_selftalk_confidence": judgment.confidence,
        "jev_selftalk_probabilities": dict(judgment.probabilities),
    }

# ruff: noqa: E501
"""Dev-side MIRROR of the on-device SelfTalkPolicy (Kotlin).

KEPT IN SYNC BY HAND with app/src/main/java/com/vyze/app/speech/SelfTalkPolicy.kt —
the report is only meaningful when this mirror matches the shipped
patterns. The docstring on the Kotlin object names this file.

A Python mirror is deliberate: the Jev harness runs on the dev machine
(no JVM/Gradle in that loop), and the corpus report must re-implement the
exact device verdict to expose misses and over-drops.
"""

import re

# Mirrors SelfTalkPolicy.SELF_TALK_PATTERNS (Kotlin), same order.
SELF_TALK_PATTERNS = [
    re.compile(r"\bi am a (large )?language model\b"),
    re.compile(r"\bi\s?m an ai( assistant)?\b"),
    re.compile(r"\bas an ai( language model)?\b"),
    re.compile(r"\bi cannot (and )?will not\b"),
    re.compile(r"\bsaya (tidak boleh|tidak dapat|tidak mampu)\b"),
    re.compile(r"\bsebagai model (bahasa )?besar\b"),
    re.compile(r"作为一个(?:大型)?语言模型"),
    re.compile(r"我无法处理"),
]


def _normalize(text: str) -> str:
    """Mirror of SelfTalkPolicy.normalize: lowercase, punctuation → space,
    collapse whitespace. CJK (>= U+2E80) is preserved as content."""
    out = []
    for ch in text.lower():
        if ch.isalnum() or ord(ch) > 0x2E7F:
            out.append(ch)
        else:
            out.append(" ")
    return re.sub(r"\s+", " ", "".join(out)).strip()


def device_policy_verdict(transcript: str) -> bool:
    """True when the on-device policy would DROP this transcript."""
    n = _normalize(transcript)
    if not n:
        return False
    return any(p.search(n) for p in SELF_TALK_PATTERNS)

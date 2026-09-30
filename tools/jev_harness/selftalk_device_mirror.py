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

# Mirror of audit_judgment.lane_of: tap-lane rows carry no spoken language
# and never touch the speech filter — the teacher must not grade them.
TAP_QUERY_PREFIX = "User tapped at position"


def is_tap_lane(item: dict) -> bool:
    lane = item.get("lane")
    if lane:
        return lane == "tap"
    return str(item.get("query", "")).startswith(TAP_QUERY_PREFIX)

# Mirrors SelfTalkPolicy.SELF_TALK_PATTERNS (Kotlin), same order.
# 2026-10-01 SYNC FIX: this mirror had drifted behind the Kotlin policy —
# it was missing the en refusal-speak pair (\bi do not have (access|
# information)\b, \bi cannot tell you\b — commit 75597bd) and the ms
# confirmation-speak pattern (\bsaya akan ...\b — commit 0987a49). The
# stale mirror inflated the teacher's MISSED count and masked the
# documented ms false-drop trade-off (row ir_191). Keep 1:1 with Kotlin:
# when the Kotlin list changes, change it here, same order, one commit.
SELF_TALK_PATTERNS = [
    re.compile(r"\bi am a (large )?language model\b"),
    re.compile(r"\bi\s?m an ai( assistant)?\b"),
    re.compile(r"\bas an ai( language model)?\b"),
    re.compile(r"\bi cannot (and )?will not\b"),
    re.compile(r"\bi (am|m) sorry\b.{0,30}\bi cannot\b"),
    re.compile(r"\bi cannot fulfill\b"),
    re.compile(r"\bi do not have (access|information)\b"),
    re.compile(r"\bi cannot tell you\b"),
    re.compile(r"\bsaya (tidak boleh|tidak dapat|tidak mampu)\b"),
    re.compile(r"\bsebagai model (bahasa )?besar\b"),
    re.compile(r"\bsaya akan (bantu|membantu|cuba|tolong)\b"),
    re.compile(r"作为一个(?:大型)?语言模型"),
    re.compile(r"我无法处理"),
    # zh identity self-talk (ir_151, 2026-09-30 session) — zh parity for
    # the en identity family; the normalizer renders "AI" as "ai".
    re.compile(r"我是ai"),
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

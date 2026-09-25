"""Vyze × TypeSafe Jev harness (Phase 0).

Modules:
  taxonomy          — the routing/action contract (mirror of the Kotlin enum)
  queries           — built-in seed corpus (en/ms/zh, follow-up echo traps)
  regex_baseline    — pure-Python mirror of VyzeShadowRouter.decideSpeech
  router_judgment   — Jev Choice+Noul routing questions
  audit_judgment    — Jev echo/language/relevance audit questions
  selftalk_judgment — L4 teacher: who spoke (user/model/not-content)
  selftalk_runner   — self-talk labeling pass + device-policy comparison
  selftalk_device_mirror — Python mirror of the Kotlin SelfTalkPolicy
  runners           — corpus loading + live/dry-run labeling passes
  reports           — routing agreement + compliance summaries (gate evidence)
  cli               — `python -m tools.jev_harness route|audit`

Jev is cloud-only and runs on the dev machine — it never ships in the APK.
This harness produces the Phase 2 gate evidence and, later, the labeled
corpus from which the offline student router (Phase 3) is distilled.
"""

from .cli import main

__all__ = ["main"]

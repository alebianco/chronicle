---
name: chronicle-verify-research-claims
description: Research subagents on Chronicle produced both real verified bugs and confidently wrong corrections; check each claim against the source
metadata:
  type: feedback
---

During the R2 review, background research agents reported findings against
upstream/fork/competitor issue trackers. Checking each one against the primary
source rather than accepting it was decisive in both directions:

- **Real, and fixed:** embedded APIC artwork parsed into heap (upstream #83/#16);
  `Mood` carrying bare author names so authors read as series (verified in
  Audnexus's `update_tools.py`); no progress flush on pause.
- **Wrong, and would have caused harm:** "python-plexapi uses the multi-id
  `/library/metadata/{id1},{id2}` route in four places" — it does not; those are
  `id=` query params to `/library/sections/{id}/common` and a PUT. Acting on it
  would have built a batched fetch on an unverified route.
- **Doesn't transfer:** upstream #67's "Auto disabled poisons the player state
  machine" — here `mapPlayerState` never returns `STATE_ERROR` and the message
  clears on leaving IDLE.

An agent also self-corrected twice, having characterised its own background work
before it reported.

**Why:** a plausible, well-cited claim about someone else's codebase is still a
claim; the cost of checking is minutes, the cost of acting on a wrong one is a
wrong architecture decision recorded as fact.

**How to apply:** for each finding, fetch the primary source (the plugin's own
`.py`, the library's own source, the issue thread) and grep it before writing
code or docs. Record negative results in CLAUDE.md too — a disproved claim will
otherwise be rediscovered and re-believed.

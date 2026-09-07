---
name: chronicle-research-2026-07
description: Key strategic facts from the July 2026 Chronicle ownership/modernization research (full report in repo RESEARCH_FINDINGS.md)
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ef2077-7534-4304-adc4-74c8edb3ea54
---

Research completed 2026-07-05 for the chronicle-audiobook quest; full report at `RESEARCH_FINDINGS.md` in the repo. Durable facts not obvious from the code:

- **fabiogermann/chronicle ("Chronicle Epilogue")** is the active continuation fork (214 commits ahead, releases through June 2026, chronicleapp.net). Strategy decision pending from the owner: collaborate vs harvest vs differentiate — this gates Phase-1 work. Harvest-not-rebase was recommended (it raised minSdk to 33). Its code is GPLv3 but branding is All-Rights-Reserved.
- **Play targetSdk-36 deadline: 2026-08-31** — only binding if the owner decides to distribute via Play (upstream owns the existing listing; open question #1 in the report).
- Upstream `mattttvaughn/chronicle` is semi-active (Nov 2025 Media3 refactor), not abandoned as the quest brief assumed.
- Plex quirks that shape the design: on-deck/continue-listening is not populated for music libraries (must be client-built); narrator = Style tags, series = Mood tags (Audnexus convention); the dead `data/sources/MediaSource.kt` scaffolding is the intended backend seam.
- **Fork name decided 2026-07-05: "Chronicle Unabridged"** ("the complete edition" — premium gate ships disabled). Needs own applicationId/icon/wordmark; upstream and Epilogue branding are both off-limits. Backlog decision D7 in PRODUCT_BACKLOG.md.

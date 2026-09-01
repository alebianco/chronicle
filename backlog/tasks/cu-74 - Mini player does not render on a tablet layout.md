---
id: cu-74
title: Mini player does not render on a tablet layout
status: To Do
assignee: []
created_date: '2026-08-31'
labels: [R2, ui]
dependencies: []
priority: medium
milestone: m-2
---

## Description

Observed while trying to screenshot the cu-9 sync badge on a Pixel Tablet AVD
(2560x1600, landscape).

Playback was confirmed running — `/:/timeline` reports firing every ~5s, `AudioFlinger`
decoding — and `MainActivity` sets `currentlyPlayingContainer` to `VISIBLE` whenever
`isLoggedIn` is true. But **no mini player was visible anywhere on screen**, and the
`currently_playing_*` views were absent from a `uiautomator` hierarchy dump. Only the book
details screen's own views were present.

Consequence: on a tablet there appears to be no way to reach the currently-playing screen
at all, since the collapsed player is the handle that expands it. That makes the player —
and anything on it, including the cu-9 badge — unreachable.

### Not yet diagnosed

Candidates, in rough order of likelihood:

1. There is exactly one `res/layout/` directory and one `activity_main.xml` — no `-land`,
   no `sw600dp` — so the collapsed player's constraints may resolve to zero height or
   off-screen at a 16:10 landscape ratio. (`values-land/` does exist, so some landscape
   tuning was anticipated but never extended to layouts.)
2. `setBottomSheetState` may be leaving the sheet in a hidden state that
   `currentlyPlayingLayoutState` never moves out of on first launch.
3. The container is set `INVISIBLE` rather than `GONE` when not logged in, deliberately, to
   hold its layout slot — if `isLoggedIn` emits late or not at all under mock mode, it
   would stay invisible while still occupying space.

Worth confirming on a phone AVD first: if the mini player is fine at 1080x2400, this is
purely a large-screen layout bug and belongs with **cu-28 (adaptive layouts)** rather than
standing alone.

## Acceptance Criteria

- [ ] Reproduced or ruled out on a phone-sized AVD, to establish whether this is
      tablet-specific
- [ ] Root cause identified rather than worked around by nudging constraints
- [ ] Mini player visible and tappable on both phone and tablet, with the expanded player
      reachable from each
- [ ] Screenshot evidence on both form factors — this was found *because* a screenshot
      could not be taken, so a fix without one proves nothing

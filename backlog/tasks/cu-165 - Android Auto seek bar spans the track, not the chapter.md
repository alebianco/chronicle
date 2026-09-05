---
id: cu-165
title: 'Android Auto seek bar spans the track, not the chapter'
status: In Review
assignee: []
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - comfort
  - bug
milestone: m-2
dependencies:
  - cu-89
priority: medium
ordinal: 1000
---

## Description

Found during the R2 adversarial review (2026-09-05), from a competitor-issue sweep rather than from
our own bug reports.

`OnMediaChangedCallback.publishChapterAsSessionMetadata` deliberately overrides **only** the display
fields — `title`, `displayTitle`, `displaySubtitle` — and preserves `duration` from the track path.
That is correct as written and the KDoc explains why (a wrong duration gives Auto a dead scrubber),
but the consequence is that on Android Auto the **seek bar spans the whole track or book**, while
the *title* shown above it names the current chapter. On a single-file 47-hour audiobook the bar is
effectively unusable: a small drag skips a large portion, and the position readout does not
correspond to the chapter named beside it.

This is the single most-reported Auto defect against both major competitors:

- advplyr/audiobookshelf-app#1406 — a user who migrated **away** from Audiobookshelf to Bookcamp
  specifically because Bookcamp shows chapter progress and prev/next-chapter buttons in Auto
- advplyr/audiobookshelf-app#489, #239 — same complaint
- PaulWoitaschek/Voice#3432 — "small adjustments skip large portions"

We are unusually well placed to fix it: chapters are already resolved
(`CurrentlyPlayingSingleton.chapters`) and offsets already carry their frame in the type system
(`BookOffset`/`TrackOffset`, cu-136), so this is mostly plumbing rather than new modelling.

**Not yet investigated:** whether publishing a chapter-scoped duration and position to the session
breaks the phone UI, which reads the same session state. That is the reason the current code is
conservative, and it is the first thing to check.

## Implementation Notes

`chapterScrubberWindow` (`features/player/ScrubberWindow.kt`) narrows a book-framed position to the
current chapter and returns position **and** duration together, or null. Together deliberately:
publishing a chapter-length duration against a track-framed position gives a bar of the right size
pointing at the wrong place, which is worse than the whole-track bar it replaces. `MediaPlayerService`
uses it for `PlaybackState.position`, `OnMediaChangedCallback` for `METADATA_KEY_DURATION`.

**The trap this hit.** `ProgressUpdater` read `PlaybackState.position` and wrote it as *track*
progress, so making the session chapter-relative would have silently saved a chapter offset as a
track offset — corrupting listening position, worse the deeper into a book. It now takes a
`trackPosition` supplier reading the player directly, with the session left as the fallback for
before the service attaches one. Two tests pin the frames, and reverting the supplier fails exactly
the frame test.

Nothing in our own code reads session `duration` (`MediaItemTrack.from(metadata)` is dead code), so
that half was safe. Verified before changing it rather than after.

**What needs your eye:** the on-device Auto check. The unit tests prove the numbers; only you can
see whether the bar *feels* right in the car and whether the phone player is unaffected — it reads
its own position, not the session's, so it should be, but that is exactly the kind of claim worth
one look. The remaining unchecked criteria are those two visual confirmations.

## Acceptance Criteria

- [x] On Android Auto, the seek bar spans the **current chapter**, not the whole track/book
- [ ] Position within the bar corresponds to the chapter named in the title
- [ ] The phone player UI is unaffected — verified on device, since it reads the same session state
- [x] A book with no chapter data still shows a working track-scoped bar rather than a dead one
- [x] Unit test pins chapter-scoped duration/position for a multi-chapter book, and the fallback

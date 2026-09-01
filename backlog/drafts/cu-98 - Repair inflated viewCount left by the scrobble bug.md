---
id: cu-98
title: Repair inflated viewCount left by the scrobble bug
status: Draft
assignee: []
created_date: '2026-09-01'
labels: [R1, trust, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

The per-tick scrobble bug is fixed (`c46be31`), but **the damage it did to server data is not
repaired and nothing repairs it**. The owner's library carried `viewCount` of 183, 129 and 126 on
single tracks of books played at most a few times.

`Audiobook.isCompleted()` returns true for any `viewCount > 0` (decision-16: completion is an
explicit fact, not inferred from position). So every affected book reads as finished permanently, and
with *hide played* enabled `LibraryViewModel` filters it out of the library entirely — the book
disappears.

`/:/unscrobble` is already wired to mark-unread (`PlexService.kt:91` →
`BookRepository.setUnwatched`), which sets `viewCount = 0` on both server and local DB. So each
affected book is repairable by hand today; what is missing is a known procedure and a way to find
which books are affected.

- `Audiobook.kt:211` — `isCompleted()` treats any non-zero `viewCount` as finished
- `LibraryViewModel.kt:136` — the *hide played* filter
- `BookRepository.kt:384` — `setUnwatched`, the existing repair

## Acceptance Criteria

- [ ] Document the manual repair in the task notes: mark unread, then restore position.
- [ ] Decide and record whether a one-shot migration/diagnostic is worth it, or whether manual
      repair for a household-sized library is sufficient (principle 5).
- [ ] If automated: identify affected books by an implausible `viewCount` and offer repair, never
      silently rewriting server state.
- [ ] Confirm on the device that mark-unread then re-seek fully restores a damaged book.
- [ ] Note in the task whether track-level `viewCount` also needs clearing, since
      `markTracksInBookAsWatched` stamps every track.

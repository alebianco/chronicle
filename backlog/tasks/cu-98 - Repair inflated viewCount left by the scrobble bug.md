---
id: cu-98
title: Repair inflated viewCount left by the scrobble bug
status: Done
assignee: [claude]
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

- [x] Document the manual repair in the task notes: mark unread, then restore position.
- [x] Decide and record whether a one-shot migration/diagnostic is worth it, or whether manual
      repair for a household-sized library is sufficient (principle 5).
- [x] If automated: identify affected books by an implausible `viewCount` and offer repair, never
      silently rewriting server state.
- [ ] Confirm on the device that mark-unread then re-seek fully restores a damaged book.
- [x] Note in the task whether track-level `viewCount` also needs clearing, since
      `markTracksInBookAsWatched` stamps every track.

## Implementation Notes

The repair turned out to be a real code gap, not just a documented procedure.

`markTracksInBookAsUnwatched` was **local-only**. `BookRepository.setUnwatched` unscrobbled the
*album*, but every track kept its server `viewCount` — and completion is owned by the tracks
(decision-16), so the next sync could read the book straight back as finished. `/:/unscrobble`
accepts a track key as well as an album one, so the fix is the same call per track, skipped in
offline mode.

That made mark-unread able to fail, which exposed a second problem: **the success toast fired
before the coroutine ran**, so it claimed the book was repaired regardless. Both mark-played and
mark-unplayed now report the real outcome, with `mark_as_played_failed` /
`mark_as_unplayed_failed` strings.

Related, in the same change: `setWatched`/`setUnwatched` caught every failure, logged it and
returned normally. Local state was never at risk — the server call runs first — but the caller could
not tell the write had been refused. Both now propagate; `ProgressUpdater` catches rather than
propagating, since failing to mark a finished book must not take down progress reporting.

**Owner action still needed:** books already damaged need mark-unread, then re-set position. The
counts were 183, 129 and 126 on the owner's library. No automated sweep was built — for a
household-sized library (principle 5) manual repair is proportionate, and rewriting server state
unprompted is the wrong default.

Verified by sabotage: reverting to the local-only clear fails the new test.

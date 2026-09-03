---
id: cu-136
title: Make the offset frame part of the type
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - architecture
  - bug
milestone: m-2
dependencies:
  - cu-115
priority: medium
---

## Description

Filed from [[cu-115]]. Six bugs and counting have come from the same mistake: a value measured from
the start of a **track** used where one measured from the start of the **book** belongs, or the
reverse. Both are `Long`, so the compiler cannot help, and on a single-track book — which is the
owner's whole library — the two are the same number, so it works by accident.

The occurrences so far, by task: cu-13, cu-49, cu-93, cu-96, then four more found in the 2026-09-02
review and fixed in cu-115. `Chapter.bookStartTimeOffset`'s KDoc already documents the frame in
prose and renames the field to say so; it has not been enough.

## Proposal

Introduce two inline value classes:

```kotlin
@JvmInline value class BookOffset(val millis: Long)
@JvmInline value class TrackOffset(val millis: Long)
```

and give the conversion one home (`chapterSeekTarget` already is that home, it is just bypassed).
`Chapter.bookStartTimeOffset` becomes `BookOffset`; `MediaItemTrack.progress` becomes
`TrackOffset`; `Player.seekTo` takes a `TrackOffset`; `chapterAtBookProgress` takes a `BookOffset`.
A mix-up then fails to compile.

Inline value classes cost nothing at runtime, which matters because these are read on the 1 Hz
progress path.

## Also in scope

Two items deliberately left open by cu-115, both instances of the same class:

- **`CurrentlyPlayingViewModel:700`** writes `currentBookPosition` into a *track's* progress column
  in the service-is-dead branch of `seekRelative`. It needs a decision about what that branch
  should do, not just corrected arithmetic.
- **The duplicated conversion.** `chapterSeekTarget` is correct and bypassed; the same arithmetic is
  inlined at two `CurrentlyPlayingViewModel` sites. `MultiTrackSeekConversionTest` pins the rule so
  a consolidation has something to check itself against.

## Acceptance Criteria

- [ ] `BookOffset` and `TrackOffset` exist as inline value classes, with the frame documented once
      on the types rather than repeated at call sites
- [ ] Room `TypeConverter`s so the DB columns are unchanged (no migration)
- [ ] Every conversion goes through one function; no inlined `- trackStart` arithmetic remains
- [ ] `CurrentlyPlayingViewModel:700` resolved, with its intended behaviour stated in the task
- [ ] The `MultiTrackBook` tests still pass unchanged — they encode the rule, so a refactor that
      breaks them broke the rule
- [ ] A deliberate mix-up (passing a `TrackOffset` where a `BookOffset` belongs) **fails to
      compile** — demonstrated, per the m-0 rule that a new check must be verified to fail

## Two more instances, found auditing cu-73 (2026-09-02)

Both verified by reading the code; neither is on any checklist.

**`getTrackProgressInAudiobook` (`MediaItemTrack.kt:228`) still has the unsorted pattern.**
`this.subList(0, indexOf(track))` — exactly what cu-115 removed from `getTrackStartTime` for
corrupting book position. It is currently **dead code** (grep: zero callers in `main`), so it is a
trap rather than a live bug: the next caller inherits the fixed bug. Either delete it or fix it to
sort; leaving a broken twin of a corrected function next to it is the worst option.

**`TrackListStateManager.seekToActiveTrack` (`:60-62`) mixes sorted and unsorted indices.**
`getActiveTrack()` sorts internally, then `trackList.indexOf(activeTrack)` indexes the *unsorted*
`trackList`. `currentTrackIndex` then feeds `seekTo(trackIndex, position)` at `PlayerExt.kt:36`,
which is a **media-item index into the player's playlist**.

It is safe *today*, and only by the caller's grace: both callers assign a DAO-ordered list
(`AudiobookMediaSessionCallback.kt:374` uses the same `tracks` for `trackList` *and*
`buildPlaylist`, and `CurrentlyPlayingViewModel.kt:705` uses `_tracks`), and
`getTracksForAudiobookAsync` is `ORDER BY discNumber, index`. So the indices agree by convention,
not by construction — the same shape as the bug cu-115 fixed, one caller away from biting.

A `TrackIndex` value class alongside `BookOffset`/`TrackOffset` would close this properly: the
distinction that matters is *index into what*, and it is currently untyped.

---
id: cu-96
title: Chapter skip mixes absolute and in-track offsets
status: Done
labels: [R1, trust, bug]
dependencies: [cu-73]
priority: high
assignee: [claude]
milestone: m-1
---

## Description

Split from [[cu-93]], where it was found but deliberately not fixed: no multi-track book was
available to verify against, and guessing at a seek fix is how cu-93 acquired two wrong attempts.

`PlayerExt.skipToPrevious` (and `skipToNext`, identically) mixes two coordinate systems:

```kotlin
if ((currentPosition - currentlyPlaying.chapter.value.startTimeOffset) < THRESHOLD)
…
seekTo(containingTrackIndex, previousChapter.startTimeOffset)
```

`currentPosition` and `seekTo`'s second argument are both **within the current track**.
`Chapter.startTimeOffset` is **absolute within the book** — the invariant recorded in CLAUDE.md, and
already the source of cu-13, cu-49 and cu-93's display half.

The owner's books are a *single* multi-hour file, so absolute and in-track offsets coincide and the
arithmetic works by accident. On a multi-track book:

- the threshold comparison subtracts an absolute offset from an in-track position, so
  "am I near the start of this chapter?" is answered against a nonsense number;
- `seekTo` is handed an absolute offset as an in-track one, so it seeks far past the end of the
  target track — Media3 clamps, so the symptom is landing at a track boundary rather than throwing.

Fourth appearance of this confusion. It is worth fixing the *contract* rather than the call site.

## Acceptance Criteria

- [x] Reproduced on a synthetic three-track fixture with unequal durations — the frames disagree
      by construction, which is what a real multi-track book would have shown.
- [x] Decide and document the contract: either `Chapter` carries both offsets explicitly, or a
      helper converts absolute → (trackIndex, inTrackOffset) at every seek boundary.
- [x] Apply it to `skipToPrevious`, `skipToNext` and `jumpToChapter`.
- [x] Unit tests on a multi-track fixture — `TrackListStateManager` is pure Kotlin and already
      testable, so the conversion can be covered without a device.
- [ ] Verify on the device with both a single-track and a multi-track book.

## Notes

Consider whether `Chapter.startTimeOffset` should be renamed to make its frame unmistakable
(`bookStartTimeOffset`). Four bugs suggest the name is doing real damage — it reads as "offset from
the start of *something*", and the something has been guessed wrong every time.

## Implementation Notes

**Contract decided: the name carries the frame.** `startTimeOffset` →
`bookStartTimeOffset`, `endTimeOffset` → `bookEndTimeOffset`. The field comment had claimed "from
the start of the containing track", which is wrong and is the likely origin of four bugs. The
database columns keep their old names via `@ColumnInfo`, so there is no migration and no change to
the `Audiobook.chapters` serialization format — cu-82 will retire that dual write anyway, and a
schema change here would have been risk with no behavioural gain.

`chapterSeekTarget(chapter, tracks)` converts book-absolute → `(trackIndex, inTrackOffset)`.
Resolution is by `Chapter.trackId`, not by summing durations until the offset fits: a duration sum
drifts exactly on the malformed data this has to survive. Returns null for an unknown track rather
than letting `indexOf`'s `-1` reach `seekTo` as a media item index.

Three call sites fixed. `skipToPrevious` also had a second instance of the same confusion in its
threshold — it subtracted a book-absolute chapter start from `currentPosition`, which is in-track,
yielding a large negative on a multi-track book so "previous" *always* skipped back instead of
restarting the current chapter. `millisIntoChapter` now puts both operands in the book frame.

`jumpToChapter` (both ViewModels) fed the absolute offset into
`KEY_START_TIME_TRACK_OFFSET`, which the service applies in-track. Converted at the ViewModel,
where the track list is available, rather than changing the key's documented meaning.

### Two traps the rename itself created

Both caught by existing tests, which is worth recording:

- **`PlexChapter` is a Moshi wire model.** Its field names are Plex's JSON keys, so renaming them
  silently stopped chapters parsing — every offset would have defaulted to 0. `PlexFixtureContractTest`
  caught it. A comment now warns against renaming them.
- **The blanket rename rewrote the string inside `@ColumnInfo(name = "…")`**, defeating the whole
  point of using it. `RoomSchemaTest` caught this by opening a migrated *file*, which is precisely
  the check CLAUDE.md keeps it for.

Also folded in: the unexplained `+ 300` in `skipToNext` is now `CHAPTER_SEEK_NUDGE_MILLIS`, with the
reason (landing exactly on a boundary can still resolve to the previous chapter).

9 tests; reinstating the original bug fails 4. Coverage 22.44% → 22.55%.

**Still unverified on hardware** — the last acceptance criterion needs a real multi-track book on a
device. The arithmetic is covered, but the end-to-end seek is not.

---
id: cu-96
title: Chapter skip mixes absolute and in-track offsets
status: To Do
labels: [R1, trust, bug]
dependencies: [cu-73]
priority: high
assignee: []
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

- [ ] Reproduce on a genuinely multi-track book (most Plex audiobooks with per-chapter files).
- [ ] Decide and document the contract: either `Chapter` carries both offsets explicitly, or a
      helper converts absolute → (trackIndex, inTrackOffset) at every seek boundary.
- [ ] Apply it to `skipToPrevious`, `skipToNext` and `jumpToChapter`.
- [ ] Unit tests on a multi-track fixture — `TrackListStateManager` is pure Kotlin and already
      testable, so the conversion can be covered without a device.
- [ ] Verify on the device with both a single-track and a multi-track book.

## Notes

Consider whether `Chapter.startTimeOffset` should be renamed to make its frame unmistakable
(`bookStartTimeOffset`). Four bugs suggest the name is doing real damage — it reads as "offset from
the start of *something*", and the something has been guessed wrong every time.

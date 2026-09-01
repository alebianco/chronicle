---
id: cu-93
title: Previous-chapter button seeks to the wrong position
status: Draft
labels: [R1, trust, bug]
dependencies: [cu-73]
priority: high
assignee: []
milestone: m-1
---

## Description

Reported on the owner's device during cu-73: pressing **previous chapter** lands at the *end of the
previous chapter*, and then pressing play jumps forward to the next chapter. The 10-second skip
button behaves acceptably by comparison (it is not expected to land on a boundary).

### What is confirmed

Captured from the device at position ~28,726,000ms in *Dungeon Crawler Carl*:

```
Player.skipToPrevious called
skipToPrevious → back to start of current chapter
PREVIOUS CHAPTER: index=19 id=518 trackId=155595 offset=26879000 title=Chapter 20
```

So the **chapter selection is correct** — Chapter 20 at 26,879,000ms (7.47h) is the right target.
The positions reported to the server afterwards drift downward (7.99h → 7.98h → 7.97h → 7.84h) but
never arrive at 7.47h, so the seek is issued and playback does not settle there.

### The unit mismatch to investigate first

`PlayerExt.skipToPrevious` mixes two coordinate systems:

```kotlin
if ((currentPosition - currentlyPlaying.chapter.value.startTimeOffset) < THRESHOLD)
…
seekTo(containingTrackIndex, previousChapter.startTimeOffset)
```

`currentPosition` and `seekTo`'s second argument are both **within the current track**, while
`Chapter.startTimeOffset` is **absolute within the book** (the invariant in CLAUDE.md, and the
source of two previous bugs — cu-13 and cu-49).

These books are a *single* multi-hour track, so absolute and in-track offsets coincide and the
arithmetic accidentally works. **A multi-track book would seek to a wildly wrong position**, and
that is likely a separate live bug not yet observed simply because the owner's current book is
single-file. `skipToNext` (same file, line ~48) has the identical shape.

### Not yet explained

Why the seek does not land, given the target is right. Candidates, in order:

1. `progressUpdater.updateProgressWithoutParameters()` fires immediately after `seekTo`, reading the
   controller position *before* the seek completes — the same race as the `time=0` bug fixed in
   cu-73, which would write the pre-seek position straight back.
2. The auto-rewind in `AudiobookMediaSessionCallback.playBook` re-applying on resume.
3. `trackListStateManager` not being told about the new position, so the next tick restores its own.

Candidate 1 is the strongest: it is the same defect shape, in the same file, found the same evening.

## Acceptance Criteria

- [ ] Reproduce with a **multi-track** book as well as a single-track one — the unit mismatch should
      be far more visible there, and confirms or rules out the coordinate bug.
- [ ] Decide the coordinate contract for `skipToPrevious`/`skipToNext` and make it explicit: convert
      absolute chapter offsets to in-track offsets at the seek, or document that they coincide and
      enforce it.
- [ ] Fix the seek so playback lands at the chapter start and stays there.
- [ ] Confirm no progress report is emitted between `seekTo` and the seek completing.
- [ ] Unit tests for the offset conversion on a multi-track book (`TrackListStateManager` is pure
      Kotlin and already testable).
- [ ] Re-check on the device, both single- and multi-track.

## Notes

Related to the observation that the **percentage updates before the timeline**: they read different
sources (`tracks` from Room, written every second, versus `currentlyPlaying.track`, refreshed only
by playback callbacks). Same family as cu-87. Filed separately as [[cu-94]].

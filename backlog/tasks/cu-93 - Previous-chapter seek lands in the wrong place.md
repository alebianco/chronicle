---
id: cu-93
title: Previous-chapter button seeks to the wrong position
status: Done
labels: [R1, trust, bug]
dependencies: [cu-73]
priority: high
assignee: [claude]
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

- [ ] Reproduce with a **multi-track** book — still open, see Follow-ups below.
- [ ] Make the coordinate contract for `skipToPrevious`/`skipToNext` explicit — still open.
- [x] Fix the seek so playback lands at the chapter start and stays there.
- [x] Confirm no progress report is emitted between `seekTo` and the seek completing.
- [x] Tests for the parts that are unit-testable.
- [x] Re-check on the device (single-track).

## Implementation Notes

The premise was wrong: **the seek was correct all along.** The device log shows it landing on
exactly 26879000, Chapter 20's first millisecond. Three separate defects produced the symptom.

**1. Two chapter lookups disagreed on a boundary.** `getChapterAt` used an inclusive
`start..end` range while `chapterAtBookProgress` was half-open, so a position exactly on a boundary
resolved to the *earlier* chapter through one and the *later* through the other. Seeking to a
chapter start lands on a boundary every time, so the readout always named the previous chapter —
which is what "goes to the end of the previous chapter" actually was. `getChapterAt` is now
half-open and agrees with its sibling; the final chapter's own end stays accepted explicitly.
`ChapterBoundaryTest`, 7 cases on the owner's real offsets.

**2. The UI was thrashing.** Measured on the device: **228 recomputations in one minute**, eight
inside 55ms, every one carrying an identical position. `ProgressUpdater` publishes book, track and
tracks once a second *and* writes the DB, which re-triggers the tracks LiveData; every `combine`
downstream fired on all of them and each wrote to the slider. Fixed with `distinctUntilChanged` on
the slider and chapter flows.

**3. The slider guard was in the wrong place.** `isSliding` filtered two of the four flows that
call `refreshSlider()`; `currentTrack` and `chapterDuration` are unfiltered and fire every tick, so
the stale position reached the thumb through a side door. The guard moved into `refreshSlider`
itself — the single line that moves the slider — and now holds from touch-down until playback
reports the requested position, with a 5s timeout so a seek that never lands cannot freeze it.

Also: `onSeekTo` now publishes the new position immediately rather than waiting up to a second for
the next tick, reading **ExoPlayer's own position** rather than the session state, which the seek has
not updated yet — reading that would have recreated the `time=0` race.

### What I got wrong on the way

Two fixes shipped before this one, both reasoned from the code rather than measured. The
`isSliding`-on-the-flows attempt and the `awaitSeek` hold were each individually correct and
individually insufficient, because the real cause was churn neither addressed. **The 228/minute log
is what solved this**, not any amount of reading.

### Follow-ups

The coordinate mismatch is **real but latent** and is *not* fixed here: `skipToPrevious` compares
and seeks with in-track positions while `Chapter.startTimeOffset` is absolute within the book. The
owner's books are single-track so the two coincide. A multi-track book should seek somewhere wildly
wrong; `skipToNext` has the identical shape. Split to [[cu-96]] rather than fixed blind, since no
multi-track book was available to verify against.

## Notes

Related to the observation that the **percentage updates before the timeline**: they read different
sources (`tracks` from Room, written every second, versus `currentlyPlaying.track`, refreshed only
by playback callbacks). Same family as cu-87. Filed separately as [[cu-94]].

---
id: cu-87
title: Chapter highlight is stale until playback starts
status: In Review
labels: [R2, comfort, bug]
dependencies: [cu-13]
priority: medium
assignee: [claude]
---

## Description

Owner-reported (2026-08-31): *"chapter list highlights the wrong chapter compared to the timeline
position when coming back. Syncs it when playback starts."*

`CurrentlyPlayingSingleton` holds the current chapter as state seeded to `EMPTY_CHAPTER`:

```kotlin
override val chapter = MutableStateFlow(EMPTY_CHAPTER)
```

and only recomputes it inside `update()`:

```kotlin
val chapter = chapters.getChapterAt(track.id, track.progress)
```

`update()` runs on playback events. So returning to a screen without playing shows whatever was
last set — `EMPTY_CHAPTER` after a process restart, or a stale chapter from a previous book — and
pressing play "fixes" it. The highlight itself (`ChapterListAdapter.isActive`, keyed on
trackId + discNumber + index) looks correct; it is being fed a stale value.

Note [[cu-13]] and [[cu-49]] fixed two *offset* bugs that would also have produced a wrong
highlight (`asChapterList` returning an empty list, and a per-track `0L` offset making
`getChapterAt` match nothing). Those are prerequisites — check whether this symptom survives them
before building anything, since a book with no embedded chapters previously had no chapters at all
to highlight.

## Fix direction

Derive the current chapter from the saved position when the screen is shown, not only from playback
events — the data is already there (`track.progress` plus the chapter list). Cheapest correct form
is to recompute on the same inputs the UI already observes rather than caching it in a singleton.

## Acceptance Criteria

- [x] Opening a book with saved progress, without playing, highlights the chapter containing that
      position
- [ ] Verified after a process restart, not just an in-session navigation — **needs a device**
- [x] A book with no embedded chapter data highlights the correct per-file chapter
- [x] Test covers position → expected chapter for a multi-file book, including a position in a
      later file
- [x] Verify loop green

## Implementation Notes

### A wrong first diagnosis, corrected

I first read `AudiobookDetailsViewModel.cachedChapter`'s hand-rolled walk as *doubly inconsistent* —
comparing a book-absolute progress against `endTimeOffset` while also subtracting each chapter's
span, as if offsets were track-relative. **That was wrong.** Probed it directly with three positions
and it returns the right chapter every time: the subtraction only runs for chapters already passed,
so the two conventions do not actually collide. The details screen was fine.

### The real cause: two readers, one with a fallback and one without

`CurrentlyPlayingFragment` observes **two different chapter values**:

- line 170 → `currentChapter`, which was the raw `currentlyPlaying.chapter`, for the timeline
- line 230 → `activeChapter`, which already combined the singleton value with a `cachedChapter`
  derived from saved progress, for the list highlight

So on returning to the screen without playing, the highlight was derived from saved progress while
the timeline read a stale or empty chapter — **the two disagreed**, which is exactly the report:
*"chapter list highlights the wrong chapter compared to the timeline position."* The fallback for one
reader had been written; the other never got it.

`currentChapter` is now the same value as `activeChapter`.

### And the source, not just the readers

Fixing the two readers would have left every *other* consumer stale. `CurrentlyPlayingSingleton`
publishes `getChapterAt(track.id, track.progress)`, which matches on trackId **and** a timestamp
inside the chapter's span, and returns `EMPTY_CHAPTER` when either misses — starting from
`EMPTY_CHAPTER` and only recomputed by `update()`, called from playback callbacks only.

That matters beyond display: **`PlayerExt` drives skip-to-next-chapter and skip-to-previous-chapter
off `currentlyPlaying.chapter.value`**, so a stale value skips to the wrong place. `NotificationBuilder`
reads it too.

The singleton now falls back to `chapters.chapterAtBookProgress(tracks.getProgress())` — a new
book-absolute lookup, which is the same thing both ViewModels hand-rolled, now in one tested place.
It returns the *last* chapter for a position past the end rather than `EMPTY_CHAPTER`, so a finished
book still reports where it finished.

8 tests, verified to bite (dropping the sort, and returning empty past the end, each fail one).

### Not done

- **Not verified after a process restart**, which is the case the owner described ("when coming
  back"). The unit tests cover the derivation; whether the singleton is populated early enough on a
  cold start needs a device → [[cu-73]].
- The two ViewModels still hand-roll their own `cachedChapter` walks. They could now use
  `chapterAtBookProgress` instead, which would remove the duplication — deliberately left alone here
  to keep this change to the defect.

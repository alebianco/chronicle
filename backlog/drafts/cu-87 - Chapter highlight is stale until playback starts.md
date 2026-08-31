---
id: cu-87
title: Chapter highlight is stale until playback starts
status: To Do
labels: [R2, comfort, bug]
dependencies: [cu-13]
priority: medium
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

- [ ] Opening a book with saved progress, without playing, highlights the chapter containing that
      position
- [ ] Verified after a process restart, not just an in-session navigation
- [ ] A book with no embedded chapter data highlights the correct per-file chapter
- [ ] Test covers position → expected chapter for a multi-file book, including a position in a
      later file
- [ ] Verify loop green

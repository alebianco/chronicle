---
id: cu-13
title: Chapter correctness on neutral Chapter model
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-15, cu-71]
priority: high
milestone: m-1
---

## Description

Multi-file mapping, duplicate names, current-chapter highlight, chapterSource respected; album-art-not-track-art fix (#119/#76/#12/#113). Per D11: build against a backend-neutral Chapter entity fed by the adapter — the fix must be inherited by ABS/local later.

## Acceptance Criteria

- [~] Chapters match m4b embedded data — **the code defect is fixed; the match itself is a
      live check.** Chapters come from Plex's `retrieveChapterInfo`, i.e. Plex's own read of the
      file, so verifying the *match* means comparing against the file (`ffprobe -show_chapters`)
      on a real server → [[cu-73]]. What was broken in the app and is now fixed: the no-embedded-
      chapters fallback returned nothing.
- [~] Correct cover always shown — **not reproducible offline, deferred to [[cu-73]]** with the
      finding written down (see notes).
- [x] Chapter logic has no Plex imports — holds for the live path (`Chapter.kt` is Plex-free);
      the dead `ChapterRepository` still has them and is documented as [[cu-49]]'s to fix.

## Implementation Notes

### The real bug: `asChapterList` discarded every chapter it built

```kotlin
for (track in this) {
  track.asChapter(cumStartOffset)   // built, then dropped
  cumStartOffset += track.duration
}
return outList                       // always empty
```

`outList` was never appended to, so **the function returned an empty list for every book.** Four
live call sites depend on it as the fallback when a book has no embedded chapter data —
`CurrentlyPlayingSingleton`, `CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel`,
`MainActivityViewModel`, all shaped `if (book.chapters.isNotEmpty()) book.chapters else
tracks.asChapterList()`. So a book Plex has no chapter data for showed **no chapters at all**,
in the player and in book details, instead of one per file.

A second defect in the same pair: `asChapter` set `endTimeOffset = duration` rather than
`startOffset + duration`. Offsets are absolute within the book, so every chapter after the first
reported an end *earlier than its own start* — and `getChapterAt` matches on a timestamp falling
inside `startTimeOffset..endTimeOffset`, so it would have resolved nothing even once the list was
populated. Both had to be fixed for either to work.

8 tests, written failing first: **7 of 8 failed** before the fix (the empty-input case passed,
since empty is the right answer there). They cover per-track production, cumulative offsets, each
chapter spanning exactly its own track, trackId/title propagation, and the `getChapterAt`
round-trip that the offset bug broke.

### The artwork issue: written down rather than speculatively fixed

`toMediaMetadata` sets `albumArtUri` from `MediaItemTrack.thumb` — Plex's *per-track* thumb —
which is the mechanism behind #119 (chapter art instead of the book cover on lockscreen/Auto).

I could not reproduce it. In the fixture pack the track thumb is
`/library/metadata/1001/thumb/...` — **1001 is the book's ratingKey**, so the fixture's per-track
thumb already *is* the album art, and no fixture models `parentThumb` at all. Adding a
`parentThumb` field would have been a change I could neither demonstrate broken nor verify fixed,
so instead the mechanism is commented at the assignment site and the check is in [[cu-73]]: on a
real library, find whether any track carries its own art. If one does, model `parentThumb` and
prefer it.

### Stale analysis file

`backlog/docs/analysis/archive/M4-chapter-management-refactor-plan.md` (now archived) is linked
from [[cu-49]], not this
task, and is in poor shape: its sections are in **reverse order** (Problem Statement last, Phase 6
before Phase 1) and its code blocks are shredded. Its premise is also partly stale — it proposes
creating `ChapterDatabase`/`ChapterDao`, which now exist (migrated to v2 in [[cu-71]]) but are
wired to nothing. Left for cu-49 to rewrite or archive, since it is that task's reference.

### Not done

- `chapterSource` (from the original one-line description) **does not exist anywhere in the
  codebase**. There is no such concept to respect; if it was meant as "prefer embedded over
  server-derived chapters", that is a design question for cu-49.
- Duplicate chapter names and the current-chapter highlight were not touched: the highlight logic
  (`ChapterListAdapter.isActive`, keyed on trackId + discNumber + index) looks correct by
  inspection, and its failure mode was most likely the empty chapter list fixed here. Verifying
  that is a [[cu-73]] check.

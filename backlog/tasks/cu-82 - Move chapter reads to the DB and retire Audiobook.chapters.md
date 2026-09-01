---
id: cu-82
title: Move chapter reads to the DB and retire Audiobook.chapters
status: To Do
labels: [R2, architecture]
dependencies: [cu-49]
priority: medium
milestone: m-2
---

## Description

Steps 6–7 of [[cu-49]], carved out because they need more than a swap and cu-49 had already
reached a safe midpoint: chapters are written to `ChapterDatabase` and *also* still stored on
`Audiobook.chapters`, so the app works with a redundant column.

## Why this isn't a one-line change

1. **The table fills lazily.** `BookRepository.syncAudiobook` is the only writer and runs per book
   when its tracks load. For a library synced by an earlier version the table is empty, so a read
   site switched straight to the DAO shows **no chapters at all** until each book happens to
   re-sync. The read therefore needs to be DAO-first with a fall back to `Audiobook.chapters`,
   giving a three-level chain: table → book column → `asChapterList()` (the no-chapter-data
   fallback fixed in [[cu-13]], which stays).
2. **All four read sites are `DoubleLiveData` combinators over the book** —
   `CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel`, `MainActivityViewModel` and
   `CurrentlyPlayingSingleton`. Each needs its reactive wiring restructured around a DAO-backed
   `LiveData` (`ChapterDao.getChaptersForBookLive` already exists), not just a changed expression.

Consider a one-off backfill instead of a permanent fallback: on first launch after upgrade, walk
the books that have `chapters` and no rows, and write them. That turns a three-level chain into a
two-level one and lets step 7 happen sooner — worth costing against just leaving the fallback.

## Acceptance Criteria

- [ ] All four read sites take chapters from `ChapterDao`, with the `asChapterList()` fallback
      preserved for books with no chapter data
- [ ] A book synced by an earlier version (rows absent, `Audiobook.chapters` populated) still shows
      its chapters — the upgrade regression this task exists to avoid, covered by a test
- [ ] Chapter highlight and jump-to-chapter still work across a track boundary
- [ ] Only then: `Audiobook.chapters` removed, with a `BookDatabase` migration and a file-backed
      test verified to bite
- [ ] `ChapterListConverter` and its tests removed with the column, or a written reason to keep them
- [ ] Verify loop green

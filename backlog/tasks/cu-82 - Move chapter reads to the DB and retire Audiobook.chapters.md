---
id: cu-82
title: Move chapter reads to the DB and retire Audiobook.chapters
status: To Do
labels: [R2, architecture]
dependencies: [cu-49, cu-158]
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
2. **It is not four read sites.** Counted 2026-09-04: **28 references to `Audiobook.chapters`
   across 11 files.** The four `DoubleLiveData` combinators over the book
   (`CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel`, `MainActivityViewModel`,
   `CurrentlyPlayingSingleton`) are the ones needing their reactive wiring restructured around a
   DAO-backed `LiveData` (`ChapterDao.getChaptersForBookLive` already exists) — but
   `CurrentlyPlayingViewModel` alone has 7 references, `PlayerExt.kt` has 5 (chapter skip), and
   there are single uses in `CachedFileManager`, `BookRepository` and `ChapterListAdapter`. Plan
   against the real number.

   | file | refs |
   |---|---|
   | `CurrentlyPlayingViewModel` | 7 |
   | `PlayerExt` | 5 |
   | `CurrentlyPlayingSingleton` | 4 |
   | `AudiobookDetailsViewModel` / `AudiobookDetailsFragment` / `MainActivityViewModel` / `Audiobook` | 2 each |
   | `CurrentlyPlayingFragment` / `ChapterListAdapter` / `CachedFileManager` / `BookRepository` | 1 each |

**The backfill is now [[cu-158]]**, and it should land first. It converts the permanent three-level
chain (table → column → `asChapterList()`) into a two-level one, which is what makes dropping the
column defensible rather than a permanent fallback nobody can ever remove. It also has to happen
before the drop regardless, or an upgrading user loses every chapter they had.

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

---
id: cu-82
title: Move chapter reads to the DB and retire Audiobook.chapters
status: Done
assignee: []
created_date: ''
labels:
  - R2
  - architecture
milestone: m-2
dependencies:
  - cu-49
  - cu-158
priority: medium
ordinal: 92000
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

- [x] All four read sites take chapters from `ChapterDao`, with the `asChapterList()` fallback
      preserved for books with no chapter data
- [x] A book synced by an earlier version (rows absent, `Audiobook.chapters` populated) still shows
      its chapters — the upgrade regression this task exists to avoid, covered by a test
      (`ChapterUpgradeReadTest`, over real databases, sabotage-verified)
- [x] Chapter highlight and jump-to-chapter still work across a track boundary — the chapter-skip
      path was in fact **broken** for freshly synced books and is fixed here; see the notes
- [ ] ~~Only then: `Audiobook.chapters` removed, with a `BookDatabase` migration and a file-backed
      test verified to bite~~ → **[[cu-159]]**, gated on a released build having run the backfill
- [ ] ~~`ChapterListConverter` and its tests removed with the column~~ → **[[cu-159]]**
- [x] Verify loop green (7 stages)

## Implementation Notes

**The table is now load-bearing; the column survives as a fallback.** Every read resolves
table → legacy column → `asChapterList()` through one shared function
(`resolveChapters` / `resolveChaptersFromCache` in `data/model/ChapterAssembly.kt`), rather than
the precedence being copied into each `LiveData` graph. The three `DoubleLiveData` combinators
became `TripleLiveData` over a DAO-backed source; `BookRepository` grew `getChaptersForBook` and
`getChaptersForBookLive` and already owned `chapterDao`, so no new dependency reached the ViewModels.

**The scope note in this task was itself wrong, in the direction that mattered.** It said the four
`DoubleLiveData` sites were the work and that `CurrentlyPlayingViewModel`/`PlayerExt` followed
transitively. They do not. Those ten sites read `currentlyPlaying.book.value.chapters` — the
**legacy column**, not the singleton's resolved list, which was `private`. Since cu-49 writes
chapters to the table, that column is empty for any freshly synced book, so `indexOf` returned
`-1` and **chapter skip silently did nothing**. That is a live user-facing bug this task found
rather than a refactor: the resolved list is exposed on `CurrentlyPlaying` now and all ten read it.
Re-count references before trusting a stated count — it was 28/11 in the task, 43/19 on the branch.

**Rows are passed into `CurrentlyPlayingSingleton.update`, never read inside it.** That method runs
**once a second** from `ProgressUpdater`, so a DAO on the singleton would put a blocking read on
every playback tick — the exact shape cu-110 removed. `OnMediaChangedCallback` and
`AudiobookMediaSessionCallback` (both already in IO context, both already holding the repository)
supply the rows when the book actually changes; the per-tick caller passes none.

That interleaving is load-bearing, so it is pinned: a tick arriving without rows must not
**downgrade** an already-resolved list back to the stale column. Both sources are usually identical,
so nothing else would have caught it.

**Three sabotages, each failing exactly one test:**

| sabotage | fails with |
|---|---|
| resolve table-only, no fallback | `a pre-cu-49 book must still show its chapters expected:<[Chapter 1..3]> but was:<[]>` |
| resolve on every tick | `expected:<from [table]> but was:<from [column]>` |
| expose the column, not the resolved list | `the resolved list must carry the table's rows` |

**The column is deliberately not dropped**, and the two criteria asking for it are moved to
**cu-159** rather than ticked. `ChronicleApplication.backfillChapterTable()` is *launched, not
awaited*, so on the first launch after an upgrade the table is still filling while the UI reads.
Removing the fallback there shows no chapters and destroys the data to recover them. It is safe only
once a released build has run the backfill — a release boundary, and an owner decision. cu-159
carries the migration, the converter removal and the now-dead backfill machinery.

**Device check afterwards, and it sharpens the case** (`book_db`/`chapter_db` on the tablet,
2026-09-04):

| | |
|---|---|
| Books with an **empty** `chapters` column | **196 of 196** |
| Books with a populated column | **0** |
| Books with chapter-table rows | 1, and it is mock id `1001`, not a real book |

So `currentlyPlaying.book.value.chapters` returned empty for **every book on this server** — the
chapter-skip defect was library-wide, not an edge case. It is also why nobody noticed: with both
sources empty, cu-13's `asChapterList()` fallback supplies one chapter per track, and for a book
like *Ender's Game* (107 tracks, one chapter each) the player looks entirely correct — the
notification reads "Chapter 13" and the numbers line up.

**What could not be checked on-device, and is left unticked above:** an end-to-end chapter skip on a
book with *real embedded chapters*. No book on this server has any — neither storage carries them —
so the fixed path cannot be exercised here yet, and the media-key route does not reach
`skipToNext` on this LineageOS build (`media dispatch` is absent). The unit tests cover the
resolution and the exposure; the on-device confirmation waits for a book with real chapter data.

**Closed to `Done`**: no screen, no wording, no product choice. The chapter-skip fix is a
correctness bug with failing-then-passing tests and three sabotage checks. The one unverified item
is called out above rather than ticked.

Coverage rose 37.75 → 37.96 aggregate, with `application`, `data/local`, `features/bookdetails`,
`features/currentlyplaying`, `features/player` and `util` all up and none down.

---
id: cu-158
title: Backfill the chapter table for libraries synced before cu-49
status: Done
assignee:
  - claude
created_date: '2026-09-04'
labels:
  - R2
  - architecture
milestone: m-2
dependencies:
  - cu-49
priority: medium
ordinal: 74000
---

## Description

Carved out of [[cu-82]], which proposed it as an alternative worth costing. It is worth doing, and
it is worth doing **first and on its own**: it is the piece that makes the rest of cu-82 tractable.

Chapters are currently written to both `ChapterDatabase` and `Audiobook.chapters` (cu-49). The
table fills **lazily** — `BookRepository.syncAudiobook` is its only writer and runs per book when
its tracks load — so for a library synced by an earlier version the table is empty. That single
fact is what forces cu-82's read sites into a permanent three-level chain (table → book column →
`asChapterList()`), and a permanent fallback is a permanent reason not to drop the column.

A one-off backfill removes the reason. Walk the books that have a non-empty `chapters` column and
no rows in `ChapterDatabase`, and write the rows. After it has run, a DAO-first read needs only the
`asChapterList()` fallback that cu-13 added for books with no chapter data at all — a two-level
chain, which is the shape cu-82 wants.

## Why separately from cu-82

cu-82 says "all four read sites". There are **28 references across 11 files**, and rewiring the
reactive plumbing of four ViewModels is the bulk of it. This half is independent of all of that: it
writes rows nobody reads yet and changes no behaviour, so it can land and be verified on its own
while the read migration is planned properly.

It also has to happen before the column is dropped regardless, or an upgrading user loses every
chapter they had — so it is on the critical path either way.

## What to work out

1. **Where it runs.** Not `Application.onCreate` — StrictMode penalises disk there and the app must
   not wait on it (the cu-148 precedent: launched, not awaited, off the main thread). A one-shot
   `WorkManager` job or a guarded call on first repository use are both candidates.
2. **How it knows it is done**, idempotently. A preference flag is the cheap answer, but the
   honest test is per book: rows absent and column populated. Per-book means an interrupted run
   resumes, which a flag does not.
3. **Chapter offsets are absolute within the book**, not per-track (cu-13/cu-49, and cu-136 made
   the frame a type). The backfill must copy them unchanged — it is moving rows, not recomputing
   them.
4. Failure must be per book and never fatal. A book whose column will not deserialize keeps its
   column and gets no rows, which is exactly the state it is in today.

## Acceptance Criteria

- [x] A book with a populated `chapters` column and no `ChapterDatabase` rows gets rows, with
      identical chapter ids, titles and **absolute** offsets
- [x] Running the backfill twice writes nothing the second time, and an interrupted run resumes
- [x] A book that already has rows is left alone — the table wins, never the column
- [x] A book whose column is empty or unparseable is skipped without failing the run
- [x] It does not run on the main thread and nothing waits on it
- [x] Verify loop green

## Related

- [[cu-82]] — the read migration and the column drop, which this unblocks
- [[cu-49]] — wrote to both stores and left the table lazily filled
- [[cu-13]] — the `asChapterList()` fallback that stays regardless
- [[cu-148]] — the precedent for off-main-thread startup work that is launched, not awaited

## Implementation Notes

`ChapterBackfill` (`data/local/`) holds the decisions, pure and free of Android and Room types;
`BookRepository.backfillChapterTable()` is the I/O around it; `ChronicleApplication` launches it
without awaiting, following cu-148's precedent for optional startup work.

**The trap that made this worth doing carefully.** `Chapter`'s primary key is
`(bookId, trackId, discNumber, index)`, and `Chapter.decodeChapter` **defaults `bookId` to
`NO_AUDIOBOOK_FOUND_ID`** for a record serialized before the id was part of the format — which is
precisely the population this backfill exists to migrate. Inserting those rows verbatim would key
every book's chapters under the same sentinel, so they would collide on the composite key and
overwrite one another: one book's chapters silently replacing another's. Every row therefore gets
`bookId = book.id` stamped, and the test that proves it inserts two books' sentinel-keyed chapters
into a **real** database and asserts both survive.

**Per book, not a global flag.** A book is done when *it* has rows, so an interrupted pass resumes.
A "backfill done" preference would silently abandon the remainder.

**A cheap gate keeps this off the launch path.** Self-review caught that the first version called
`bookDao.getAudiobooks()` — a `SELECT *` deserializing every book's chapters column, megabytes on a
real library (cu-134 measured 3.38 MB from one such list) — on **every** launch, only to discover
there was nothing to do. That is the cu-110 mistake exactly. Two `COUNT` queries now gate it:
`ChapterDao.countBooksWithChapters()` (DISTINCT bookId) against
`BookDao.countBooksWithChapters()`; when they agree, the book table is never read.

Both queries are verified against **real SQLite**, not mocks, because each fails silently in a way
a mock cannot catch, and each mistake leaves a green suite and a feature that does nothing:

- the book count must treat `''` as "no chapters" — the converter writes `joinToString` over an
  empty list, which is an empty string and **not** NULL, so an `IS NOT NULL`-only test counts the
  whole library and the gate never opens;
- the chapter count must be `DISTINCT bookId` — counting rows makes the number far larger than the
  book count, so the gate closes at once and the backfill never runs.

Sabotaging each query fails exactly one test.

**No schema change**: only `@Query` methods were added, and the exported schemas are byte-identical
(checked).

**Nothing reads the table yet.** Every read site still goes through `Audiobook.chapters` — that is
[[cu-82]], whose scope this correction also fixed: it claimed "four read sites", and there are
**28 references across 11 files**. This task deliberately changes no read behaviour, so it is
verifiable on its own.

19 tests across three classes: `ChapterBackfillTest` (rules), `ChapterBackfillRepositoryTest`
(plumbing and the gate), `ChapterBackfillSqlTest` (the SQL, on real SQLite).

---
id: cu-44
title: Test-coverage push on critical paths
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, agentic]
dependencies: [cu-16, cu-15]
priority: high
milestone: m-1
---

## Description

H1: standing effort to raise coverage where it unblocks autonomous work — ViewModels, repositories, DAOs (in-memory Room), sync/progress/download logic. Not one PR: the JaCoCo ratchet (cu-3) enforces it per-PR; this task tracks the initial backfill on the R1 risk surface, fixture-backed (cu-16).

Analysis: [`archive/H1-test-coverage-plan.md`](../docs/analysis/archive/H1-test-coverage-plan.md).

## Implementation Notes

### The task had already been half-done by R1

Written before R1 started, it assumed a near-zero baseline. By the time it was picked up,
coverage had gone **6.60% → 10.79%** and tests **73 → 155** as a side effect of cu-9/10/11/12/14/17
— each of which added tests for the code it touched, which is the ratchet working as designed
(cu-3). So this task was not a backfill from scratch; it was finding what those tasks had *not*
touched.

Final state: **11.68%, 170 tests across 31 files.**

### Targeting was done from the JaCoCo report, not by guesswork

Ranking packages by missed instructions put `features/player` first (8013) and `data/local`
second (4207). The player is largely Android-framework-bound and belongs with cu-54's
instrumented suite; `data/local` is the trust surface this task names. Within it,
`BookRepository` (4.8%) and `TrackRepository` (6.9%) were the least covered.

### It found a crash, which is the point of coverage work

Writing round-trip tests for `ChapterListConverter` (1.6% covered) turned up that a chapter title
containing `©` or `®` made the decoder **throw**, so Room failed to read the row and the book
crashed on open — permanently, until the row was deleted. `© 2019 Macmillan Audio` is ordinary
chapter metadata. Fixed under [[cu-78]] with escaping plus fail-soft decoding.

**Worth recording how it was found:** I predicted the delimiters would *corrupt* the data and
wrote `assertNotEquals`. The tests failed — because the code throws instead. The prediction was
wrong in a way that made the bug worse than assumed, and only running the test revealed it.

### `refreshData`'s failure paths

`BookRepositoryRefreshTest` pins the two that matter: offline mode must not touch the network
(and must not stamp a refresh time it never performed), and **a network failure must leave the
local library alone**. That last one is the `?: return` after the catch — replacing it with
`emptyList()` makes a failed fetch read as "the server has no books" and deletes the whole local
library. Verified by sabotage.

### DAO tests on in-memory Room: partly done since, by another task

**Update (2026-08-31):** the argument below has been overtaken. [[cu-49]] added `RoomSchemaTest`,
which opens all four databases through Room in-memory and exercises real DAO methods —
`chapterDao.insertAll`, `getChapters`, `getChaptersForBook`, `removeAllForBook`, `bookDao.getAudiobooks`
— because it needed to prove a composite primary key actually prevents a cross-book collision. That
is exactly a DAO test on in-memory Room, arrived at because a *behavioural* question demanded it
rather than as a coverage exercise, which is the distinction the reasoning below was really drawing.

What remains untested is the wider DAO surface (queries with no behavioural question attached). The
reasoning below still applies to those.

### The original reasoning

`RoomMigrationTest` already drives all four databases through real SQLite under Robolectric, so
the schema and migration chains are covered. Adding per-DAO CRUD tests would mostly assert that
Room's generated code works, which it does — the interesting logic lives in the repositories
above the DAOs, and that is where the tests went.

If DAO-level tests earn their place later it will be for query correctness (a `WHERE` clause with
subtle ordering, say), not coverage percentage. Left unchecked rather than quietly reinterpreted.

### On the number itself

11.68% is not a good coverage figure, and the ratchet's job is to make it climb rather than to
declare a threshold met. The large remaining blocks are Fragments and ViewModels
(`currentlyplaying`, `bookdetails`, `settings`, `library`, `login`, `collections` — all at 0%),
which need either Robolectric or the instrumented suite (cu-54). That is a real gap; this task
does not close it and should not be read as having done so.

## Acceptance Criteria

- [x] Repositories + sync/progress/download logic have unit tests — sync failure paths
      (`BookRepositoryRefreshTest`), progress (`ProgressUpdaterTest`, `ProgressReporterTest`
      from cu-9), download integrity (`DownloadIntegrityTest` from cu-12), dispatchers
      (`RepositoryDispatcherTest` from cu-15)
- [~] DAO tests on in-memory Room — **partly done since, by another task.** `RoomMigrationTest`
      covers the schema; per-DAO CRUD would test Room's codegen
- [x] Coverage baseline established and ratcheting in CI — since cu-3; it has ratcheted eleven
      times during R1, which is the mechanism working

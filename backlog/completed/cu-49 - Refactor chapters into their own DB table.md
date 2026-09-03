---
id: cu-49
title: Refactor chapters into their own DB table
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: [cu-13, cu-15]
priority: medium
milestone: m-1
---

## Description

M4: chapters are embedded in tracks/books, complicating queries. Design a chapter schema + ChapterDao + migration; playback + UI read chapters from the DB. Sequence with cu-13 (chapter correctness) and the neutral Chapter model (cu-13 builds it; this persists it).

Analysis: superseded by the plan below; the old file is archived at
[`archive/M4-chapter-management-refactor-plan.md`](../docs/analysis/archive/M4-chapter-management-refactor-plan.md).

## Implementation Plan

Ground truth from the code (2026-08-31), because the linked analysis file is unreliable — see
"Analysis file" below.

**What already exists:** the `Chapter` entity, `ChapterDatabase` (v2 after [[cu-71]]),
`ChapterDao` with `getAllRows`/`getChapters`/`insertAll`/`update`/`updateCachedStatus`/`removeAll`,
and `ChapterRepository` with `loadChapterData`. **None of it is wired**: no Dagger module provides
`ChapterDatabase` or `ChapterRepository`, and nothing injects them.

**Where chapters actually live:** serialized into `Audiobook.chapters` by `ChapterListConverter`,
fetched by `BookRepository.loadChapterData`, and read directly off the book in four places
(`CurrentlyPlayingSingleton`, `CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel`,
`MainActivityViewModel`), each falling back to `tracks.asChapterList()`.

### The blocker to solve first: `Chapter.id` is not a safe primary key

`Chapter.id` is `@PrimaryKey`, and it is populated from two different namespaces:

- **Plex path** — `PlexChapter.id` (fixture: 4001–4003)
- **Fallback path** (`MediaItemTrack.asChapter`) — the **track id** (fixture: 2001–2003)

Both write into one table keyed by `id`. In the fixtures the ranges happen not to overlap, but
nothing guarantees that: Plex assigns chapter and track ratingKeys from the same server-wide
sequence. Today this is harmless because chapters are serialized per book; as a shared table's
primary key, `insertAll` with `OnConflictStrategy.REPLACE` would **silently overwrite one book's
chapter with another's**. Relying on the ranges not colliding is the same accidental correctness
that the missing-`parentKey` bug was made of.

**Decision: composite primary key `(bookId, trackId, discNumber, index)`**, which is unique
without depending on either id namespace. `id` stays as a plain column (it is still the value the
server gave, useful for debugging and for `updateCachedStatus`). This makes the migration a table
rebuild — SQLite cannot alter a primary key — through the same `rebuildTableWithTextIds` path
[[cu-71]] used, so `ChapterDatabase` goes to **v3**.

### Steps

1. **Composite key + migration to v3.** Rebuild `Chapter` with
   `@Entity(primaryKeys = ["bookId", "trackId", "discNumber", "index"])`. Add
   `CHAPTER_MIGRATION_2_3`, append to `CHAPTER_MIGRATIONS`, export schema 3.
   *Test:* file-backed migration test in `RoomSchemaTest`, plus a test that two books whose
   chapters share an `id` both survive `insertAll` — the collision the old key allowed.
2. **`bookId` is currently never populated.** `PlexChapter.toChapter` does not set it and
   `asChapter` does not either, so every chapter carries `NO_AUDIOBOOK_FOUND_ID`. With `bookId`
   in the primary key this must be fixed first or every chapter in a book collides on it.
   *Test:* chapters from both paths carry the right `bookId`.
3. **DAO query by book.** Add `getChaptersForBook(bookId): List<Chapter>` and a LiveData variant;
   `getChapters()` returning the whole table is not a useful read path for the UI.
4. **Provide the database and repository in Dagger**, and give `ChapterRepository` its data
   through the existing repositories rather than `PlexMediaService` directly — its current Plex
   imports are why [[cu-13]]'s "no Plex imports" criterion is only half met.
5. **Move the write.** `BookRepository.loadChapterData` currently computes chapters and stores
   them on the book; it should write them to `ChapterDao` instead.
6. **Move the reads**, one call site at a time, keeping the `asChapterList()` fallback (it is the
   no-embedded-chapters path fixed in [[cu-13]] and still needed).
7. **Retire `Audiobook.chapters`** only once every read is moved, as a separate `BookDatabase`
   migration. Sequenced last and separable: if anything is uncertain, stopping after step 6 leaves
   a working app with a redundant column, which is far better than a half-migrated schema.

### Risk note

This touches the same tables as [[cu-71]] and there is deliberately no
`fallbackToDestructiveMigration`, so a bad migration crashes rather than wiping progress. Every
migration gets a file-backed test verified by sabotage (an in-memory Room test provably cannot
catch a migration fault — see `RoomSchemaTest`'s KDoc). Steps 1–2 are the risky half; 3–6 are
mechanical; 7 is optional cleanup.

### Analysis file

[`archive/M4-chapter-management-refactor-plan.md`](../docs/analysis/archive/M4-chapter-management-refactor-plan.md)
is **not usable as written**: every section's lines are in reverse order (Problem Statement last,
Phase 6 before Phase 1, bullet lists inverted) and its code blocks are shredded. Its content is
also generic and partly stale — it hedges that "chapters [are] likely stored as part of tracks or
books" and proposes creating a `ChapterDao` that already exists. Superseded by the plan above;
archive it when this task closes rather than repairing it.

## Acceptance Criteria

- [x] `Chapter` has a composite primary key that cannot collide across books, with a migration to
      `ChapterDatabase` v3 and an exported schema
- [x] `bookId` is populated on both chapter paths (Plex and the per-track fallback)
- [x] Two books whose chapters share a server `id` both survive `insertAll` — covered by a test
      verified to bite
- [x] `ChapterDao` can fetch the chapters for one book
- [x] `ChapterDatabase` + `ChapterRepository` provided in Dagger and actually injected
- [x] Chapters are written to the table by the live fetch path
- [>] Playback + UI read chapters from the DB, with the `asChapterList()` fallback preserved →
      **[[cu-82]]** —
      **not done, see "Step 6" below**
- [x] Every new migration has a file-backed test in `RoomSchemaTest`, each verified to bite by
      deliberate sabotage
- [x] Verify loop green; coverage not regressed (12.62% → 13.05%)

## Implementation Notes

Steps 1–5 of the plan landed. Step 6 (moving the reads) and step 7 (retiring
`Audiobook.chapters`) did not — the reason matters and is below.

### The blocker that shaped the whole task: `Chapter.id` was unsafe as a primary key

`id` arrived from two namespaces — `PlexChapter.id` on the Plex path, the *track* id on the
per-track fallback — and Plex assigns chapter and track ratingKeys from one server-wide sequence.
With `id` as the primary key and `insertAll` using `OnConflictStrategy.REPLACE`, two books whose
chapters shared an id would silently evict each other. Harmless while chapters were serialized per
book; a data-loss bug the moment they share a table.

In the fixtures the ranges happen not to overlap (tracks 2001–2003, chapters 4001–4003) — the same
accidental correctness the missing-`parentKey` bug was made of, so it is not evidence.

Now keyed `(bookId, trackId, discNumber, index)`. `bookId` had to be populated first: neither
construction path set it, so every chapter carried `NO_AUDIOBOOK_FOUND_ID` and would have collided
on the new key.

`ChapterDatabase` → v3. The migration **drops** the table rather than copying it, safe only here:
nothing ever wrote to it, pre-existing rows would all collide anyway, and chapters are derived data
refetched per book. That argument does not extend to books or tracks.

### A second bug found on the way

Both `BookRepository.syncAudiobook` and `ChapterRepository.loadChapterData` fell back to
`listOf(track.asChapter(0L))` — a literal zero offset for **every** track. Chapter offsets are
absolute within the book, so a multi-file book where Plex returns no chapters had every chapter
starting at 0, and `getChapterAt` resolves the wrong chapter or none. Same class of bug as the one
[[cu-13]] fixed in `asChapterList`, in the path that runs when the server answers for *some*
tracks.

Fixed by extracting `assembleChapters` (`data/model/ChapterAssembly.kt`), which owns the running
offset, and pointing both repositories at it — so the duplication that let the two copies drift is
gone too. 7 tests, including the mixed case: a track the server answered for must still advance
the offset used by a later track it did not.

### Replacement, not merge

`syncAudiobook` calls `removeAllForBook(bookId)` before `insertAll`. A chapter list can *shrink* —
a re-tagged file, or a book dropping from server chapters to the fallback — and `REPLACE` only
overwrites rows whose key matches, so stale extras would otherwise survive. Scoped by `bookId` so
it cannot disturb another book; both properties are tested, and the scoping was verified by
inverting the `WHERE` clause.

### Step 6: why the reads did not move

Chapters are written **and** still stored on the book, deliberately, so reads could move one at a
time. On investigation that turned out to need more than a swap:

1. **`syncAudiobook` is the only writer and runs on demand**, per book, when its tracks load. So
   the table is empty for any book not re-synced on this version. A read site switched to the DAO
   would show *no chapters* for an existing library until each book happened to sync — a visible
   regression on upgrade. The correct read is DAO-first with a fall back to `Audiobook.chapters`,
   which means the fallback chain grows to three levels (table → book column → `asChapterList`).
2. **All four read sites are `DoubleLiveData` combinators over the book** (`CurrentlyPlayingViewModel`,
   `AudiobookDetailsViewModel`, `MainActivityViewModel`, plus `CurrentlyPlayingSingleton`). Making
   them DAO-backed means restructuring each ViewModel's reactive wiring, not changing an expression.

Stopping here leaves a working app with a populated chapter table and a redundant column, which is
exactly the safe midpoint the plan named ("if anything is uncertain, stopping after step 6 leaves a
working app with a redundant column"). Steps 6–7 are carved into a follow-up rather than rushed
against the same tables that hold listening progress.

### Analysis file

`M4-chapter-management-refactor-plan.md` was **unusable**: every section's lines in reverse order
(Problem Statement last, Phase 6 before Phase 1), code blocks shredded, and its premise stale —
it proposed creating a `ChapterDao` that already existed. Superseded by the plan in this task;
moved to `analysis/archive/` since it no longer reflects the code.

### Follow-ups

- Steps 6–7: move the reads to the DAO, then retire `Audiobook.chapters`. → new draft.
- **No live verification.** Every check is a unit test against Robolectric SQLite. Whether a real
  library's chapters land correctly, and whether the v2→v3 drop is truly harmless on a real device,
  belong in [[cu-73]].

---
id: cu-49
title: Refactor chapters into their own DB table
status: In Progress
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: [cu-13, cu-15]
priority: medium
milestone: m-1
---

## Description

M4: chapters are embedded in tracks/books, complicating queries. Design a chapter schema + ChapterDao + migration; playback + UI read chapters from the DB. Sequence with cu-13 (chapter correctness) and the neutral Chapter model (cu-13 builds it; this persists it).

Analysis: [`M4-chapter-management-refactor-plan.md`](../docs/analysis/M4-chapter-management-refactor-plan.md).

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

[`M4-chapter-management-refactor-plan.md`](../docs/analysis/M4-chapter-management-refactor-plan.md)
is **not usable as written**: every section's lines are in reverse order (Problem Statement last,
Phase 6 before Phase 1, bullet lists inverted) and its code blocks are shredded. Its content is
also generic and partly stale — it hedges that "chapters [are] likely stored as part of tracks or
books" and proposes creating a `ChapterDao` that already exists. Superseded by the plan above;
archive it when this task closes rather than repairing it.

## Acceptance Criteria

- [ ] `Chapter` has a composite primary key that cannot collide across books, with a migration to
      `ChapterDatabase` v3 and an exported schema
- [ ] `bookId` is populated on both chapter paths (Plex and the per-track fallback)
- [ ] Two books whose chapters share a server `id` both survive `insertAll` — the regression the
      old single-column key allowed, covered by a test
- [ ] `ChapterDao` can fetch the chapters for one book
- [ ] `ChapterDatabase` + `ChapterRepository` provided in Dagger and actually injected
- [ ] Chapters are written to the table by the live fetch path
- [ ] Playback + UI read chapters from the DB, with the `asChapterList()` fallback preserved
- [ ] Every new migration has a file-backed test in `RoomSchemaTest`, each verified to bite by
      deliberate sabotage
- [ ] Verify loop green; coverage not regressed

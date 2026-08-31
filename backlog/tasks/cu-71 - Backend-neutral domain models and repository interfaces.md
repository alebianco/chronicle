---
id: cu-71
title: Backend-neutral domain models and repository interfaces
status: Done
assignee: [claude]
created_date: '2026-08-31'
labels: [R1, architecture]
dependencies: [cu-15]
priority: high
milestone: m-1
---

## Description

Split out of [[cu-15]] on 2026-08-31. cu-15's first two criteria — backend-neutral
domain models and extracted repository interfaces — turned out to be a **Room schema
change**, while its other four items are mechanical coroutine/DI refactors. Bundling
them would put reversible plumbing and a potentially data-destructive migration in
one review, so they were separated.

### Scope

1. Replace Plex-shaped fields in the domain models with neutral ones:
   `ratingKey: Int` → `id: String`, `viewOffset: Long` → `progressMs: Long`, across
   `Audiobook` and `MediaItemTrack`.
2. Remove `Injector.` leaks from domain/model code (27 files reference `Injector.`;
   only those in the model/domain layer are in scope here).
3. Extract `BookRepository`/`TrackRepository` interfaces cleanly from their
   implementations, so the `MediaSource` seam ([[cu-15]]) has repository-side
   counterparts.

### Why this is the risky half

`BookDatabase` (v8) and `TrackDatabase` (v4) hold the user's listening progress.
Renaming and retyping a primary key (`ratingKey: Int` → `id: String`) is not a
column rename — SQLite requires a table rebuild with a data copy, and Room's
generated schema must match exactly or every subsequent migration test fails.

None of the four databases use `fallbackToDestructiveMigration`, deliberately: a bad
migration must crash rather than silently wipe progress (CLAUDE.md, Gotchas). That
makes correctness here a hard requirement, not a best effort.

Code churn is small — `ratingKey` appears in 6 files, `viewOffset` in 2 — so the
work is almost entirely migration design and testing.

### Sequencing

Must land **before [[cu-13]]** (chapter correctness on a neutral `Chapter` model),
which assumes the neutral domain exists. [[cu-49]] (chapters into their own table)
depends on both.

## Acceptance Criteria

- [x] `Audiobook` and `MediaItemTrack` expose neutral `id: String` and `progressMs`;
      no `ratingKey`/`viewOffset` in the domain layer
- [x] `BookDatabase` and `TrackDatabase` versions bumped with hand-written migrations
      in the same change
- [x] `RoomMigrationTest` extended with the new chains, and **verified to bite** by
      deliberately breaking a migration and observing the failure
- [x] Round-trip test: a book with saved progress survives the migration with its
      position intact (the failure mode that matters to the user)
- [>] `Injector.` removed from domain/model code — **not done, deferred**
- [>] `BookRepository`/`TrackRepository` interfaces extracted; `SourceManager` gains
      the ingestion API it needs — **not done, deferred**
- [x] Verify loop green; coverage not regressed

## Implementation Notes

### What landed

All four entities retyped to `id: String` (`Audiobook`, `MediaItemTrack`, `Chapter`,
`Collection`), with a migration per database generated from the exported schemas:
`BOOK_MIGRATION_8_9`, `MIGRATION_4_5`, `CHAPTER_MIGRATION_1_2`,
`COLLECTIONS_MIGRATION_1_2`. Scope grew beyond the two databases the task named —
`Chapter` and `Collection` carry foreign ids into books and tracks, so retyping only
two would have left the join columns mismatched.

Coverage 11.96% → 12.62%; tests 201 → 204.

### Decisions taken

- **`rebuildTableWithTextIds`** (`data/local/MigrationSupport.kt`) — SQLite cannot
  alter a column type or a primary key, so all four migrations are create-copy-drop-rename
  through one tested helper. The column list comes from the exported schema, which is
  the authority: **a column omitted there is dropped with no error at all.**
- **Sentinels keep their textual values** (`NO_AUDIOBOOK_FOUND_ID = "-22321"`,
  `TRACK_NOT_FOUND = "-23"`) so rows written before the retype still compare equal.
- **Fetch2's `Int` groupId is a one-way hash.** `downloadGroupId(bookId)` maps a String
  id into the `int` Fetch2 requires, but listeners hand back only that Int and the app
  needs the real id to update the DB. Solved by carrying the id in `Request.setExtras`
  and reading it back (`bookIdOrNull()`, `groupByBookId()`). Downloads enqueued by an
  older version have no extras and are **skipped rather than guessed** — a wrong guess
  marks the wrong book downloaded; a skip is recovered by the next cache scan.
- **`ACTIVE_TRACK` (`Long.MIN_VALUE + 22233L`) deleted.** The Bundle extra
  `KEY_SEEK_TO_TRACK_WITH_ID` is now a String, and *absence of the key* carries what the
  magic sentinel meant ("resume the most recently listened track").
- **`childIds` column deliberately untouched.** Its converter already serialized a JSON
  array of strings; only the Kotlin type moved `List<Long>` → `List<String>`, which also
  removes a `toLong()` that would throw on a non-numeric child id.
- **Stale WorkManager data tolerated.** `requireId` accepts the pre-upgrade `Int` form,
  because WorkManager persists pending requests across an upgrade and an exception out of
  `doWork` is an uncaught crash.

### Bugs found and fixed along the way

Pre-existing, unrelated to the retype but uncovered by touching these paths:

1. **An inverted, vacuous `check` in `AudiobookMediaSessionCallback`** — asserted that an
   explicitly requested track was **not** in the track list, and short-circuited to true
   for `ACTIVE_TRACK`. Never fired only because the `checkNotNull` below did the real work.
   Deleted rather than ported.
2. **`activeDownloads` posted stale LiveData** — `postValue(internalSet)` ran *before*
   mutating and posted the mutable set itself, so observers saw the pre-change contents,
   and LiveData's reference comparison could coalesce the update away entirely, leaving
   the download indicator stale. Now posts an immutable snapshot after the change.
3. **`startTimeOffset = ACTIVE_TRACK` in `CurrentlyPlayingViewModel`** — a *track id*
   sentinel passed as a millisecond time offset.
4. **Two DAO methods bound numeric parameters against a now-TEXT `id`**
   (`ChapterDao.updateCachedStatus`, `CollectionsDao.removeAll`). SQLite compares across
   storage classes and matches **no row, silently, with no error**. Both were uncalled, so
   this was a latent trap for the next caller rather than a live bug.
5. **`getItemId()` in two adapters called `id.toLong()`** — throws `NumberFormatException`
   on exactly the non-numeric ids this task exists to enable. Now hashes.

### The testing gap this exposed (the important part)

`verify.sh` was green while a migration would have crashed the app on launch. An earlier
commit in this task shipped `BookDatabase` at v9 with TEXT ids while the entity still
declared `Int`; Room validates entity against schema **on open**, and nothing in the suite
opened a database through Room. That commit was reverted and `RoomSchemaTest` added.

Two kinds of check are needed, and the first alone is worthless here:

- **In-memory open** proves entities are internally consistent — but an in-memory database
  is created fresh at the current version and *never migrated*, so it cannot catch a
  version/migration mismatch. Confirmed by bumping a version with no migration: it passed.
- **File-backed open after migration** is what reproduces the crash.

This change adds file-backed migration tests for the three remaining databases, each
**verified to bite** by deliberate sabotage:

| Sabotage | Caught by | Failure |
|---|---|---|
| `MIGRATION_4_5` omits `parentKey` from the copy | track test | `NOT NULL constraint failed: MediaItemTrack_new.parentKey` |
| `CHAPTER_MIGRATION_1_2` omits `title` | chapter test | `NOT NULL constraint failed: Chapter_new.title` |
| `COLLECTIONS_MIGRATION_1_2` wipes `childIds` | collections test | `expected:<["1001","1002"]> but was:<[]>` |

The first sabotage is worth dwelling on: **it passed all 201 tests before these were
added.** Every track silently lost its book link — a library of books with no tracks —
with no error anywhere.

A related trap: three assertions compared a String id to an Int literal and still
*compiled*, because `assertEquals(Object, Object)` boxes both. They failed at runtime.
A green compile is not a green build.

### Follow-ups (deferred, not done)

- `Injector.` still present in `Collection.kt` and `MediaItemTrack.kt` (3 sites) — needs
  the DI seam, not the retype. → new draft.
- `BookRepository`/`TrackRepository` interface extraction and the `SourceManager`
  ingestion API — untouched. → new draft.
- **No live-server verification.** Every migration is tested against Robolectric SQLite
  with synthetic rows; none has run against a real Plex library or a real upgrade from an
  installed build. This belongs in [[cu-73]].
- Seeks over the 206/range path remain unit-tested only (pre-existing, cu-64).

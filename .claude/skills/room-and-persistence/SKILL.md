---
name: room-and-persistence
description: Use when touching Room databases, entities, DAOs, migrations, exported schemas, source scoping (SourceId), bookmarks, or the settings backup/export format. Covers the five separate databases, migration testing traps, and the local-column merge rule.
---

# Room and persistence

Five separate databases, each with its own version and migration list — a schema change means
finding the right one:

| Database | Version |
|---|---|
| `BookDatabase` | 14 |
| `TrackDatabase` | 7 |
| `ChapterDatabase` | 3 |
| `CollectionsDatabase` | 3 |
| `BookmarkDatabase` | 1 |

None use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never
silently wipe listening progress.

## Migrations

**A migration is only tested if a *file* is opened through Room.** Room validates entity against
schema **on open**; an in-memory database is created fresh at the current version and never
migrated. `verify.sh` was once green while a committed migration would have crashed on launch — a
migration that dropped every track's `parentKey`, orphaning every book from its tracks, passed all
201 other tests.

- `RoomSchemaTest` is the load-bearing check: in-memory opens for entity consistency, **plus** a
  file created at the old schema and opened at the current one.
- `RoomMigrationTest` drives the historical chains through real SQLite via Robolectric (Room's
  `MigrationTestHelper` is instrumented-only). Add a case for any new migration.
- Verify every migration by **deliberate sabotage** — a check that cannot fail proves nothing.

**An exported schema for a released version must never change.** Room rewrites
`<version>.json` from the current entities, and when a version bump and an entity change land in
the same build it overwrites the **older** file — leaving `10.json` containing v11's shape. Those
files are the authority a migration's column list is written from (`BOOK_MIGRATION_8_9` says so).
`RoomSchemaTest` checks each file's name against the `version` inside it; comparing column counts
between neighbours does **not** work, because an overwritten file is an exact copy of the newer one
and compares equal.

A migration that changes only *data* (not shape) still needs a case — v11→v12 rescaled series
indices while the exported schemas differed only by version and shared an `identityHash`, since
Room hashes the schema, not the version.

## Entity ids are all `String` (decision-11)

So a non-numeric backend can be represented. Two traps:

- **A DAO parameter bound against an id column must be `String`.** SQLite compares across storage
  classes, so a numeric bind matches *no row, silently, with no error* — two dead DAO methods had
  exactly this.
- **A numeric-looking id must never be parsed.** `id.toLong()` throws on the very ids the retype
  exists to allow (it did, in two RecyclerView `getItemId` overrides; they hash now).

## The local-column merge rule

**A local-only column must be named in *both* arms of `Audiobook.merge`.** A library refresh merges
a network copy without loading tracks, and a field the server knows nothing about is always the
default on that copy — so an arm that omits it wipes the local value on every refresh.

`merge` has two branches and only one runs for a given pair, so a fix applied to one arm and missed
in the other looks correct in a test that happens to take the fixed path. `PerBookSpeedTest`
exercises both and was verified by sabotaging one arm.

`merge` needs a **third** rule for fields the server may or may not carry (narrator, series) — the
network value when it has one, the local value when it does not. Preferring the network blanks a
narrator on every refresh; preferring the local one makes a re-tagged book uncorrectable.

## Source scoping (decision-21)

`SourceId` (`data/model/SourceId.kt`) is a `String` value class holding `"plex:<clientIdentifier>"`.
`Audiobook`, `Collection` and `MediaItemTrack` all carry one.

- **The repository is the seam.** All 78 DAO call sites live in four repositories; nothing in
  `features/`, `application/` or the player reaches a DAO. Each repository resolves
  `currentSourceId` from the connected server.
- **A read that returns rows without naming one must filter by source** — `ScopedQueryTest` fails
  the build otherwise. A query keyed on a primary key (`WHERE id = :bookId`) or on one book
  (`parentKey = :`) is exempt: the id is already unique, and a filter there masks bugs rather than
  preventing them. Chapters and bookmarks carry no `source` at all for that reason. The guard
  exists because an unscoped read fails *silently* — the symptom is a union that only appears with
  two servers configured.
- **`SourceId.UNKNOWN` is inert, never a wildcard.** `planIngestion` writes nothing when the scope
  is unresolved (mid-login, or after `PlexConfig.clear()`): stamping rows with it would put them
  beyond both the removal rule and every scoped read — a catalogue that grows and can never be
  pruned. Stub `server`, not just `library`, or a test silently stops exercising ingestion.
- **Migrating rows land on `SourceId.LEGACY_PLEX`**, and `adoptLegacyRows` claims them on the next
  launch. A `SupportSQLiteDatabase` cannot know which server the app is configured for, so v12→v13
  and v6→v7 mark every existing row and `ChronicleApplication` adopts them — launched, not awaited,
  so the library briefly reads empty on the first launch after upgrading. Adoption matches the
  marker **only**, never a resolved source, or a second server could take over the first's library.
  A plain `CAST(source AS TEXT)` would have produced the string `"0"` — schema-valid, right row
  count, every book permanently invisible.

**Not fixed:** `Audiobook.id` is still the sole primary key, so two servers sharing a Plex rating
key collide on insert. decision-21 rejected a composite key deliberately (the lesson from the
all-`String`-ids retype). Scoping removes the *union*, which is what a user sees. Downloads stay at
`<cachedMediaDir>/<trackId>.<ext>`: a per-source path buys nothing while one id means one row means
one filename, and it would touch four file paths whose failure mode is deleted audio.

**Note:** `ScopedQueryTest` covers **reads only**. An unresolved source on a *write* looks exactly
like "no data" and nothing catches it.

## Ingestion

**A refresh may only delete books belonging to the source doing the refreshing.** `planIngestion`
scopes removal by `Audiobook.source`; the Plex-only path deleted every local row absent from its
fetch — safe with one source, a **library-wipe with two**, taking listening progress no server
holds a copy of.

**An empty fetch removes nothing**: a source answering `Ok(emptyList())` is a failed refresh, not
an emptied library, and a *failed* fetch never reaches ingestion at all. All three are
sabotage-verified.

`refreshData` and `refreshDataPaginated` share that one path — the tail was written out twice
before, and adding tag seeding had to touch both copies.

## Chapters

**Chapters live in `ChapterDatabase` and nowhere else.** The legacy `Audiobook.chapters` column is
**gone** as of v14 — do not reintroduce a serialized copy on the book.

- Every read goes through `resolveChapters` / `resolveChaptersFromCache`
  (`data/model/ChapterAssembly.kt`): table → `asChapterList()`.
- The fallback is permanent: a server reporting no chapters has nothing to fall back *to*,
  so one chapter per track is derived instead.
- A book with no rows repairs itself — `syncAudiobook` refetches from
  `/library/metadata/{id}?includeChapters=1` whenever the book is opened.
- **Read `currentlyPlaying.chapters`, never a chapter list off the book.** Ten call sites in
  `PlayerExt` and `CurrentlyPlayingViewModel` were resolving `indexOf` to `-1` against the empty
  column, so chapter skip silently did nothing.
- **Rows are passed *into* `CurrentlyPlayingSingleton.update`, never read inside it**: it runs once
  a second from `ProgressUpdater`, so a DAO there is a blocking read per tick. A test pins
  that a tick without rows cannot downgrade an already-resolved list.
- The backfill and `ChapterListConverter` went with the dropped column. Verified on the
  tablet: v14, 196 books, 6 positions and 138 series indices intact, a 107-chapter book playing.
- **A chapter spanning a track boundary appears in both *responses*, but must not appear twice in
  the assembled list** — `assembleChapters` de-duplicates. It used to concatenate the
  per-track lists, so the fixture book stored ten rows for eight chapters, read "Chapter 3: A Short
  Rest" twice and said "Ch 8 of 10", because that readout's `m` is a size. The chapter is kept for
  the track it **starts** in — the rule `trackId` already follows and the frame
  `bookStartTimeOffset` is measured in — and identity is the id **plus** both book offsets, since
  `id` alone is not unique within a book and dropping on it would take a real chapter with
  it. Invisible for as long as it existed: the old adapter rendered the legacy `Audiobook.chapters`
  column, empty since that column was dropped, so no duplicate could reach a screen until the list became Compose.
- **`ChapterRepository.loadChapterData` does not call `removeAllForBook` before inserting**, unlike
  `BookRepository.syncAudiobook` — so a shrinking chapter list leaves stale rows on that path.
- Chapter offsets are **absolute within the book**, not per-track. The frame is a
  type, so that mistake no longer compiles — see the `playback-and-player` skill.

## Bookmarks are a separate database on purpose

`BookmarkDatabase` is keyed by `bookId` and lives outside `BookDatabase` **so the sync path cannot
reach it**: `refreshData` calls `bookDao.removeAll` for books the server no longer lists, so a
bookmark stored alongside a book would vanish when a Plex rescan briefly drops it — permanently,
since no server holds a copy of a note the user wrote.

`BookmarkSurvivesSyncTest` runs the real refresh over real databases and asserts the note outlives
the catalogue row; moving bookmarks into `BookDatabase` breaks it, which is the point. A library
*switch* (`clear()`) leaves them alone too — the user may switch back.

## The backup file

`SettingsBackup.settings` is a `Map<String, String>` of *preference keys*; bookmarks are a
top-level `bookmarks` array, because forcing per-book rows through that map means JSON encoded
inside a string value, and the file is meant to be openable in an editor (D12 rule 7).

`BACKUP_SCHEMA_VERSION` is **2**: adding a settings key needs no bump (unknown keys are ignored —
that tolerance is `ChronicleJson`'s `ignoreUnknownKeys`, a **setting**, not the parser's default,
and `encodeDefaults` is what keeps `version` in a file whose version sits at its default),
but the format growing a field does — otherwise `importSettingsOrNull`'s refusal of a newer version
can never distinguish "a v1 file with no bookmarks" from "a v2 file whose bookmarks were lost".

Import is **additive and idempotent**, keyed on the id in the file — never a replace-all, which
would delete notes made since the export.

Any settings export MUST use the `BACKUP_SETTING_KEYS` allowlist and **never enumerate
`sharedPreferences.all`**. The allowlist gates **keys, not values**: an imported string is written
straight to prefs, so a value with a closed set of valid options needs validating on the way in.

## Testing the data layer

Test against **real in-memory Room databases** (nine suites do) rather than mocked DAOs — and
`RoomSchemaTest` against a real *file*, since an in-memory database is never migrated.

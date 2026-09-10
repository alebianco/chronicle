---
id: cu-127
title: Scope stored data and downloads by source instance
status: In Review
assignee:
  - '@claude'
created_date: '2026-09-03'
labels:
  - R2
  - architecture
  - data
  - multi-backend
milestone: m-2
dependencies: []
priority: medium
ordinal: 84000
---

## Decision taken, 2026-09-05 — [[decision-21]]

The owner chose **scoping by source instance** (one Plex *server*), not by library and not by backend
type. Option 1 of the three below, with option 3 explicitly rejected: a book can move between
libraries on one server while keeping its rating key, so a library scope would invent a boundary
Plex does not have. **"Different library" stays a refresh concern**, already handled by [[cu-126]].

Read [[decision-21]] before starting — it carries two traps this task's analysis did not have:

1. **`ServerModel.serverId` is a `String` while `Audiobook.source` is a `Long`.** A per-instance id
   cannot simply be the Plex server id. The ADR prefers moving to a `String` source id (consistent
   with [[cu-71]]), which makes this wider than "add a column".
2. **A download path migration must degrade to "not cached", never "deleted"** — cu-85's failure
   mode, plus cu-153's finding that a partial and a finished file are indistinguishable by name.

## Promoted from a draft, 2026-09-05 — with one correction

Claims re-verified against the tree. Two hold; one has moved:

- ✅ `Audiobook.source` is written as a constant at every site and no `BookDao` query filters on it.
- ✅ `MediaItemTrack` still has no source field.
- ⚠️ **"no DAO filters on it" is no longer the whole picture.** [[cu-80]] added
  `planIngestion` (`data/sources/IngestionPlan.kt`), which scopes *removal* by
  `Audiobook.source` — a refresh may only delete rows belonging to the source doing the refreshing.
  So the column has one real reader now, and it is the dangerous one. That does not change this
  task's question, but it does mean the field is no longer inert and a design that repurposes it
  has an existing behaviour to preserve.

~~Still needs the owner~~ — **settled 2026-09-05**, see the decision block at the top.

## Description

Owner proposal during the cu-73 live pass (session 4): *"should the db isolate data by library
maybe? so selecting a different one does not merge data? … same for the downloaded files, isolate
them by library in their path?"*

Raised after [[cu-126]] showed that choosing a different library at the login picker leaves the
previous library's rows in Room and its downloads on disk, with no prompt. The current answer to
"which library does this row belong to?" is **implicit** — whatever library was selected when it
was written.

This is a design decision, so it probably wants an ADR in `backlog/decisions/` rather than being
settled inside a bugfix.

### Current state, verified

**The scoping column already exists and is never populated.** `Audiobook` carries:

```kotlin
/** Unique long representing a [MediaSource] in [SourceManager] */
val source: Long,
```

but every write site sets it to the constant `PlexMediaSource.MEDIA_SOURCE_ID_PLEX` (or
`MediaSource.NO_SOURCE_FOUND`), and **no DAO filters on it**. `Collection` has the same field with
the same constant. **`MediaItemTrack` has no such field at all** — only `id` and `parentKey`.

Downloads are equally flat: `getCachedFileName()` returns `"$id.${extension}"` and files land
directly in `cachedMediaDir`, so the path encodes nothing about origin.

So the dimension was anticipated in the schema and never made load-bearing — consistent with the
`MediaSource`/`SourceManager` seam being "declared but not yet load-bearing" per CLAUDE.md.

### How much does it matter today, honestly

For **one Plex server, several libraries: not much.** Plex rating keys are server-global — the
owner's 196 books span ids 150309–155718 across the whole server, so two libraries on the same
server cannot produce colliding ids. Switching libraries yields *extra* rows, not *wrong* ones, and
`refreshData` prunes them on the next successful sync (`removedFromNetwork` → `bookDao.removeAll`).

It matters for:

1. **A different Plex server.** Rating keys are unique per server, not globally. Two servers can
   both have a book `151444`, and today they would occupy the same primary key and the same
   download filename.
2. **[[decision-11]] multi-backend.** ABS, local files and WebDAV mint their own ids with no
   coordination. `source` exists precisely for this, and until it is populated the seam cannot be
   turned on safely.
3. **Predictability now.** Even where it self-heals, the window between switching and the next
   refresh shows a union of two libraries, and a stale download is deleted silently later.

So: low urgency for today's single-server case, **blocking** for decision-11, and the cheap moment
to do it is before the ABS adapter rather than after.

### Options

1. **Populate what exists.** Make `source` real (per *backend instance*, not per backend type), add
   the equivalent to `MediaItemTrack`, and filter every DAO read by it. Downloads become
   `<cachedMediaDir>/<sourceId>/<trackId>.<ext>`.
   *Cost:* migrations on four DBs, a filter on every query, and a one-time move of existing files.
2. **Composite ids.** Store `"<sourceId>:<rawId>"` as the primary key. Avoids per-query filters,
   but every id parse/format site becomes load-bearing — and cu-71 already recorded pain from ids
   being over-interpreted. Probably worse.
3. **Scope by library too, not only source.** Strictly more isolation, but a book genuinely can
   move between libraries on the same server, and rating keys survive that. Scoping to *source*
   (server) matches the id-uniqueness boundary; scoping to *library* would invent a boundary Plex
   does not have. **Recommend source-scoping, and treat "different library" as a refresh concern
   rather than an isolation one.**

Option 1, scoped per source, looks right — it makes an existing field honest instead of adding a
concept, and it lines up with where decision-11 is going.

### Trap to avoid

`MediaItemTrack.getCachedFileName()` currently produces `<id>.<ext>`, and `cachedMediaDir` is user
-relocatable (Settings → sync location, with a `MoveSyncLocationWorker`). Any path change must be
migrated for files already on disk, and must not repeat cu-85's failure mode where an unreadable
directory silently un-cached whole libraries. A partial migration that leaves files at the old path
must degrade to "not cached", never to "deleted".

## Implementation Notes

Six commits, each with the verify gate green. The full 6-stage `./verify.sh` passes; coverage rose
**40.34% → 40.97%** aggregate, with `data/local`, `data/model`, `data/sources`, `data/sources/local`
and `application` all ratcheted up.

### What the tree turned out to look like

Two findings reshaped the plan, one narrowing and one widening.

**Narrowing: the DAOs do not leak.** All 78 DAO call sites live in exactly four repositories.
Nothing in `features/`, `application/` or the player touches one — the only other references are
Dagger provision methods and two doc comments. So the scoping is enforced at the repository, which
already passes `prefsRepo.offlineMode` into every read; `currentSourceId` follows the same path.
The feared "thread a parameter through the whole app" never materialised.

**Widening: tracks needed the field after all.** A track is nearly always reached through its
book's `parentKey`, which is scoped — but **four** `TrackDao` queries filter on no book at all
(every track, every cached track, the title search), and `getTrack(id)` is ambiguous across two
servers. Those are exactly the reads that would return a union.

### Decisions taken

- **`String`, not `Long`** — as decision-21 preferred. A Plex `clientIdentifier` is ~40 characters,
  so a numeric id would be a hash or a locally-assigned number mapped from it: the second identity
  cu-71 removed.
- **Primary-key reads are deliberately unscoped.** `WHERE id = :bookId` is already unique; a filter
  there masks bugs rather than preventing them.
- **`SourceId.UNKNOWN` is inert.** `planIngestion` writes nothing for an unresolved scope, rather
  than stamping rows with a key no later refresh matches.
- **Point 4 of decision-21 — the per-source download path — is deferred, with a tripwire.** The
  ADR is amended in place with the reasoning: the filename collision is unreachable while
  `MediaItemTrack.id` is the sole primary key, and the change would touch four file paths whose
  failure mode is deleted audio. A test fails the moment that stops being true.

### Bugs and traps found on the way

1. **Room silently overwrote three released schemas** (`BookDatabase/12.json`,
   `CollectionsDatabase/2.json`, `TrackDatabase/6.json`) — the cu-24 trap, hit three times in one
   task because three databases were versioned. Caught by `git status` each time and reverted.
   Worth checking after *every* entity change that bumps a version.
2. **`MediaItemTrack.merge` named `source` in neither arm**, so a refresh would have blanked the
   scope of every track — cu-20's rule in a new field. Each arm is separately sabotage-verified.
3. **Three test suites stubbed `PlexPrefsRepo` without a `server`** and silently stopped ingesting
   once the unresolved-scope guard landed. That they broke is what proves they were exercising the
   path.
4. **`uncacheAll` and `uncacheAllInLibrary` disagreed on scope** after `getCachedTracks` became
   scoped: deletion narrowed to one server while the flag clear stayed global, which would have
   reported another server's downloads as absent while they sat on disk.
5. **I referenced `BookRepository.adoptLegacyRows` in two KDocs before writing it.** Without it an
   upgrading user opens the app to an empty library. Caught by re-reading the diff, which is what
   the self-review pass is for.

### Sabotage verification

Every guard was verified by deliberate sabotage and restored with `--rerun-tasks`:
the unresolved-scope guard; the naive `CAST` in the book migration; a dropped column in the
collections rebuild; the track migration's default; each arm of `MediaItemTrack.merge`
independently; a removed read filter; a newly-added unscoped query (caught by `ScopedQueryTest`);
and adoption widened past the legacy marker.

### Follow-ups

- The per-source download path, if `Audiobook.id`/`MediaItemTrack.id` ever stop being the sole
  primary keys. The tripwire test names the condition.
- **Live verification is not done** — see the unchecked criteria below.

## Acceptance Criteria

- [x] Decision recorded — [[decision-21]]: scope by **source instance**, not library
- [x] `source` is populated with a real per-instance id, not a per-type constant
- [x] Track-level entities carry the same scoping as book-level ones
- [x] Every DAO read is scoped, so two sources cannot merge into one list — `SourceIsolationTest`
      against real databases, plus `ScopedQueryTest` as a build gate for the next query
- [~] ~~Downloads are stored under a per-source path~~ — **retired with reasoning**, not unmet.
      The collision is unreachable while the track id is the sole primary key, so the change would
      buy nothing while touching four paths whose failure mode is deleted audio. decision-21 is
      amended in place and a test fails the moment the premise changes.
- [x] Room migrations + `RoomSchemaTest` cases for all affected DBs — three needed one
      (Book v12→13, Collections v2→3, Track v6→7); chapters and bookmarks are keyed by `bookId`
      and carry no scope. All sabotage-verified.
- [ ] **Switching library or server never shows a union of two catalogues, even before a refresh**
      — needs the owner on a device. The unit suite proves the reads are scoped; it cannot prove
      what the screen shows during the adoption window on the first launch after upgrading.

## Related

- [[cu-73]] — raised during the live pass
- [[cu-126]] — the unguarded library switch that prompted this
- [[decision-11]] — multi-backend; this is a prerequisite for the ABS adapter
- [[cu-15]] / [[cu-33]] — the MediaSource seam these fields were added for
- [[cu-71]] — String ids; argues against re-encoding meaning into the id itself
- [[cu-85]] — the "a cache scan that cannot read its directory must change nothing" rule

## What needs the owner's eye

Two things, both on-device and neither provable by a machine:

1. **The first launch after upgrading.** `adoptLegacyRows` is launched rather than awaited, so
   there is a window where the library reads empty and then fills — the same trade cu-158's chapter
   backfill makes. How long that window is on a real 196-book library, and whether it reads as a
   loading state or as a bug, is a judgement call. If it is ugly, the fix is to await it behind the
   existing splash rather than to change the scoping.
2. **A real library switch.** The unit suite proves two sources cannot merge in a *query*. Whether
   the screens behave — home shelves, downloads, collections, search — during and after a switch on
   the household server is the check that was not performed.

Worth knowing while testing: this is the first change to write a **new schema version to three
databases at once**, so a downgrade is not possible without clearing data. `plex-session.sh backup`
before testing.

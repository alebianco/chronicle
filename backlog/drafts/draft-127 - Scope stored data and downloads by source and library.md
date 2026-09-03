---
id: DRAFT-127
title: Scope stored data and downloads by source and library
status: Draft
assignee: []
created_date: '2026-09-03'
labels: [R2, architecture, data, multi-backend]
dependencies: []
priority: medium
milestone: m-2
---

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

## Acceptance Criteria

- [ ] Decision recorded — ADR in `backlog/decisions/`, since this constrains [[decision-11]]
- [ ] `source` is populated with a real per-instance id, not a per-type constant
- [ ] Track-level entities carry the same scoping as book-level ones
- [ ] Every DAO read is scoped, so two sources cannot merge into one list
- [ ] Downloads are stored under a per-source path, with a migration for existing files that
      cannot delete or silently orphan them
- [ ] Room migrations + `RoomSchemaTest` cases for all affected DBs (four of them)
- [ ] Switching library or server never shows a union of two catalogues, even before a refresh

## Related

- [[cu-73]] — raised during the live pass
- [[cu-126]] — the unguarded library switch that prompted this
- [[decision-11]] — multi-backend; this is a prerequisite for the ABS adapter
- [[cu-15]] / [[cu-33]] — the MediaSource seam these fields were added for
- [[cu-71]] — String ids; argues against re-encoding meaning into the id itself
- [[cu-85]] — the "a cache scan that cannot read its directory must change nothing" rule

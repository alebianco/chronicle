---
id: decision-21
title: Stored data and downloads are scoped by source instance, not by library
type: adr
status: accepted
created_date: '2026-09-05'
---

## Context

`Audiobook.source` and `Collection.source` exist in the schema and are **written as a constant at
every site** (`PlexMediaSource.MEDIA_SOURCE_ID_PLEX`); `MediaItemTrack` has no such field at all;
and **no DAO read filters on it** — verified 2026-09-05. The one real reader is cu-80's
`planIngestion`, which scopes *removal* by source so a refresh cannot delete another source's rows.
So the dimension was anticipated in the schema and never made load-bearing.

Downloads are equally flat: `getCachedFileName()` returns `"<id>.<ext>"` into a single
`cachedMediaDir`, so the path encodes nothing about origin.

The owner raised this during the cu-73 live pass: *"should the db isolate data by library maybe? so
selecting a different one does not merge data? … same for the downloaded files, isolate them by
library in their path?"*

**Honest urgency.** For one Plex server with several libraries this barely matters: Plex rating keys
are server-global (the household's 196 books span 150309–155718 across the whole server), so two
libraries cannot collide, and a switch yields *extra* rows that the next refresh prunes. It matters
for a **second Plex server** — rating keys are unique per server, not globally, so two servers can
both hold a book `151444` occupying the same primary key and the same download filename — and it
**blocks [[decision-11]]**, since ABS, local files and WebDAV mint their own ids with no
coordination.

## Decision

**The scoping key is the source instance — a specific backend *installation*, i.e. one Plex server —
not the library, and not the backend *type*.**

1. `source` is populated with a real per-instance id rather than a per-type constant. Two Plex
   servers are two sources; two libraries on one server are **not**.
2. `MediaItemTrack` gains the same field, so track-level rows carry the scoping their books do.
3. Every DAO read is scoped by it, so two sources cannot merge into one list.
4. Downloads move to `<cachedMediaDir>/<sourceId>/<trackId>.<ext>`.

**Why not scope by library.** A book can genuinely move between libraries on the same server while
keeping its rating key, so a library-scoped row would churn on a move that changed nothing.
Scoping to the *source* matches the boundary at which ids are actually unique, which is the only
boundary that prevents a collision. **"Different library" is therefore a refresh concern, not an
isolation one** — and cu-126 already handles it by clearing the previous library's catalogue on a
genuine switch.

**Why not composite ids** (`"<sourceId>:<rawId>"` as the primary key). It avoids per-query filters
but makes every id parse/format site load-bearing, and [[cu-71]] already recorded the cost of ids
carrying meaning: `id.toLong()` threw on the very ids the String retype existed to allow. Keeping
the scope in a *column* leaves ids opaque.

### Two traps for the implementer

**The id types do not currently agree.** `ServerModel.serverId` is a **`String`**; `Audiobook.source`
is a **`Long`**. A per-instance id therefore cannot simply be the Plex server id, and the mismatch
must be resolved deliberately — either a stable `String` source id (consistent with [[cu-71]]'s
"all four entity ids are String", and the direction decision-11 points) or a locally-assigned `Long`
mapped from the server id. **Prefer the `String`**, since a locally-assigned number is a second
identity to keep in sync and would repeat the problem the retype removed. Note this makes the
migration wider than "add a column".

**A download path change must never delete or orphan.** `cachedMediaDir` is user-relocatable
(Settings → sync location, `MoveSyncLocationWorker`), and cu-153 established that Fetch2 downloads
**in place** with no `.part` suffix, so a partial and a finished file are indistinguishable by name.
A partial path migration must degrade to *"not cached"* — never to *"deleted"* — and must not repeat
cu-85's failure, where an unreadable directory silently un-cached whole libraries. The prune in
cu-81 only ever scans the *active* directory, so a file left at the old path becomes invisible to
it.

## Consequences

**Good.** An existing field becomes honest instead of a new concept being added. The ABS adapter
(cu-33.1) stops being blocked on an unsafe seam, and a second Plex server becomes representable
rather than corrupting. cu-80's removal guard gains a real key instead of a constant that makes its
scoping vacuous.

**Costs, accepted.**

- **Migrations across four databases** (`BookDatabase` v12, `TrackDatabase` v6,
  `CollectionsDatabase` v2, and whichever others gain the field), each needing a `RoomSchemaTest`
  case that opens a **file** at the old schema — an in-memory test cannot catch a migration
  disagreeing with its entity.
- **A filter on every read**, which is a broad diff and easy to miss one of. A missed filter fails
  *silently* by showing a union, exactly the symptom this removes, so the acceptance criterion is
  "two sources cannot merge", tested — not "filters were added".
- **A one-time file move** for existing downloads, with the degradation rule above.
- **Sequencing.** Doing this before cu-33.1 is deliberate: retrofitting a scope onto two live
  backends is strictly harder than onto one.

**Not changed.** Library switching keeps cu-126's behaviour — clear the previous catalogue on a
genuine change — and [[cu-130]] adds the download prompt to that same path.

## References

- [[cu-127]] — the implementing task
- [[decision-11]] — multi-backend; this is a prerequisite for the ABS adapter
- [[cu-71]] — String ids, and the argument against re-encoding meaning into an id
- [[cu-80]] — source-scoped removal, today's only real reader of the column
- [[cu-85]], [[cu-81]], [[cu-153]] — the cache rules a path migration must not violate
- [[cu-126]], [[cu-130]] — the library-switch half, deliberately left as a refresh concern

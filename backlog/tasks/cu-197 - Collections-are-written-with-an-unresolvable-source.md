---
id: cu-197
title: Collections are written with an unresolvable source, so the tab never appears
status: Done
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - trust
dependencies: []
priority: high
milestone: m-2
---

## Description

Found on the tablet during cu-187's device verification, 2026-09-06. **The Collections tab is dead
for every user, on both the real server and the mock fixture** — and the cause is not that the
library has no collections, which is what CLAUDE.md and cu-187 both assumed.

Collections are **scoped on read but never stamped on write**:

- `Collection.from(dir)` (`data/model/Collection.kt:29`) hardcodes `source = SourceId.UNKNOWN`.
- `CollectionsRepository.refreshCollectionsPaginated` passes those straight to
  `collectionsDao.insertAll` without resolving a source.
- `hasCollections()` and `getAllCollections()` both filter by `currentSourceId`
  (`CollectionsRepository.kt:38-43`), per cu-127's rule that a read returning rows must be scoped.

So every stored row is invisible to every read of it. `MainActivity:264` hides the nav item on
`hasCollections`, so the tab never appears and the screen is unreachable.

**Measured on the household tablet** (ANTARES, real session):

```
sqlite3 databases/collections_db "SELECT source, count(*) FROM Collection GROUP BY source;"
|4
```

Four real collections — Darkover, Fiction, Non Fiction, Unmatched — all with an **empty** source.
A pull-to-refresh does not repair them: the write path stamps `UNKNOWN` again.

## Why this matters beyond one tab

`SourceId.UNKNOWN` is documented as **inert, never a wildcard** — CLAUDE.md's own words, because
rows stamped with it sit "beyond both the removal rule and every scoped read — a catalogue that
grows and can never be pruned." That is exactly what has happened here, and it is the failure mode
decision-21 predicted: an unscoped read fails *silently*, and the only symptom is a feature that
quietly does nothing.

cu-127 converted `Audiobook` and `MediaItemTrack` and added `ScopedQueryTest` to catch unscoped
*reads*. Nothing catches an unscoped **write**, which is why this survived.

## The thing to get right

- **Stamp at ingestion, like the books do.** `Audiobook` and `MediaItemTrack` resolve
  `currentSourceId` in the repository; collections should take the same route rather than
  inventing one.
- **The existing rows need adopting, not deleting.** `adoptLegacyRows` already exists for the
  v12→v13 / v6→v7 migrations and claims `SourceId.LEGACY_PLEX` rows on next launch. Empty-string
  rows are a *third* case — neither legacy-marked nor correctly scoped — so decide deliberately
  whether to migrate them to `LEGACY_PLEX` and let adoption handle it, or stamp them directly.
  **Do not `clear()` and re-fetch**: a collection's `childIds` are assembled with one request per
  collection, and a user with many collections pays for it.
- **Add the missing guard.** `ScopedQueryTest` pins reads; the equivalent for writes is what would
  have caught this. Worth pinning that `Collection.from` cannot produce `UNKNOWN` in a path that
  reaches `insertAll`.
- Check whether `Bookmark` and `Chapter` have the same shape. Both are keyed by `bookId` and carry
  no `source` by design (CLAUDE.md says so), so they are probably fine — but confirm rather than
  assume.

## Implementation Notes

**The fix is two halves, and both were needed.**

1. **Stamp at ingestion.** `CollectionsRepository.refreshCollectionsPaginated` now resolves
   `currentSourceId` and writes `it.copy(childIds = childIds, source = scope)`, mirroring what
   `planIngestion` does for books. It also **refuses to write at all** when the scope is
   unresolved — the same rule, for the same reason: filing rows under a key no later refresh can
   match is worse than not writing them.
2. **Adopt what is already stored.** `adoptUnscopedRows` claims both `SourceId.LEGACY_PLEX` (what
   the v2→v3 migration correctly stamped) and `SourceId.UNKNOWN` (what the broken write path then
   overwrote it with). Wired into `ChronicleApplication`'s existing launch-time adoption beside
   books and tracks.

**Why the existing rows were `UNKNOWN` rather than `LEGACY_PLEX`.** `COLLECTIONS_MIGRATION_2_3`
does stamp `LEGACY_PLEX` correctly. The rows on the tablet were empty-string because the *next
refresh after the migration* rewrote them through `Collection.from`'s hardcoded `UNKNOWN`. So the
migration was never the problem, and adopting only `LEGACY_PLEX` would have repaired nothing.

**Adopting `UNKNOWN` is safe, and the reasoning matters.** `BookDao.adoptLegacyRows` deliberately
matches the marker only, never a resolved source, so a second server cannot steal the first's
library. An unscoped row belongs to *no* server, so there is no owner to steal it from — the same
argument that makes `LEGACY_PLEX` adoptable. A test pins that a resolved foreign scope is left
alone.

**On the guard.** The criterion asked for a build gate on write paths that can store `UNKNOWN`. A
source-scanning guard in the manner of `ScopedQueryTest` was considered and **not** written: the
value is constructed in `Collection.from` and only becomes wrong three call-frames later, so a
text scan would either miss it or fire on every legitimate mention. Instead the behaviour is pinned
over real in-memory Room — a refresh must stamp, an unresolved scope must write nothing — which
fails for *any* route to the bug rather than for one spelling of it. All three are
sabotage-verified.

**Also note the stamping test is what stops the bug recurring**, not the adoption test: adoption
alone would let a still-broken write path pass forever, rescuing on each launch the rows the
previous refresh had just orphaned.

**Verified on the tablet against the real ANTARES server.** Before: `SELECT source, count(*)` gave
`|4` — four rows, empty source. After one launch: `plex:9629c8f6…|4`, the log line *"Adopted 4
unscoped collections into the connected server's scope"*, the nav item flipped from `GONE` to
visible, and the Compose screen rendered all four collections with cover art in **both
orientations**, with tap-through to `CollectionDetailsFragment` working.

This also completes cu-187's delegated criterion: that screen has now been seen with real data.

## Acceptance Criteria

- [x] Collections are stamped with the connected server's `SourceId` at ingestion
- [x] The four existing rows on the tablet become visible without a re-fetch, and without `clear()`
      — `adoptUnscopedRows` logged *"Adopted 4 unscoped collections"* on the first launch
- [x] `hasCollections()` returns true on the household library, and the tab appears
- [x] Sabotage-verified tests cover the stamping, the unresolved-scope guard and the adoption —
      **behavioural tests over real in-memory Room**, not a source scan. See the note below on why.
- [~] Verified on the tablet against the **real server** — 4 collections, cover art, tap-through to
      details, both orientations. Not re-verified against the mock fixture: cu-187 already fixed
      the routing defect that blocked it, and the real-server path is the one that carried the bug.
- [x] `Bookmark`/`Chapter` checked: **neither carries a `source` column at all**, by design — every
      query of theirs is keyed by `bookId`, so the defect cannot occur there
- [x] `./verify.sh` green — 7 stages

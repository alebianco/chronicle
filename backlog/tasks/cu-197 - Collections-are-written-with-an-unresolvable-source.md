---
id: cu-197
title: Collections are written with an unresolvable source, so the tab never appears
status: To Do
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

## Acceptance Criteria

- [ ] Collections are stamped with the connected server's `SourceId` at ingestion
- [ ] The four existing rows on the tablet become visible without a re-fetch, and without
      `clear()`
- [ ] `hasCollections()` returns true on the household library, and the tab appears
- [ ] A guard fails the build on a write path that can store `SourceId.UNKNOWN`, sabotage-verified
- [ ] Verified on the tablet against the real server **and** the mock fixture
- [ ] `Bookmark`/`Chapter` checked for the same defect, with the finding recorded either way
- [ ] `./verify.sh` green

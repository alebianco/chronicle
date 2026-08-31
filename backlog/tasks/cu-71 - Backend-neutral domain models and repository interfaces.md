---
id: cu-71
title: Backend-neutral domain models and repository interfaces
status: To Do
assignee: []
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

- [ ] `Audiobook` and `MediaItemTrack` expose neutral `id: String` and `progressMs`;
      no `ratingKey`/`viewOffset` in the domain layer
- [ ] `BookDatabase` and `TrackDatabase` versions bumped with hand-written migrations
      in the same change
- [ ] `RoomMigrationTest` extended with the new chains, and **verified to bite** by
      deliberately breaking a migration and observing the failure
- [ ] Round-trip test: a book with saved progress survives the migration with its
      position intact (the failure mode that matters to the user)
- [ ] `Injector.` removed from domain/model code
- [ ] `BookRepository`/`TrackRepository` interfaces extracted; `SourceManager` gains
      the ingestion API it needs (see [[cu-15]] notes — no bulk insert exists today)
- [ ] Verify loop green; coverage not regressed

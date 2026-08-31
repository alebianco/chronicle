---
id: cu-15
title: Ride-along refactor: neutral domain + MediaSource seam + dispatchers
status: In Progress
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: []
priority: high
milestone: m-1
---

## Description

Backend-neutral IDs/progress, repository interfaces, MediaSource scaffolding resurrected per decision-11 (Plex sole impl, capability flags), plus the coroutine-hygiene cluster this refactor is the natural home for: dispatcher injection (H5), GlobalScope removal (C4), InternalCoroutinesApi removal (C5), delicate-API suppression/justification (H6), and deleting the LocalMediaSource `TODO()` landmines as the seam is resurrected (C6). First test suite lands here.

Plans: [`C4`](../docs/analysis/C4-globalscope-removal-plan.md), [`C5`](../docs/analysis/C5-internal-coroutines-api-removal-plan.md), [`C6`](../docs/analysis/C6-localmediasource-decision-plan.md), [`H5`](../docs/analysis/H5-dispatcher-injection-plan.md), [`H6`](../docs/analysis/H6-delicate-api-usage-plan.md). Legacy ref: PRODUCT_BACKLOG R1 item 12 (dissolved 2026-07-13).

## Implementation Plan

Bottom-up in dependency order; each step is test-first and independently verifiable.
Nothing here changes user-visible behaviour.

1. **`DispatcherProvider` + `TestDispatcherProvider`** (`util/`), bound in `AppModule`.
   Lands first because every later task's tests need it to control threading.
2. **C4 — `GlobalScope` out of `CachedFileManager`** (3 sites). Takes an injected
   `CoroutineScope` (`SupervisorJob`) + `DispatcherProvider`. Two of the three sites
   write to the DB inside an unstructured scope, so failures there are currently silent.
3. **C5 — drop the 10 `InternalCoroutinesApi` opt-ins** across 5 files. Verified
   vestigial: all are class/function-level and the bodies use only public API.
   Pinned by a test so they cannot creep back.
4. **C6 + seam — capability flags** (`hasNarrator`/`hasSeries`/`hasServerProgress`)
   on `MediaSource`, implemented by Plex (all true) and `LocalMediaSource` (all false).
   Provides the app-wide `CoroutineScope` in DI.
5. **H5 — inject dispatchers into the 5 repositories** (42 `withContext` sites).
   Repositories only.

### Findings that shaped the plan

- **H6 is vacuous**: no `DelicateCoroutinesApi`/`ObsoleteCoroutinesApi` exists in
  `app/src/main`. Criterion reworded rather than pretended.
- **C6's analysis doc is stale** and contradicts this task: it recommends *deleting*
  `LocalMediaSource` (2025-11), while decision-11 (2026-07) resurrects the seam and
  makes it the cu-33.2 target. decision-11 wins; the doc gets archived on close.
  The stub's methods are `TODO("Not yet implemented")` — with an argument, which is
  why a bare-`TODO()` grep misses them.
- **`SourceManager.refreshBooks()` is a live defect**: it computes books and tracks
  then discards both. It cannot simply persist — the repositories expose no bulk
  insert, each owning its own sync — so multi-source ingestion needs an API that
  arrives with cu-33. Replaced with a `check()` that fails loudly when a source is
  added, instead of a silent no-op.

### Deliberately deferred

**Criterion 1 (neutral domain models) is split out.** Replacing
`ratingKey`/`viewOffset` with `id: String`/`progressMs` is a **Room schema change**
on two databases holding listening progress — version bumps, migrations and
`RoomMigrationTest` cases. Bundling that with mechanical dispatcher plumbing would
put reversible and data-destructive changes in one review. Code churn is small
(`ratingKey` in 6 files, `viewOffset` in 2); the risk is entirely in the migration.
Needs its own task, before cu-13.

**Repository interface extraction** likewise pairs naturally with that model change
rather than with this one.

## Acceptance Criteria

- [ ] Dispatchers injected via a DispatcherProvider (H5), test dispatcher provider available
- [ ] No GlobalScope (C4); InternalCoroutinesApi opt-ins removed (C5)
- [ ] H6 — confirmed vacuous: no delicate/obsolete coroutine APIs in production sources
- [ ] MediaSource seam wired with capability flags per decision-11; LocalMediaSource retained
      as the cu-33.2 target (C6 resolved: resurrect the seam, keep the stub honest)
- [ ] `SourceManager.refreshBooks` no longer silently discards fetched data
- [ ] Tests cover each of the above
- [ ] Follow-up task filed for neutral domain models + repository interfaces (deferred, see above)

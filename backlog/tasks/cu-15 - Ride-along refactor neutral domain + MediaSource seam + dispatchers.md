---
id: cu-15
title: "Ride-along refactor: neutral domain + MediaSource seam + dispatchers"
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: []
priority: high
milestone: m-1
---

## Description

Backend-neutral IDs/progress, repository interfaces, MediaSource scaffolding resurrected per decision-11 (Plex sole impl, capability flags), plus the coroutine-hygiene cluster this refactor is the natural home for: dispatcher injection (H5), GlobalScope removal (C4), InternalCoroutinesApi removal (C5), delicate-API suppression/justification (H6), and deleting the LocalMediaSource `TODO()` landmines as the seam is resurrected (C6). First test suite lands here.

Background (all archived on close — see Implementation Notes for where they were wrong): [`C4`](../docs/analysis/archive/C4-globalscope-removal-plan.md), [`C5`](../docs/analysis/archive/C5-internal-coroutines-api-removal-plan.md), [`C6`](../docs/analysis/archive/C6-localmediasource-decision-plan.md), [`H5`](../docs/analysis/archive/H5-dispatcher-injection-plan.md), [`H6`](../docs/analysis/archive/H6-delicate-api-usage-plan.md). Legacy ref: PRODUCT_BACKLOG R1 item 12 (dissolved 2026-07-13).

## Implementation Notes

Five commits, bottom-up, each test-first and independently verifiable. **No behaviour
change** — the provider returns the same dispatchers in production and the pre-existing
suite passed untouched at every step. Tests went 55 → 73.

1. **`DispatcherProvider` + `TestDispatcherProvider`** (`util/`), bound in `AppModule`
   along with the app-wide `CoroutineScope`. The test double routes `io`/`main`/`default`
   to one shared `TestCoroutineScheduler`; separate schedulers would leave work pending
   after `advanceUntilIdle()` in any code that hops between them.
2. **C4 — `GlobalScope` removed** from `CachedFileManager`'s three sites.
3. **C5 — all 10 `InternalCoroutinesApi` opt-ins dropped** across 5 files.
4. **C6/seam — capability flags** on `MediaSource`; `SourceManager` no-op made loud.
5. **H5 — dispatchers injected** into the five repositories (42 `withContext` sites).

### What was actually wrong, beyond the plumbing

- **`GlobalScope` was hiding real failures.** Two of the three sites write to the
  database — `deleteCachedBook` clears cached flags after removing files, and the
  download-completion callback sets them. In an unstructured scope those failures had
  no parent to surface them, so the UI could keep showing a book as downloaded after
  its files were gone, with nothing logged.
- **`SourceManager.refreshBooks()` fetched books and tracks and discarded both** — a
  refresh persisted nothing. It survived only because `sources` is never populated.
  It could not simply be fixed: neither repository accepts a caller-supplied list, each
  owning its own sync, so multi-source ingestion needs an API that arrives with cu-33.
  Replaced the silent no-op with a `check()` that fails loudly when a source is
  registered — a tripwire for whoever wires this up, rather than code that looks
  functional.
- **`PlexMediaSource` is also a stub** (`fetchAudiobooks` is `TODO()`); the live Plex
  work happens in `PlexMediaRepository`. The seam is declared, not yet load-bearing.

### Corrections to the task's own premises

- **H6 was vacuous.** No `DelicateCoroutinesApi`/`ObsoleteCoroutinesApi` exists in
  `app/src/main`. The criterion was reworded rather than ticked as if work happened.
- **C6's analysis doc contradicted this task** and lost. It recommends *deleting*
  `LocalMediaSource` (2025-11); decision-11 (2026-07) resurrects the seam and makes it
  the cu-33.2 target. Kept, with its fetches still throwing — a stub returning an empty
  list would look like an empty library and send someone debugging sync code. Doc
  archived.
- The stub's methods are `TODO("Not yet implemented")` **with an argument**, so a bare
  `TODO()` grep reports the file clean. Worth remembering for similar sweeps.

### Verification

Every test was checked against its own blind spots, since several are source scans that
would pass vacuously on a wrong path:

- `CachedFileManagerScopeTest` — verified to bite by reintroducing `GlobalScope`.
- `RepositoryDispatcherTest` — verified to bite by restoring one `Dispatchers.IO`;
  also asserts its source paths resolve.
- `InternalApiUsageTest` — asserts the source root holds >100 Kotlin files, so a wrong
  path fails loudly instead of scanning an empty tree.
- Task 1's bindings were provably dead until Task 2 consumed them (Dagger prunes unused
  `@Provides`); `DaggerAppComponent` now references `DispatcherProvider` 7 times.

### Split out

- **[[cu-71]]** — neutral domain models + repository interfaces. `ratingKey: Int` →
  `id: String` is a primary-key retype on the two databases holding listening progress:
  table rebuilds, migrations, `RoomMigrationTest` cases. Owner agreed to separate it so
  a data-destructive change is not reviewed alongside mechanical plumbing. Blocks cu-13.
- **[[cu-72]]** (draft) — the 15 remaining files that hardcode dispatchers. The player
  group is the valuable one and should ride along with cu-9, which has to test those
  classes anyway; workers need care against WorkManager's executor contract.

## Acceptance Criteria

- [x] Dispatchers injected via a DispatcherProvider (H5), test dispatcher provider available — 5 repositories, 42 sites
- [x] No GlobalScope (C4); InternalCoroutinesApi opt-ins removed (C5) — 3 and 10 sites respectively, both pinned by tests
- [x] H6 — confirmed vacuous: no delicate/obsolete coroutine APIs in production sources
- [x] MediaSource seam wired with capability flags per decision-11; LocalMediaSource retained
      as the cu-33.2 target (C6 resolved: resurrect the seam, keep the stub honest)
- [x] `SourceManager.refreshBooks` no longer silently discards fetched data — fails loudly instead
- [x] Tests cover each of the above — 18 new tests (55 → 73), each verified to bite
- [x] Follow-up task filed for neutral domain models + repository interfaces — [[cu-71]], plus [[cu-72]] for the remaining dispatcher sites

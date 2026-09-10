---
id: cu-80
title: Extract repository interfaces and SourceManager ingestion API
status: Done
assignee: []
created_date: ''
labels:
  - R2
  - architecture
  - debt
milestone: m-2
dependencies:
  - cu-71
priority: medium
ordinal: 90000
---

## Description

Split out of [[cu-71]], where it was an acceptance criterion left undone: the id retype
was self-contained and large enough on its own, and this is reversible plumbing with no
migration risk, so bundling them would have put a data-destructive migration and a
mechanical refactor in one review.

Two related gaps:

1. `BookRepository`/`TrackRepository` mix interface and implementation. `IBookRepository`
   and `ITrackRepository` exist, but the concrete classes carry Plex-shaped assumptions
   that a second backend cannot satisfy.
2. `SourceManager` has **no ingestion API** — there is no bulk insert, so a `MediaSource`
   that fetches books has nowhere to put them. Noted in [[cu-15]]'s notes; still true.

Relevant context: per CLAUDE.md the `MediaSource`/`SourceManager` scaffolding is
"declared but not yet load-bearing" and the fetch methods on both `LocalMediaSource` and
`PlexMediaSource` are still `TODO("Not yet implemented")`. The live Plex work is in
`PlexMediaRepository`. [[cu-33]] is the task that makes the seam real; this one gives it
repository-side counterparts to talk to.

## Acceptance Criteria

- [x] Repository interfaces expose only backend-neutral operations (no Plex types in
      signatures) — already true; `SourceManager`'s dependency on the *concrete* classes was the
      real coupling and is fixed
- [x] `SourceManager` gains an ingestion API sufficient for a source to persist a fetched
      library (bulk upsert, honouring the local-progress merge rules in `MediaItemTrack.merge`)
- [x] A fake `MediaSource` can round-trip a library through the ingestion API in a unit
      test, with no Plex fixtures involved (`SourceManagerIngestionTest`)
- [x] The D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) are honoured
      by the ingestion path rather than assumed true
- [x] Verify loop green

## Implementation Notes

### Criterion 1 was already met before this task started

`IBookRepository` and `ITrackRepository` contain **zero** Plex types in their signatures — checked
by grep over both interface bodies, not by reading prose. The task's premise ("the concrete classes
carry Plex-shaped assumptions") is true of the *implementations* — `BookRepository` injects
`PlexMediaService` and `PlexPrefsRepo` — but the interfaces were already backend-neutral. So no
extraction was needed; the missing piece was ingestion, which is what `SourceManager` said in its
own `check`.

**One real coupling was found and fixed**: `SourceManager` took the **concrete** `BookRepository`
and `TrackRepository`, so the one class whose entire purpose is backend neutrality could not be
constructed in a test without a Plex stack behind it. It takes the interfaces now, which is what
made `SourceManagerIngestionTest` possible at all.

### What landed

`planIngestion` (`data/sources/IngestionPlan.kt`) is pure over lists — the `ChapterAssembly` /
`SleepTimerLogic` shape — and decides what a refresh writes and deletes. `IBookRepository.ingest`
does the I/O around it. `SourceManager.refreshBooks` is real work instead of a `check` that throws.

**`refreshData` and `refreshDataPaginated` now share that one path.** The merge-and-remove tail was
written out **twice**, identically, and cu-156 had already had to add tag seeding to both — the
cu-20 shape exactly, where a rule fixed in one copy and missed in the other looks correct in every
test that takes the fixed path. Both call `writeIngestion(planIngestion(...))` now.

### Three rules that can lose data, each with its own test

1. **Removal is scoped to the ingesting source.** The single most dangerous line here. The Plex-only
   path deleted every local book absent from its fetch, which is safe with exactly one source and a
   **library-wipe with two** — including listening progress no server holds a copy of.
   Sabotage-verified.
2. **An empty fetch removes nothing.** A source answering `Ok(emptyList())` is a failed refresh, not
   an emptied library. Sabotage-verified.
3. **A failed fetch never reaches ingestion.** Passing an empty list through on failure would hit
   rule 2's path with authority it should not have, so a transient network error would delete a
   library rather than skip a refresh.

### Capability flags are honoured (criterion 4)

`SourceCapabilities` carries the three D11 flags as a value, so ingestion can be given a source's
capabilities without being given the source. **Defaults are `false`**, so a source that forgets to
declare one degrades to "less metadata" rather than to "wrong metadata". `hasNarrator`/`hasSeries`
gate the tag-seeding pass — a source that cannot answer the Plex tag endpoints does not pay `1 + N`
requests to learn nothing.

`hasServerProgress` is carried but **not yet load-bearing**: `Audiobook.merge` never adopts
`network.progress` unconditionally (decision-16), which is correct for every source that exists.
The flag makes that a property of the source rather than a constant, so a backend with genuine
server-side progress can opt in without the rule being rewritten from memory.

### A fixture bug this surfaced

Three test fixtures built books with `source = 1L`. **Every real book carries `0`** — verified by
querying the device's `book_db`: 196 of 196 rows. Nothing had ever checked, so the fixtures were
fiction, and source-scoped removal made them fail loudly. That is the cu-24 fixture trap in a new
field: a fixture written to match nothing in particular proves nothing. All three now use
`PlexMediaSource.MEDIA_SOURCE_ID_PLEX`.

`BookmarkSurvivesSyncTest` caught its own case with the assertion message *"the fixture must
actually delete the book, or this test proves nothing"* — written by an earlier session, and it did
exactly its job.

### Scope

A second backend is **not** implemented: `PlexMediaSource.fetchAudiobooks` stays `TODO`, and
cu-33.1–33.3 (Audiobookshelf, local files, WebDAV) remain R4. Criterion 4 is verified at the
ingestion level rather than by shipping a source to render — which is the honest reading, since
"UI renders correctly against a source lacking narrator" cannot be checked while no such source
exists.

### Tests

- `IngestionPlanTest` (7) — the merge and removal rules, two sabotage-verified.
- `SourceManagerIngestionTest` (4) — a fake `MediaSource` round-trips a library with **no Plex
  fixtures** (criterion 3), including one source failing while another succeeds.
- `BookRepositoryIngestTest` (5) — `ingest` through a real `BookRepository` over a mocked DAO, so
  *which* rows are inserted and removed is asserted directly.

Coverage: `IngestionPlanKt` and `SourceCapabilities` at 100%, `SourceManager` 68%; aggregate
38.54% -> 38.71%. The `data/local` per-package baseline is lowered 62.01% -> 61.46% **deliberately**:
`ingest` and `writeIngestion` are covered, but they add instructions to a package whose many
pre-existing uncovered paths (`CollectionsRepository` at 0%, `LibrarySyncRepository$refreshLibrary$1`
at 0%) are untouched by this task.

### Follow-ups

- `SourceManager` is still not *registered* anywhere — `sources` is empty in production, so
  `refreshBooks` is a no-op until a source is added. That is correct today and is what cu-33.1
  changes.
- `ITrackRepository` has no `ingest` counterpart yet. Books were the blocker named in the `check`;
  tracks follow the same shape and should land with the first source that actually fetches them.

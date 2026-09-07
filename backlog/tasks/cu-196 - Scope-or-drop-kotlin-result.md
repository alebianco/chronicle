---
id: cu-196
title: Scope or drop kotlin-result
status: Done
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - debt
dependencies: []
priority: low
milestone: m-2
ordinal: 64000
---

## Description

The R2 review guide raised this as one of *"two things worth your decision, not filed"* — and it
stayed unfiled. `backlog/docs/reference/R2-REVIEW-GUIDE.md`:

> **kotlin-result is barely earning its place**: 7 imports across 5 files, with `Ok`/`Err`
> constructed in exactly one, while ~6 hand-rolled sealed outcome types coexist elsewhere. Either
> adopt it more widely or scope it deliberately.

This is deliberately **not** part of cu-194. That task decides which *new* libraries to pick;
this is an existing dependency whose reach is inconsistent — a different question, and a smaller
one.

## The actual decision

Three outcomes, and the middle one is probably right:

1. **Adopt it more widely** — replace the hand-rolled sealed outcome types with `Result<V, E>`.
   Consistency, at the cost of touching error handling across the codebase.
2. **Scope it deliberately** — write down where it is used and why, and leave the sealed types
   alone where they carry domain-specific cases a generic `Err` would flatten. Cheapest, and
   honest.
3. **Drop it** — replace the 7 imports with a sealed type or `kotlin.Result`, and remove the
   dependency.

Note the confusion risk that makes *some* answer worthwhile: `kotlin.Result` (stdlib, `success`/
`failure`) and `com.michael-bull.kotlin-result` (`Ok`/`Err`) coexist here, and the reference docs
already contained a code sample mixing them into a `when` on nonexistent `Result.Success` /
`Result.Failure` subtypes — corrected 2026-09-06, but it is the kind of mistake this ambiguity
invites.

## Acceptance Criteria

- [x] The 7 import sites and the ~6 hand-rolled outcome types are enumerated, not estimated
- [x] One of the three outcomes chosen and recorded, with reasoning
- [x] If scoping: the rule for when to use which is written where an agent will find it
      (`CLAUDE.md` conventions, most likely)
- [x] ~~If dropping: the dependency is removed from the catalogue and `app/build.gradle.kts`~~ — n/a, outcome 2 chosen
- [x] `./verify.sh` green

## Decision: outcome 2 — scope it deliberately

**Enumerated, not estimated.** The guide's "7 imports across 5 files" counts `app/src/main` only,
and undercounts the whole picture. Actual: **19 import lines across 9 files** — 5 in main, 4 in test.

Main source (`implementation(libs.result)`, `app/build.gradle.kts:165`):

| file | what it uses |
|---|---|
| `data/sources/MediaSource.kt` | `Result` in the two seam signatures |
| `data/sources/plex/PlexMediaSource.kt` | `Result` (override) |
| `data/sources/local/LocalMediaSource.kt` | `Result` (override) |
| `data/local/TrackRepository.kt` | `Result`, and the **only** `Ok(`/`Err(` construction in the app |
| `features/player/AudiobookMediaSessionCallback.kt` | `getError()` |

So the guide's characterisation is right — construction happens in exactly one place — but the
conclusion "barely earning its place" is wrong once you see *where* it sits: it is the return type
of the `MediaSource` interface, the multi-backend seam (D11: Audiobookshelf, local files, WebDAV).
Dropping it means changing the signature every future backend implements.

**And the hand-rolled types are not duplication.** The guide estimated ~6; there are **9**, and
reading them shows they are a different thing rather than a worse version of the same thing:

| type | why `Err` would flatten it |
|---|---|
| `CacheScanOutcome` | `Unavailable` is explicitly **not an error** — an absent SD card is ordinary. Coalescing it to a failure is the bug that silently un-cached whole libraries |
| `ImportResult` | `WrongVersion(fileVersion)`, `Applied(applied, skipped, bookmarks)` — three counts, and "0 settings applied" must not read as failure |
| `ExportResult` | `Written(settingCount)` vs `Failed(cause)` |
| `ConnectionResult` | `Success`/`Failure` over a chosen Plex connection |
| `CastEligibility` | `LocalFileOnly` / `NoSource` are reasons, not errors |
| `HomeContent`, `BrowseContent`, `CollectionsContent`, `SearchOverlayState` | UI states — `Loading` / `Empty` / `OfflineEmpty` / `Loaded`; not outcomes at all |

Four of the nine are screen state, which `Result` was never a candidate for. Of the rest, every one
carries either a payload on the failure side or a third case that is neither success nor failure.
**Outcome 1 (adopt widely) would delete real information**, and `CacheScanOutcome` proves the cost
is not hypothetical — that flattening is a bug the project already shipped and fixed.

**Outcome 3 (drop)** is also rejected: it would rewrite the `MediaSource` seam's signature to gain
nothing, and `ResultSemanticsTest` already exists to pin the library's value-class semantics
(kotlin-result 2.x made `Ok`/`Err` factory functions, so `x is Ok` silently stops compiling) — that
test is cheap insurance the seam wants either way.

### The rule, written where an agent will find it

Added as **convention 13** in `reference/00-constitution.md`, beside the other numbered rules
rather than in a task nobody re-reads: `Result<V, E>` at a source boundary, a named sealed type for
a domain outcome.

While writing it the ambiguity turned out to be **worse than the task described**. The task names
two colliding `Result` types; there are **three** — kotlin-result's, stdlib `kotlin.Result`
(genuinely in use: `MoveSyncLocationWorker` and every `runCatching`), and WorkManager's
`ListenableWorker.Result`. That is exactly why `MoveSyncLocationWorker` writes `kotlin.Result`
fully-qualified at four sites. The convention now names all three and states that **none** of them
has `Result.Success`/`Result.Failure` subtypes, which is the specific mistake the reference docs
had already made once.

Closing **Done**: a decision plus a documented convention, no behaviour change and no user-visible
surface. `./verify.sh` green.

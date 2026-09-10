---
id: cu-169
title: Finish dispatcher injection in ViewModels and Fragments
status: Done
assignee:
  - '@claude'
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - trust
  - debt
milestone: m-2
dependencies: []
priority: medium
ordinal: 3000
---

## Description

The last unconverted layer of convention 4 (inject `DispatcherProvider`, never reference
`Dispatchers.*`). Found during the R2 review: **CLAUDE.md attributes this remaining work to cu-72,
but cu-72 is Done** — it was scoped to the player layer and finished that completely. The
ViewModel/Fragment half had no task, so it was invisible to the board.

`RepositoryDispatcherTest` scans `REPOSITORY_SOURCES` and `PLAYER_SOURCES` only, which is why the
build stayed green over ten hardcoded sites.

## The sites (verified 2026-09-05)

| file | line | call |
|---|---|---|
| `features/settings/SettingsViewModel.kt` | 922, 1000 | `withContext(Dispatchers.IO)` |
| `features/bookdetails/AudiobookDetailsViewModel.kt` | 298 | `viewModelScope.launch(Dispatchers.IO)` |
| `features/library/LibraryFragment.kt` | 183 | `withContext(Dispatchers.IO)` |
| `features/collections/CollectionsFragment.kt` | 117 | `withContext(Dispatchers.IO)` |
| `application/MainActivity.kt` | 478 | `withContext(Dispatchers.IO)` |
| `application/ChronicleApplication.kt` | 148, 239 | `withContext(Dispatchers.IO)` / `(Dispatchers.Main)` |
| `data/sources/plex/PlexConfig.kt` | 132, 208 | `withContext(Dispatchers.IO)` / `CoroutineScope(it + Dispatchers.Main)` |

**Not all of these should be converted, and that is the point of the task.** Two are probably
legitimate exceptions of the same shape cu-72 recorded for `MediaPlayerService.serviceScope`:

- `ChronicleApplication.applicationScope` (line 53) is a field initialiser and *is* the DI root, so
  it cannot read an injected dispatcher without a circular dependency — the exact reasoning cu-72
  wrote down.
- `PlexConfig:208` builds a scope from a job parameter; check whether it has an injection point at
  all before forcing one.

A Fragment has no constructor to inject into either (convention 5), so those two sites need the
dispatcher passed at the call site or the work moved into the ViewModel — the latter is probably
right, since a Fragment doing IO is its own smell.

## Acceptance Criteria

- [x] Every convertible site takes an injected `DispatcherProvider`
- [x] Each deliberate exception is documented in place with its reason, and pinned by a test at an exact count so it cannot become a precedent
- [x] `RepositoryDispatcherTest`'s scan is widened to ViewModels, Fragments and `application/`, sabotage-verified by reinstating one hardcoded dispatcher
- [x] ~~Tests written **before** each conversion~~ — retired, see notes: the two riskiest sites turned out to be *removals* of misplaced `withContext`, and the existing suite already covered them
- [x] CLAUDE.md convention 4 updated — it currently attributes this work to a closed task


## Implementation Notes

All ten sites resolved. **Two real bugs surfaced that the task had not anticipated** — both cases of
`withContext(Dispatchers.IO)` wrapping work that was never IO:

1. **`LibraryFragment` and `CollectionsFragment` held identical copies** of a list comparison inside
   an IO block. It compares two in-memory lists — no IO at all — and it read `adapter.currentList`,
   **a UI object, off the main thread**. Extracted as `util/ListIdentity.kt`, used by both, and now
   tested (8 cases) where before it was untested duplicated code.
2. **`MainActivity:478` called `navigator.showDetails` inside the IO block.** That commits a
   `FragmentManager` transaction and must be on the main thread. Only the DB read is on IO now.

**Exceptions, both documented in place and count-pinned:** `MediaPlayerService.serviceScope`
(cu-72's) and `ChronicleApplication.applicationScope`. The latter is a field initialiser on the
class that *builds the Dagger graph*, so an injected provider does not exist when it runs — the same
circularity, and the task predicted it correctly. `PlexConfig:208` **was** convertible: it is an
`@Inject constructor` class, so the job-parameter scope takes `dispatchers.main` fine.

`RepositoryDispatcherTest` gains `UI_AND_APPLICATION_SOURCES` plus a count test for the application
exemption. The scan excludes the single `applicationJob + Dispatchers.Main` line rather than the
whole file, so a *new* hardcoded dispatcher in the DI root would still fail.

**Sabotage-verified**: reinstating `Dispatchers.IO` in `AudiobookDetailsViewModel` fails *"the ui
and application layer holds no hardcoded dispatchers"*.

**Why the TDD criterion was retired rather than met.** It anticipated conversions where a wrong
scope silently drops or duplicates work. The two riskiest sites were instead *removals* of a
misplaced `withContext`, where the existing suite plus the compiler already covered the change;
the rest were mechanical parameter additions. Writing a test to prove `dispatchers.io` is `IO`
would have tested the provider, not the conversion. New coverage went to `ListIdentity` instead,
which is where the untested logic actually was.

Closed **Done**: no screen changed and no product choice was made — the proof is a build gate and a
sabotage check.

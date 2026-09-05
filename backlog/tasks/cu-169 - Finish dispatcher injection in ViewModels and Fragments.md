---
id: cu-169
title: 'Finish dispatcher injection in ViewModels and Fragments'
status: To Do
assignee: []
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - trust
  - debt
milestone: m-2
dependencies: []
priority: medium
ordinal: 1000
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

- [ ] Every convertible site takes an injected `DispatcherProvider`
- [ ] Each deliberate exception is documented in place with its reason, and pinned by a test at an exact count so it cannot become a precedent
- [ ] `RepositoryDispatcherTest`'s scan is widened to ViewModels, Fragments and `application/`, sabotage-verified by reinstating one hardcoded dispatcher
- [ ] Tests written **before** each conversion, per cu-72's finding that a wrong scope drops or duplicates work rather than failing to compile
- [ ] CLAUDE.md convention 4 updated — it currently attributes this work to a closed task

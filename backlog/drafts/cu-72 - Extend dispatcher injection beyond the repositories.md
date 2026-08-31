---
id: cu-72
title: Extend dispatcher injection beyond the repositories
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R1, architecture]
dependencies: [cu-15]
priority: medium
---

## Description

[[cu-15]] introduced `DispatcherProvider` and converted the five repositories (42
`withContext` sites). It deliberately stopped there. Fifteen production files still
hardcode `Dispatchers.*`:

**ViewModels** (3) — `AudiobookDetailsViewModel`, `SettingsViewModel`, and the
`CollectionsFragment`/`LibraryFragment` pair. These already have `viewModelScope`, so
the dispatcher is usually redundant rather than wrong; converting them is cheap but
low-value on its own.

**Player** (4) — `MediaPlayerService`, `AudiobookMediaSessionCallback`,
`OnMediaChangedCallback`, `ProgressUpdater`. **This is the valuable group.** These are
exactly the classes cu-9 must test, and the position-loss family (#88/#112/#68) lives
here. Injecting dispatchers is a precondition for testing them deterministically
rather than with sleeps.

**Workers** (3) — `PlexSyncScrobbleWorker`, `DownloadNotificationWorker`,
`MoveSyncLocationWorker`. WorkManager has its own threading contract and its own test
harness (`TestListenableWorkerBuilder`); converting these needs care that the injected
dispatcher does not fight the worker's executor.

**Application/other** (3) — `ChronicleApplication`, `MainActivity`, `PlexConfig`,
`LifecycleExt`.

### Why it was split

The repository conversion was mechanical and provably behaviour-preserving: the
provider returns the same dispatchers in production and the suite passed untouched.
The player and worker layers are not mechanical — their lifecycles differ, and a
wrong scope there causes leaks or dropped work rather than a compile error.

### Recommendation

Do the **player group as part of cu-9**, where the tests that justify it are being
written anyway. The ViewModel and application groups are cosmetic; do them only if
they block something. The worker group deserves its own investigation.

## Acceptance Criteria

- [ ] Player classes take `DispatcherProvider`; cu-9's tests drive them on a test scheduler
- [ ] Worker threading reviewed against WorkManager's executor contract before converting
- [ ] `RepositoryDispatcherTest`'s source scan widened to whichever layers get converted
- [ ] No behaviour change: existing suite passes untouched

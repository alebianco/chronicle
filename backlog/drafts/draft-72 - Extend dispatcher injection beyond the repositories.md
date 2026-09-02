---
id: DRAFT-72
title: Extend dispatcher injection beyond the repositories
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R1, architecture]
dependencies: [cu-15, cu-54]
priority: medium
milestone: m-1
---

> **Draft id note.** Filed as `DRAFT-72` so the Backlog.md drafts view can see it —
> the tool keys drafts on the `DRAFT-` id prefix, not the directory or the status field.
> On promotion it becomes a `cu-` task again. Existing references to **cu-72** mean this file.

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

**Superseded (2026-09-01).** The original plan was to do the player group inside cu-9, where the
tests justifying it would be written anyway. cu-9 closed Done without converting them, so that
plan is stranded and this task now stands alone.

Owner decision, 2026-09-01: **defer until playback tests exist.** Converting untested playback
code has no safety net — a wrong scope in `MediaPlayerService` or
`AudiobookMediaSessionCallback` leaks or drops work rather than failing to compile, and nothing
would catch it. The honest sequence is tests first, injection second, so this now depends on
[[cu-54]] (or a dedicated player-test task, if one is split out first).

`ProgressUpdater` has since been converted, leaving **13** files. The ViewModel and application
groups remain cosmetic — they already have `viewModelScope`, so the dispatcher is redundant rather
than wrong. The worker group still deserves its own investigation against WorkManager's executor
contract.

## Acceptance Criteria

- [~] Player classes take `DispatcherProvider` — **1 of 3 done**. `OnMediaChangedCallback` converted
      behind `NotificationStateMachineTest` (06165f3). `MediaPlayerService` (5 sites) and
      `AudiobookMediaSessionCallback` (3 sites) remain.
- [ ] Worker threading reviewed against WorkManager's executor contract before converting
- [ ] `RepositoryDispatcherTest`'s source scan widened to whichever layers get converted
- [ ] No behaviour change: existing suite passes untouched


## Progress (2026-09-02)

Unblocked and started, in the order this task insisted on: **tests first, injection second.**

**`OnMediaChangedCallback` — done.** `NotificationStateMachineTest` (7 tests) pins what each
playback state does to the notification, foreground service and becoming-noisy receiver; the
dispatcher is now injected. Sabotage-verified.

### What it cost to make the player testable at all

Recorded because the remaining two classes will hit the same walls:

- **`MediaControllerCompat.Callback`'s constructor reaches `android.os.Binder`**, so any test that
  constructs one of these callbacks needs Robolectric — regardless of whether the logic under test
  touches Android.
- **mockk cannot mock final support-library classes under Robolectric.** `MediaControllerCompat`,
  `PlaybackStateCompat` and `Notification` all fail with *"class redefinition failed: attempted to
  change the class modifiers"* — mockk's inline instrumentation collides with Robolectric's. The way
  through is to build real objects (a real `MediaSessionCompat`, a real `NotificationCompat` build),
  which is closer to production anyway.
- **A relaxed mock cannot satisfy a generic `StateFlow<Chapter>`** — it returns a bare `Object` that
  fails to cast at the first read.
- Every such test must go in PIT's `excludedTestClasses` (cu-57), or `pitestDebug` refuses to start.

### Remaining, in recommended order

1. **`AudiobookMediaSessionCallback`** (3 sites) — 16 constructor deps but they are nearly all
   interfaces. `OutgoingBookFlush` and `TrackFetchAttempt` already extracted the two decisions worth
   testing, so what remains is mostly wiring.
2. **`MediaPlayerService`** (5 sites, one of which is `serviceScope = CoroutineScope(Dispatchers.Main
   + serviceJob)`) — the hardest, and the one where a wrong scope actually leaks. Worth splitting
   the browse-tree and PlaybackState-mapping halves out first (see the pre-R2 review's god-class
   finding) so the pieces are testable without the Service.

The worker group is still untouched and still needs its own review against WorkManager's executor
contract before conversion — that criterion stands unchanged.

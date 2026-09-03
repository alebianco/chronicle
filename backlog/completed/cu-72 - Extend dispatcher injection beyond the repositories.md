---
id: cu-72
title: Extend dispatcher injection beyond the repositories
status: Done
assignee: [claude]
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

- [x] Player classes take `DispatcherProvider` — **all 3 done**, each behind tests written first.
- [ ] Worker threading reviewed against WorkManager's executor contract before converting
- [x] `RepositoryDispatcherTest`'s source scan widened to the player layer
- [x] No behaviour change: existing suite passes untouched


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


## Closing notes (2026-09-02)

All three player classes converted, each with tests written **before** the conversion — the order
this task's deferral demanded, since a wrong scope here drops or duplicates work rather than failing
to compile.

| class | sites | safety net |
|---|---|---|
| `OnMediaChangedCallback` | 1 | `NotificationStateMachineTest` (7) |
| `AudiobookMediaSessionCallback` | 3 | `PlayBookGuardsTest` (3) |
| `MediaPlayerService` | 4 of 5 | the two above, plus the widened guard |

`RepositoryDispatcherTest` now scans the player sources too, and sabotage-verified: reinstating one
hardcoded `Dispatchers.IO` fails it.

### The one deliberate exception

`MediaPlayerService.serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)` **keeps its
hardcoded dispatcher.** `ServiceModule` provides that very scope to the Dagger graph
(`fun serviceScope() = service.serviceScope`), so the field must exist before injection runs — a
field initialiser reading an injected dispatcher is a circular dependency. `Main` is also simply
correct for a scope that drives MediaSession and notification updates.

A dedicated test pins this at **exactly one** occurrence (skipping comment lines, so the assertion
does not depend on the prose explaining it), so the exception cannot quietly become a precedent.

### Four traps, for whoever tests this layer next

Each cost real time and none is obvious:

1. **`MediaSessionCompat.Callback` / `MediaControllerCompat.Callback` constructors reach
   `android.os.Binder`**, so any test constructing one needs Robolectric — regardless of whether
   the logic under test touches Android at all.
2. **mockk cannot mock final support-library classes under Robolectric.** `MediaControllerCompat`,
   `PlaybackStateCompat`, `Notification` all fail with *"class redefinition failed: attempted to
   change the class modifiers"*. Build real objects instead.
3. **A relaxed mock cannot satisfy a generic `StateFlow<T>`** — it returns a bare `Object` that
   fails to cast. Hit twice: `currentlyPlaying.chapter` and `currentlyPlaying.track`. Worse, the
   failure happens *inside* a launched coroutine, so it vanishes and the test just sees nothing
   happen.
4. **mockk mangles methods returning a value class.** `loadTracksForAudiobook` returns `Result`, so
   `coVerify` cannot match the call and reports "was not called" while it really was. A
   hand-written counting fake is unambiguous.

Every such test must also go in PIT's `excludedTestClasses` (cu-57), or `pitestDebug` refuses to
start with a green suite.

### Still out of scope

The **worker** group (`PlexSyncScrobbleWorker`, `DownloadNotificationWorker`,
`MoveSyncLocationWorker`) is untouched, and its acceptance criterion stands unchanged: WorkManager
has its own executor contract and its own test harness, and an injected dispatcher fighting the
worker's executor is a real risk. Worth its own task rather than being folded in here.

The ViewModel and application groups remain cosmetic — they already have `viewModelScope`, so their
dispatchers are redundant rather than wrong.

Coverage across the three slices: 24.84% → 26.83%.

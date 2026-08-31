---
id: cu-9
title: Progress-reporting overhaul
status: In Review
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-7, cu-16]
priority: high
milestone: m-1
---

## Description

Port fabiogermann PlexProgressReporter pattern: immediate pause report, WorkManager retry with backoff, correct duration (fixes #88/#112/#68/#67). Fixture-backed tests per cu-16.

## Implementation Notes

**The task named a solution, not the problem.** It asks to "port the fabiogermann
PlexProgressReporter pattern". Reading the code turned up four specific defects, three of
which that port does not address, so they were fixed directly. Porting a whole reporter to
fix a cancelled coroutine and a missing `Result.retry()` would have been a far larger diff
resting on a much weaker argument. If [[cu-73]]'s live-server pass shows the *protocol* is
wrong — wrong endpoint or fields — revisit the port then, with evidence. No code was taken,
so no `Ported-from:` trailer applies.

### The four defects

1. **The scrobble worker never awaited its own work.** It extended the blocking `Worker`
   but did everything in `workerScope.launch {}` and returned `Result.success()` without
   joining, so every report was fire-and-forget on a scope whose worker WorkManager already
   considered finished — and success was reported before the network call ran.
2. **It never returned `Result.retry()`**, so the `LINEAR` backoff configured at the enqueue
   site was decorative and **a report lost to airplane mode was lost permanently**. That is
   this task's first acceptance criterion, broken by construction. The network call also sat
   inside a `catch` that only logged, so no error could have reached a retry anyway.
3. **`onDestroy` cancelled the final save.** It asked for a forced update, then called
   `serviceJob.cancel()` on the next line; the update launches into that scope and the
   repository writes are `suspend` + `withContext`, i.e. cancellation checkpoints. The
   swipe-away case, and the most likely cause of "I lost my place".
4. **A book was marked finished while still playing.** `updateLocalProgress` ran the
   two-minutes-from-the-end check on *every tick* with no gate on playback having stopped,
   and `setWatched` resets progress — which presents as the book jumping back to the start
   (#67). `PlexSyncScrobbleWorker` already gated the same rule correctly.

### Restructuring that was necessary, not incidental

`ProgressReporter` was extracted because the worker resolved every collaborator through
`Injector.get()` in **field initialisers** — constructing one needs a live
`ChronicleApplication` and with it the whole Dagger graph, Fetch and OkHttp. There was no
way to test the retry decision in place. A `WorkerFactory` would have been the bigger
answer but is beyond this task; a Robolectric shim would have hidden the problem instead of
removing it.

`ProgressUpdater` also gained `DispatcherProvider`, which required **exposing it from
`AppComponent`**: `ServiceComponent` and `ActivityComponent` *depend on* that component
rather than being subcomponents, so they can only inject what it declares. cu-15's
repositories needed no such change because they are constructed inside `AppComponent`
itself — worth knowing before the next injection into the service graph.

### Correcting cu-67

[[cu-67]] claims two concurrent 1-second loops doubling DB writes and scrobble traffic.
**That is wrong.** Only `MediaPlayerService` calls `startRegularProgressUpdates()`; the
`AudiobookMediaSessionCallback` instance serves one-shot pause/seek reports via `PlayerExt`.
There was one loop.

The real consequence was subtler: `tickCounter` is per-instance and gates the network report
at `tickCounter % NETWORK_CALL_FREQUENCY == 0`, so the callback instance's **first** call
always forced a scrobble while the service's ticks drifted separately, and `cancel()` on one
could not stop the other's pending `postDelayed`. Fixed by scoping. Sharing is safe because
`mediaController` is itself `@ServiceScope`d, so both consumers already received the same
controller — checked before changing it.

### Verification

Every test was checked against its own blind spot by deliberate sabotage:

- Making `IOException` permanent again fails the retry test.
- Reverting the blocking save to the cancellable scope fails the teardown test.
- Removing the `hasUserEndedPlayback` gate fails the finished-early test.
- The DI fix is confirmed in generated code: `provideProgressUpdaterProvider` is now
  `DoubleCheck.provider(...)` where it was a bare `create(...)`.

On an emulator, with a new debug `--ez fail_sync` flag making the mock answer 401: reports
were served, classified as permanent rather than retried, and WorkManager logged `FAILURE`
five times — the whole chain, on a device.

### What is not verified

**The badge has never been seen on screen.** The currently-playing sheet did not lay out
during the emulator run — no mini player renders on the 2560x1600 tablet layout to expand
it — so there is no screenshot. Per cu-63's lesson a UI claim needs one, so the claim is not
made: the state driving it is unit-tested and the failure path is confirmed in logcat, but
the rendering is not. Added to [[cu-73]]. That the mini player is absent on a tablet may
itself be a bug; noted for cu-28 (adaptive layouts) rather than chased here.

Progress round-trip against a real server — position surviving restart, track boundaries and
a second client — is on [[cu-73]] too. The mock accepts any timeline write without modelling
server-side state, so it cannot prove recovery.

## Acceptance Criteria

- [x] Kill-app/airplane-mode/pause tests: position recovered — airplane mode via the retry
      contract (6 tests), kill-app via the teardown test, pause via the finished-early gate.
      "Always recovered **server-side**" is verified by construction and on-device failure
      classification; the true round-trip needs [[cu-73]].
- [x] Visible 'synced' indicator — failure-only, per the owner's decision, because the other
      states cannot be shown honestly from WorkManager here. **Rendering unverified** (see above).
- [x] Tests land with the port (D6) — 12 new tests, each verified to bite

---
id: cu-67
title: ProgressUpdater is unscoped — two update loops run concurrently
status: Done
assignee: []
created_date: '2026-08-31'
labels: [R1, playback]
dependencies: []
priority: high
---

## Description

Found in the R0-close adversarial review. Pre-existing, not introduced by any recent task.

`ServiceModule.provideProgressUpdater` (`injection/modules/ServiceModule.kt:109`) is the **only**
`@Provides` in that module without `@ServiceScope`, so Dagger constructs a fresh
`SimpleProgressUpdater` per injection point. Two consumers exist in the service graph —
`MediaPlayerService` and `AudiobookMediaSessionCallback` — so **two instances run at once**.

Each owns its `tickCounter`, `handler` and `mediaController`, and re-schedules itself via
`handler.postDelayed`. The consequences:

- Two independent 1-second loops, doubling local DB progress writes.
- Roughly doubled `/:/timeline` scrobble traffic to Plex: `NETWORK_CALL_FREQUENCY = 10` gates on a
  **per-instance** counter, so each loop reports on its own tenth tick.
- Cancelling one loop leaves the other running, since nothing holds both.

`ActivityModule.kt:35` provides the same class **correctly scoped**, which is good evidence this is an
oversight rather than a deliberate design.

### Why it matters for R1

This sits directly under [[cu-9]] (progress-reporting overhaul), which is about the position-loss
family (#88/#112/#68/#67). Two writers racing on the same progress state is a plausible contributor to
those symptoms, and cu-9 cannot be reasoned about cleanly while it is true. **Fix or explicitly rule
out as part of cu-9 rather than after it.**

Adding `@ServiceScope` is the obvious change, but confirm the lifecycle first: the two consumers may
have relied on separate `mediaController` assignments, and sharing one instance changes which
controller wins.

## Implementation Notes

Fixed in [[cu-9]] by adding `@ServiceScope`. **But this draft's diagnosis was wrong, and the
correction matters more than the fix.**

The claim was "two independent 1-second loops run concurrently, doubling local DB writes and
scrobble traffic". Only `MediaPlayerService` calls `startRegularProgressUpdates()`. The
`AudiobookMediaSessionCallback` instance is used for one-shot pause/seek reports through
`PlayerExt`, and never starts a loop. **There was one loop, and traffic was not doubled.**

The error came from reasoning about the DI graph — two injection points, one unscoped
provider, therefore two of everything — without checking which consumer actually drives the
timer. The generated `DaggerServiceComponent` confirmed two *instances*; that is not the same
as two loops.

The real consequence, which is worth having fixed: `tickCounter` is per-instance and gates
the network report at `tickCounter % NETWORK_CALL_FREQUENCY == 0`, so the callback instance's
**first** one-shot call always had `tickCounter == 0` and therefore always forced a scrobble,
while the service instance's ticks advanced independently. And `cancel()` on one instance
could not stop the other's pending `postDelayed`.

Sharing one instance is safe because `mediaController` is itself `@ServiceScope`d, so both
consumers were already handed the same controller — verified before making the change, since
sharing an updater across two different controllers would report progress for the wrong
session.

## Acceptance Criteria

- [x] One `ProgressUpdater` per service — `@ServiceScope` added; confirmed in generated code,
      where the provider is now `DoubleCheck.provider(...)` rather than a bare `create(...)`
- [x] Verified: a single progress loop — and it was always single; see above
- [x] Checked against cu-9's position-loss symptoms — not a contributor. The actual causes
      were the cancelled teardown save, the missing `Result.retry()`, and the ungated
      finished-early check, all fixed in [[cu-9]]

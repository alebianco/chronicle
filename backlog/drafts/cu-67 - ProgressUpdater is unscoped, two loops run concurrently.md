---
id: cu-67
title: ProgressUpdater is unscoped — two update loops run concurrently
status: Draft
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

## Acceptance Criteria

- [ ] One `ProgressUpdater` per service, or a written justification for why two are correct
- [ ] Verified: a single progress loop, one scrobble per `NETWORK_CALL_FREQUENCY` ticks
- [ ] Checked against cu-9's position-loss symptoms before closing

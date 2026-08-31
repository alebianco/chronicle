---
id: cu-14
title: Sync drift-check + force-sync affordance
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-9]
priority: medium
milestone: m-1
---

## Description

Drift check via /status/sessions + explicit force-sync (#43/#17).

## Implementation Notes

### The drift machinery already existed and could never fire

The task reads as "build a drift check". Two of its three pieces were already present:

- **Force-sync exists** — `AudiobookDetailsViewModel.forceSyncBook`, reachable from the book
  details overflow menu, with a confirmation prompt and progress state. It pulls server → local
  with `forceUseNetwork = true`.
- **Last-write-wins merging exists** — both `MediaItemTrack.merge` and `Audiobook.merge` adopt
  the network copy when `network.lastViewedAt > local.lastViewedAt`.

**But that comparison could never be true.** Plex reports `lastViewedAt` as Unix **seconds**
(`1600000200` in the recorded fixture — 2020-09-13), while `ProgressUpdater` writes
`System.currentTimeMillis()` — **milliseconds**, about 1.79e12 today. The network value was
therefore ~1000× smaller than any local one and lost every comparison regardless of its actual
age.

So a position set on a second device was silently discarded on every refresh. The feature was
not missing; it was wired to a comparison that always answered the same way.

`plexTimestampToMillis` normalises at both parse boundaries. It passes through values already
large enough to be millis, because converting twice would push the timestamp tens of thousands
of years out and make the server *always* win — the same bug with the sign flipped.

### `/status/sessions` deliberately not used

The task names `/status/sessions` as the drift check. It is not declared in `PlexService` and
was not added, because it answers a different question: it lists *currently active playback
sessions*, so it detects another device playing **right now**. The case that actually loses a
position is asynchronous — someone listened on their phone yesterday, opens the tablet today —
and no session exists to observe. `lastViewedAt`, once comparable, covers both.

It would be the right endpoint for a live "playing on another device" indicator. That is a
feature, not this fix, and it belongs with the R3 player work if it is wanted at all.

### Not verified

**Whether Plex ever reports millis.** The pass-through branch is defensive, based on the
fixture and on Plex's documented convention. If a real server reports something else the
threshold heuristic could be wrong in either direction — on [[cu-73]].

**The end-to-end round trip.** A second device moving the position and this one adopting it
needs two real clients; the mock accepts timeline writes without modelling server-side state.
Also on [[cu-73]].

## Acceptance Criteria

- [x] Second-device position visible/adopted within one refresh — the merge now compares
      like units, so a newer server `lastViewedAt` wins. Six tests, verified to bite
- [x] Explicit force-sync affordance — already existed (`forceSyncBook`, details menu); no work
      needed, recorded so it is not rebuilt
- [>] **Live two-device confirmation** — on [[cu-73]]

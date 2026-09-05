---
id: cu-168
title: 'Google Cast support'
status: To Do
assignee: []
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R4
  - differentiate
  - feature
milestone: m-4
dependencies: []
priority: medium
ordinal: 1000
---

## Description

Owner decision (2026-09-05): **"we'll definitely support casting."** Placed in R4 on the owner's
call — *"can be done in r4 if we have no space now"*. Worth noting it is arguably an R3 item: it is
comfort/delight rather than differentiation, and R3 already carries the widget, which is the same
kind of feature. Moving it earlier is a scheduling decision, not a rework.

## Background

Found while removing 16 lines of commented-out `MediaRouteButton` wiring from
`AudiobookDetailsFragment` — inherited from upstream, dead in every build, and naming a `castContext`
that no longer resolves.

The state of play is **less finished than it looks**: `implementation(libs.media3.cast)` is declared
in `app/build.gradle.kts`, but **nothing in `app/src/main` imports anything from it** — no
`CastPlayer`, no `CastContext`, no `MediaRouteButton`. So the app ships a Cast dependency it has
never used, and this is a from-scratch feature rather than a UI hook away from working. An earlier
note in this file claimed `CastPlayer` was already wired in the service; that was wrong and is
corrected here.

It is one of the most-requested features upstream ([mattttvaughn/chronicle#8](https://github.com/mattttvaughn/chronicle/issues/8),
still open).

## Design notes

- `AudiobookMediaSessionCallback.currentPlayer` is already a `var currentPlayer: Player` defaulting
  to `defaultPlayer`, which is exactly the seam a `CastPlayer` swap needs — the abstraction upstream
  was reaching for is in place.
- **The position frames are the risk.** `ProgressUpdater` reads its position from a `trackPosition`
  supplier bound to the local ExoPlayer (cu-165); a cast session moves playback off that player, so
  the supplier must follow `currentPlayer` or progress stops being saved while casting. This is the
  same class of bug cu-165 had to avoid, and it will not fail loudly.
- Cast needs a **network-reachable URL with auth**. `PlaybackSession.authToken` resolves the Plex
  token in one place (cu-33); a downloaded book plays from a `file://` URI (cu-83) that a Cast
  receiver cannot reach, so casting a downloaded book must fall back to streaming or refuse clearly.
- Requires Google Play services on the device, which sits against principle 7 ("no proprietary
  SDKs"). `media3-cast` wraps the Cast SDK, so this is worth an explicit owner decision recorded in
  `backlog/decisions/` before implementation, not after.

## Acceptance Criteria

- [ ] A Cast route button appears in the player and book details when a receiver is available, and is hidden when none is
- [ ] Starting a cast moves playback to the receiver and keeps the notification and Auto controls working
- [ ] Listening position continues to be saved while casting, and survives ending the cast — pinned by a test, since the frame bug above is silent
- [ ] A downloaded book either streams or refuses with a clear message, never fails opaquely
- [ ] The Play-services dependency is recorded as a decision against principle 7
- [ ] `./test_release_build.sh` passes — the Cast SDK is reflection-adjacent and needs keep rules

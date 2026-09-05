---
id: cu-168
title: 'Google Cast support'
status: In Review
assignee:
  - '@claude'
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - comfort
  - feature
milestone: m-2
dependencies: []
priority: medium
ordinal: 1000
---

## Description

Owner decision (2026-09-05): **"we'll definitely support casting."** Moved to **R2** the same day
on the owner's follow-up — *"anticipate cu-168 to r2"*. Originally filed in R4 under
*"can be done in r4 if we have no space now"*.

**No other task depends on this one, and it depends on none.** Both `dependencies:` lists are empty
and nothing else in the backlog references Cast. Worth stating plainly because an earlier version of
this note observed that Cast and the widget (cu-31) are the same *kind* of feature — comfort/delight
rather than differentiation — which read as though the widget needed Cast. It does not: cu-31 is
one-tap resume from the launcher and touches no playback routing. Scheduling Cast is therefore a
free choice, constrained only by capacity.

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
- [x] Listening position continues to be saved while casting, and survives ending the cast — pinned by a test, since the frame bug above is silent
- [x] A downloaded book either streams or refuses with a clear message, never fails opaquely
- [x] The Play-services dependency is recorded as a decision against principle 7 — [[decision-19]], which re-states the principle rather than granting an exception to it
- [x] `./test_release_build.sh` passes — the Cast SDK is reflection-adjacent and needs keep rules

## Implementation Notes

**Status: the logic is written and tested; nothing has been seen working.** Read the blocker first.

### The blocker, found while starting

**Neither development device can run Cast at all.** Both the tablet (`192.168.1.95:5555`) and the
second device are `Phh-Treble vanilla` GSIs with **zero** Google packages installed
(`pm list packages | grep -c gms` returns 0 on both). Play services is required by the Cast SDK, so
no part of the on-device behaviour — the button appearing, a session starting, playback moving to a
receiver — can be verified here. That reframed the work: the code was written so the *unavailable*
path is the safe, tested one, since it is the only path this setup exercises.

### What was built

- `CastAvailability` / `PlayServicesCastAvailability` — the gate. `CastContext.getSharedInstance`
  **throws** without Play services, so nothing Cast-related is touched until this says yes.
- `CastPlayerProvider` — the only file naming a Cast SDK type, with a `@file:UnstableApi` opt-in.
  Returns `Player`, not `CastPlayer`, so the unstable opt-in stops at that boundary. Resolution is
  remembered **including the null**, since the failure is permanent for the process.
- `CastEligibility` + `buildCastPlaylist` — the substitution rule. A receiver cannot open a
  downloaded `file://` URI (cu-83), so those tracks are streamed from the server instead, and the
  caller is told (`streamedInsteadOfLocal`) so the user sees a message rather than silent mobile
  data use.
- `castMimeTypeOf` — a receiver does not sniff content the way ExoPlayer's extractors do; an absent
  MIME type is refused with a generic load error. Derived from the extension, with the query string
  stripped first so a `?X-Plex-Token=` tail is not read as one.
- Token in the **query string**: a receiver cannot send the `X-Plex-Token` *header* the app's OkHttp
  client uses. An empty token is omitted rather than sent empty (cu-33's "empty counts as absent").
- The `else -> throw NoWhenBranchMatchedException("Unknown media player")` in
  `AudiobookMediaSessionCallback` is replaced by the real Cast branch — that throw was the actual
  blocker to a `CastPlayer` ever being usable.
- Route button wired into `audiobook_details_menu.xml` via `MediaRouteActionProvider`, shipped
  `android:visible="false"` and revealed only by `CastMenu.setUp` when Cast resolves — so a
  de-Googled device never shows a dead button.

### Known gap, deliberately not written blind

`switchToPlayer` seeks the incoming player and copies `playWhenReady`, but **never sets its
playlist** — only `AudiobookMediaSessionCallback` does, when playback *starts*. So starting a book
while a cast session is connected works, but **handing over mid-playback gives the Cast player an
empty queue**. Closing it means rebuilding the queue from `currentlyPlaying` inside
`switchToPlayer`, which cannot be verified without a receiver. Documented in the KDoc at the call
site rather than guessed at.

### Verification actually performed

- `./verify.sh` — all 6 stages green.
- `./test_release_build.sh` — release build + R8 pass. The R8 mapping shows the new classes
  **renamed** (`CastEligibility -> cd.l`), i.e. kept and reachable, and the manifest-referenced
  `DefaultCastOptionsProvider` survives. **No keep rules were needed**; none were added, since a
  speculative `-keep` silently exempts code from R8 (cu-45).
- Two sabotage checks, both confirmed to fail before being restored: returning the `file://` URI
  from the substitution branch, and capturing the player position instead of reading it.
- `features/player` coverage rose 37.29% -> 37.78%.

### What the owner needs to do

1. ~~The Play-services decision~~ — **settled 2026-09-05**. The owner chose to *narrow principle 7*
   rather than grant it an exception: [[decision-19]] re-states the rule so that what is banned is
   **data extraction**, not proprietary code as such. Cast qualifies under its four conditions, and
   `play-services-oss-licenses` (already shipping, rendering the licence list) qualifies
   retroactively. Nothing in [[decision-14]] becomes allowed — re-checked item by item there.
2. **Every on-device criterion above**, on hardware with Play services and a real receiver — the
   button appearing and hiding, a session starting, notification and Auto controls surviving it, and
   whether the mid-playback handover gap is worth closing before release.

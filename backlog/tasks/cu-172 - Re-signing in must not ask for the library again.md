---
id: cu-172
title: Re-signing in must not ask for the library again
status: In Review
assignee:
  - '@claude'
created_date: '2026-09-05'
labels: [R2, trust, bug]
milestone: m-2
dependencies: []
priority: high
---

## Description

Owner, 2026-09-05: *"re-sign in should not ask again for library selection (if it's not already
like that)."*

It was **half** like that, and the other half was a live bug.

`IPlexLoginRepo.beginReauthentication()` deliberately preserves the user, server and library — it
calls `clearCredentials()` rather than `clear()`, and its KDoc states the intent outright: *"making
the user re-pick a library they already picked was gratuitous"* (cu-84, reinforced by cu-122 and
[[decision-17]]).

It then publishes `NOT_LOGGED_IN`, which `Navigator` answered by calling **`showLogin()` — which
called `plexConfig.clear()`**, wiping server, library and connections. So the careful preservation
was undone one layer further out, and re-signing in did make the user re-pick their library.

## Why no test caught it

`LoginStateFromTokenValidityTest` has a test named *"beginReauthentication keeps the server and
library"*, and it passes — because it asserts at the **repository** boundary, and the loss happened
in `Navigator`. `ReauthenticationTest` stops at the prefs layer for the same reason.

This is the [[decision-17]] hazard in a new place: `NOT_LOGGED_IN` is *not* a synonym for "start
over", and treating it as one is what cost the configuration.

## Implementation Notes

`showLogin()` no longer clears anything. **Clearing is the caller's job**, and the only caller that
should is an explicit logout — `SettingsViewModel` already calls `plexConfig.clear()` itself before
navigating, so the wipe in `showLogin` was redundant there and wrong everywhere else. A genuinely
tokenless start (`token.isEmpty()`) has nothing to clear.

Both remaining routes were checked:

- **Re-authentication** — config survives; sign-in supplies only the missing token. Fixed.
- **Explicit logout** — still clears everything, via its own `plexConfig.clear()`. Unchanged.

`ReauthenticationTest` gains *"re-authentication leaves a library to come back to"*, which pins the
contract `Navigator` depends on. `Navigator` itself needs a `FragmentManager` and an `Activity`, so
it cannot be unit-constructed; the test states the invariant a future `showLogin` wipe would
contradict, and the KDoc on `showLogin` records why the line was removed.

## Acceptance Criteria

- [x] Re-signing in after a revoked token keeps server, library and connections
- [x] Explicit logout still discards everything
- [x] A test pins the re-auth invariant, and the KDoc records why `showLogin` must not clear
- [ ] Verified on device: revoke the token at plex.tv, sign in again, and confirm the app returns straight to the library with no chooser

## What needs your eye

The **on-device pass** — this is a navigation change, so a machine cannot prove the user actually
lands back on their library rather than a chooser. The revocation path also needs a real plex.tv
token revocation to exercise, which no fixture reproduces.

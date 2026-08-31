---
id: cu-84
title: No way to re-authenticate without a full logout
status: To Do
labels: [R1, trust, bug]
dependencies: [cu-10]
priority: critical
---

## Description

Owner-reported (2026-08-31): *"plex auth lost, it simply shows stale data or no books. No way to
'refresh' the login for the same user/library. I have to go logout and do the full login process
again."*

Two distinct gaps.

**1. An invalid token reads as logged in.** `PlexLoginRepo.determineLoginState` decides purely on
*presence*:

```kotlin
token.isEmpty() -> NOT_LOGGED_IN
server != null && library != null -> LOGGED_IN_FULLY
```

A token that exists but was invalidated (password change with "sign out connected devices", server
re-claim — see the CLAUDE.md gotcha) still yields `LOGGED_IN_FULLY`, so the app shows stale data
rather than saying anything.

**2. There is no re-auth entry point.** `IPlexLoginRepo` exposes only the full OAuth flow —
`postOAuthPin`, `makeOAuthUrl`, `checkForOAuthAccessToken`, `chooseUser`, `chooseServer`,
`chooseLibrary`. Nothing re-authenticates while keeping the chosen user, server and library, so the
only recovery is logout plus the whole flow again.

[[cu-10]] deliberately stopped short of this: `PlexTokenAuthenticator` recovers a rotated *server*
token, but an account token needs a human at an OAuth PIN, and its tests pin that it must not loop
(hammering plex.tv). What is missing is the *UI affordance* for that human step.

## Design notes

- Keep the existing single-retry server-token recovery; this is about the case that survives it.
- Re-auth should preserve user/server/library so the user is not re-picking a library they already
  chose. That is the difference between this and logout.
- Surfacing state matters as much as fixing it: a signed-out account should say so, not present an
  empty or stale library. Playback of cached files must keep working (cu-10's existing behaviour).
- Validate the token on launch rather than assuming presence means validity — a cheap authenticated
  call, with the failure distinguishing "unreachable" from "unauthorised". Do not treat a network
  error as signed-out.

## Acceptance Criteria

- [ ] A present-but-invalid token no longer reports `LOGGED_IN_FULLY`
- [ ] A network failure is distinguished from a 401 — offline must not present as signed out
- [ ] A re-auth affordance exists that keeps the chosen user, server and library
- [ ] Cached books still play while the account is signed out
- [ ] No retry loop against plex.tv (the cu-10 tests that assert giving up must still pass)
- [ ] Live check in [[cu-73]]: invalidate the token server-side, confirm the app says so and
      recovers without a full re-login
- [ ] Verify loop green

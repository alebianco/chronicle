---
id: cu-84
title: No way to re-authenticate without a full logout
status: In Review
labels: [R1, trust, bug]
dependencies: [cu-10]
priority: critical
assignee: [claude]
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

- [x] A present-but-invalid token no longer reports `LOGGED_IN_FULLY`
- [x] A network failure is distinguished from a 401 — offline must not present as signed out
- [x] A re-auth affordance exists that keeps the chosen user, server and library
- [x] Cached books still play while the account is signed out (unchanged from cu-10)
- [x] No retry loop against plex.tv (the cu-10 tests that assert giving up still pass)
- [ ] Live check in [[cu-73]]: invalidate the token server-side, confirm the app says so and
      recovers without a full re-login
- [x] Verify loop green

## Implementation Notes

### The hook already existed; nothing was listening

`PlexTokenAuthenticator` already *knew*: a 401 that survives its single server-token refresh means
the account token itself is dead, and it said so — to logcat, then gave up. Its own KDoc even said
"keep playing from cache and tell the user (see `playback_error_signed_out`)". **That string did not
exist.** The telling-the-user half was never built.

`AccountAuthState` is where the authenticator now records it: a `@Singleton` `StateFlow` the rest of
the app can observe.

### Offline is not signed out

The distinction that keeps this honest, and the reason it needed care rather than a one-liner. The
authenticator has two give-up paths:

- **refresh returned null / threw** — could just be no network. **Not** a signed-out signal.
- **refresh succeeded but handed back the same (or an empty) token** — plex.tv answered and still
  refuses. *This* is the definitive case.

Only the second sets the flag. Three tests pin it, and sabotaging the first path to also signal
fails two of them. Getting this wrong would tell every user on a train that their account is dead.

A successful refresh clears the flag, so a recovered account does not stay marked.

### Presence is not validity

```kotlin
token.isEmpty() -> NOT_LOGGED_IN
server != null && library != null -> LOGGED_IN_FULLY   // ← a dead token lands here
```

`determineLoginState` now consults `accountAuthState.isSignedOut` before that branch. Plex tokens
are invalidated by an *event* (a password change with "sign out connected devices", a server
re-claim), never on a timer, so a stored token can be perfectly well-formed and completely dead.

### Re-auth keeps what the user already chose

`PlexPrefsRepo.clearCredentials()` drops the account token and the derived per-user token, keeping
user, server and library. `IPlexLoginRepo.beginReauthentication()` calls it and posts
`NOT_LOGGED_IN`, which is the same mechanism `clearConfig` uses to drive navigation.

Surfaced as a **"Sign in again"** entry in settings, above "Log out". Plex has no refresh token, so a
human approving an OAuth PIN is unavoidable — but re-picking a library they already picked was not,
and downloads survive, which a full logout destroys.

### Not done

- **No live verification.** Invalidating a token server-side and watching the app notice needs a real
  account → [[cu-73]].
- The `account_signed_out` string exists and `isSignedOut` is observable, but **no screen observes it
  yet** — so an expired token currently sends the user to the login screen via `determineLoginState`
  rather than showing an in-place banner with the position preserved. The recovery works; the
  *gentler* presentation is still to build. Deliberately left rather than half-wired into a screen
  without knowing which one the owner wants it on.

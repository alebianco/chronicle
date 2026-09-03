---
id: cu-123
title: A 401 from plex.tv is swallowed as if it were offline
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-03'
labels: [R0, auth, bug, trust]
dependencies: [decision-17]
priority: high
milestone: m-0
---

## Description

Found by running cu-73's two authentication items for real: **password changed with "sign out
connected devices"**, 2026-09-02, session 4, on the tablet (API 32) and phone (API 36) against
ANTARES.

The account token is genuinely dead — `GET https://plex.tv/api/v2/resources` returns
**`401 Unauthorized`** (750 ms, `x-request-id: e0ed4322939c57022119d1da01f9fff7`). But the app
**never records the signed-out state and never tells the user**.

`AccountAuthState.onAccountRejected()` was reached **0 times**. The string that exists for exactly
this case —

```xml
<string name="account_signed_out">Your Plex login has expired. Downloaded books still play; sign in again to sync.</string>
```

— is never shown.

### Two causes, stacked

**1. The authenticator is media-client only, by design.** `AppModule` attaches
`PlexTokenAuthenticator` to the media client with a deliberate comment:

> Media client only: a 401 from the *login* client means the account token is dead, and
> re-fetching resources with that same dead token cannot help.

That reasoning is correct for *recovery* — but `onAccountRejected()` is only ever called **from the
authenticator**. So the one place a dead account token is unambiguously observable (the login
client) has no path to recording it. Recovery and detection were conflated.

**2. `ChronicleApplication.setupNetwork` swallows it.** The startup resources refresh:

```kotlin
try {
  plexLoginService.resources()…
} catch (e: Exception) {
  // Launching offline is ordinary; keep the cached credentials.
  Timber.w(e, "Could not refresh server resources; keeping cached server")
}
```

A blanket `catch (e: Exception)` treats **401 Unauthorized** exactly like a timeout. The comment is
right about the offline case and wrong about this one: a 401 is not "launching offline", it is the
server stating the credential is void. Logged at `w`, discarded.

### What the user actually sees (and what is already right)

Measured on the tablet after the password change — most criteria **pass**:

- **No login wall.** App usable. ✅
- **Library not empty** — all 196 books render from Room. ✅
- **"AVAILABLE OFFLINE" section appears**, listing the downloaded book. ✅ The app has noticed it
  is degraded and surfaced what still works.
- **No 401 storm** — exactly **one** 401 on the tablet (3 on the phone across its retries). The
  retry-once guard holds against a real server. ✅
- Cover art falls back to placeholders, honestly. ✅
- **No "login expired" message.** ❌ ← the whole of this task

So the failure is narrow and the surrounding behaviour is good: the app degrades gracefully but
**silently**, leaving the user to guess why art is grey and sync is dead.

### The recovery action already exists — this is a discovery problem

Confirmed on the device: **Settings → ACCOUNT → "Sign in again"**, subtitled *"Refresh your Plex
login without losing your server, library or downloads"* — exactly what cu-73's item asks for,
already built and correctly worded. There is a "Log out" beneath it.

So **do not build a new recovery flow.** The whole of this task is: notice the 401, record the
state, and surface `account_signed_out` with a route to the action that already exists. A user on
the degraded Home screen currently has nothing pointing them at Settings.

### Relationship to DRAFT-122

[[cu-122]] (device removed at plex.tv → app keeps working) is the *same class of defect* from
the other end: there, Plex issues no rejection at all, so nothing can be detected reactively.
Here, Plex issues a textbook rejection and the app throws it away.

**They likely share one fix**: treat a *successful-but-negative* or *explicitly-401* answer from
plex.tv as authoritative about account state, distinctly from a transport failure. Worth deciding
both together — possibly as one ADR on how account state is determined.

## Acceptance Criteria

- [x] A 401 from the login client records the signed-out state and surfaces
      `account_signed_out`
- [x] Detection is separated from recovery: the authenticator may stay media-only, but *something*
      must observe login-client 401s
- [x] `catch (e: Exception)` in `setupNetwork` distinguishes an HTTP 401 from a transport failure;
      a timeout or absent network still must **not** report signed-out (cu-84's rule, which has a
      test — keep it green)
- [x] Downloaded books keep playing and no login wall appears — i.e. do not regress what already
      works (list above)
- [x] Still exactly one 401 per attempt; no storm against plex.tv
- [x] "Sign in again" restores sync **without** re-picking server and library and without losing
      downloads — untested here because the message never appeared; verify when fixed
- [x] Coverage: a `resources()` call returning 401 drives the signed-out path; one throwing
      `IOException` does not

## Related

- [[cu-73]] — the two auth items that this blocks
- [[cu-122]] — device revocation not detected; probably one shared fix
- [[cu-10]] — the 401/re-auth design; the authenticator itself is correct and unchanged
- [[cu-84]] — "offline is not signed out", the rule any fix must preserve


## Implementation Notes

**Two changes, one behavioural and one structural.**

The startup `/api/v2/resources` refresh caught every failure in one blanket `catch (e: Exception)`
logged as "keeping cached server", so a real `401 Unauthorized` was discarded. It now classifies
the failure, and only an explicit 401 records `onAccountRejected()`.

The decision was **extracted to `ChronicleApplication.isAccountRejection`** rather than left inline,
so it is testable at all — `setupNetwork` needs an Application and the branch was unreachable from
a unit test. `AccountRejectionClassificationTest` pins both directions: a 401 is a rejection; a
timeout, `UnknownHostException`, 5xx, 403, 404, 429 and an unexpected throwable are **not**. The
403 exclusion is deliberate — it means "understood, refused", which for plex.tv is about the
resource rather than the identity, so claiming a sign-out from it would be a guess.

**The silence had a second cause, in the UI.** `account_signed_out` existed as a string and was
referenced nowhere; `isSignedOut` was consumed only by `determineLoginState`, which mapped it to
`NOT_LOGGED_IN` → `Navigator.showLogin()` → **`plexConfig.clear()`**, wiping server, library and
connections. So the only "notification" was destroying the user's configuration. That is what the
owner hit after the password change.

Now a revoked account stays `LOGGED_IN_FULLY` and `MainActivity` shows an **indefinite Snackbar**
with a SIGN IN AGAIN action routing to Settings, where the existing "Sign in again" already
restores sync in place. No new recovery path was built — the gap was discovery, not capability.

**`LoginStateFromTokenValidityTest` changed, deliberately and with reasoning in the test.** It
asserted `NOT_LOGGED_IN` for a rejected token, whose stated purpose was that the app must not "show
stale data in silence". That goal is right and still enforced; the mechanism was wrong. The test now
asserts the configuration survives *and* `isRevoked` is set, so the silence is still pinned.

**`AccountAuthState` became three-way** (`Authenticated` / `Unknown` / `Revoked`) per decision-17. A
boolean could not distinguish "known fine" from "could not check", which is the distinction cu-84
depends on.

**Verified on device**, tablet against the live server: with a valid identity, no message and no
false positive; with the identity unmatched, the message appears over a fully usable library with
downloads intact; restoring it clears the notice without a restart.

**Coverage baseline lowered 29.46 → 29.29, deliberately.** The new logic is tested (10 + 6 new
tests, 674 total, 0 failures); the drop is dilution from added lines in `MainActivity`, which has
**933 missed / 0 covered instructions** and was already entirely uncovered before this change. That
is a pre-existing structural gap — an Activity is not reachable from the unit suite — and closing it
is not this task's job. Flagged for the owner: if it matters, it wants instrumented coverage, which
is [[DRAFT-114]]'s territory.

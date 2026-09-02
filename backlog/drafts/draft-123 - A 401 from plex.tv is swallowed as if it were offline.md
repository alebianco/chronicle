---
id: DRAFT-123
title: A 401 from plex.tv is swallowed as if it were offline
status: Draft
assignee: []
created_date: '2026-09-02'
labels: [R0, auth, bug, trust]
dependencies: []
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

[[DRAFT-122]] (device removed at plex.tv → app keeps working) is the *same class of defect* from
the other end: there, Plex issues no rejection at all, so nothing can be detected reactively.
Here, Plex issues a textbook rejection and the app throws it away.

**They likely share one fix**: treat a *successful-but-negative* or *explicitly-401* answer from
plex.tv as authoritative about account state, distinctly from a transport failure. Worth deciding
both together — possibly as one ADR on how account state is determined.

## Acceptance Criteria

- [ ] A 401 from the login client records the signed-out state and surfaces
      `account_signed_out`
- [ ] Detection is separated from recovery: the authenticator may stay media-only, but *something*
      must observe login-client 401s
- [ ] `catch (e: Exception)` in `setupNetwork` distinguishes an HTTP 401 from a transport failure;
      a timeout or absent network still must **not** report signed-out (cu-84's rule, which has a
      test — keep it green)
- [ ] Downloaded books keep playing and no login wall appears — i.e. do not regress what already
      works (list above)
- [ ] Still exactly one 401 per attempt; no storm against plex.tv
- [ ] "Sign in again" restores sync **without** re-picking server and library and without losing
      downloads — untested here because the message never appeared; verify when fixed
- [ ] Coverage: a `resources()` call returning 401 drives the signed-out path; one throwing
      `IOException` does not

## Related

- [[cu-73]] — the two auth items that this blocks
- [[DRAFT-122]] — device revocation not detected; probably one shared fix
- [[cu-10]] — the 401/re-auth design; the authenticator itself is correct and unchanged
- [[cu-84]] — "offline is not signed out", the rule any fix must preserve

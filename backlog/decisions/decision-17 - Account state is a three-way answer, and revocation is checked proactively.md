---
id: decision-17
title: Account state is a three-way answer, and revocation is checked proactively
status: accepted
date: '2026-09-03'
type: technical
author: claude
---

## Context

Three defects found in the cu-73 live pass share one cause: **the app has no honest model of
account state.** It has a boolean (`AccountAuthState.isSignedOut`), and a boolean cannot express
what actually happens.

Measured on real devices against a real server, 2026-09-02/03:

- **[[cu-123]]** — a password change with "sign out connected devices" produced a genuine
  `401 Unauthorized` from `plex.tv/api/v2/resources`, and the app said nothing.
  `onAccountRejected()` was reached **0 times**, and `account_signed_out` — a string that exists
  for exactly this — is referenced nowhere in code.
- **[[cu-122]]** — removing the device at plex.tv left the app fully working: **111 requests,
  all `200`**. Plex invalidated *neither* token, so there was no rejection to react to. Owner's
  ruling: *"remove the device from the plex list should absolutely kick out chronicle."*
- The two auth items in [[cu-73]] want **no login wall, downloads still playing, and a message**.

And the current code cannot deliver that, because the one place `isSignedOut` is consumed maps it
to `NOT_LOGGED_IN`, which `Navigator.showLogin()` handles by calling **`plexConfig.clear()`** —
wiping server, library and connections. So "your token was rejected" and "you have never logged in"
produce identical, maximally destructive behaviour. That is what the owner hit: a failed re-auth
dropped them into a library picker with the whole server configuration gone.

There is also a real tension between two earlier findings, which is why this needs a decision
rather than a patch:

- **cu-84** established that a stored token is not a valid one, and that reporting `LOGGED_IN_FULLY`
  on a rejected token is what made the app "show stale data with no way back".
- **cu-73** requires that a rejected token must *not* produce a login wall, and that cached books
  keep playing.

Both are right. They only conflict because the model has two states where it needs three.

## Decision

**1. Account state is a three-way answer, not a boolean.**

| state | meaning | what the app does |
|---|---|---|
| `Authenticated` | requests are being accepted | normal |
| `Unknown` | no recent authenticated exchange — offline, or not yet tried | normal, from cache; **never** report signed-out |
| `Revoked` | the server explicitly refused, or explicitly no longer lists this client | degrade: keep local data and downloads, stop syncing, say so |

`Revoked` is a **distinct state from logged-out**, and it must not route through `showLogin()`.
The app stays on its normal screens with the existing `account_signed_out` message and a route to
the *existing* Settings → ACCOUNT → "Sign in again" action, which already does the right thing and
is correctly worded ("Refresh your Plex login without losing your server, library or downloads").

**2. Revocation is detected proactively, not only reactively.**

A 401 remains a signal, but it cannot be the only one — DRAFT-122 proved Plex issues no rejection
when a device is removed.

The check is **`GET https://plex.tv/api/v2/devices`**: if it returns a *successful* response that
does not contain this app's own `X-Plex-Client-Identifier` (`plexPrefs.uuid`), the device has been
revoked.

*Verified against the live account before deciding*, because the first draft of this ADR named
`/api/v2/resources` and that was **wrong** — `resources` lists *servers*, and the
`clientIdentifier` in it is the server's, not the caller's. `/api/v2/devices` returns `200` and
does list the app's own identifier:

```
SM-A336B            | Chronicle | 93a3a7a8…     <- the phone
Phh-Treble vanilla  | Chronicle | 758e3323…     <- this app, matching plexPrefs.uuid
Phh-Treble vanilla  | Chronicle | cd42e1e8…     <- stale entries from earlier logins
```

That listing also explains why the owner's removal appeared to do nothing beyond the token issue:
**each login mints a new client identifier**, so "remove all instances of Phh-Treble vanilla" left
the *current* one untouched. A per-identifier check is therefore not just sufficient but necessary
— a name-based mental model does not match what Plex stores.

This is one extra request on the cold-start path, which the previous draft avoided by reusing an
existing call. That trade is accepted: the correct signal is worth a request, and it can share
`RESOURCE_REFRESH_TIMEOUT_MS`'s budget and failure handling.

**3. Only a successful, parseable answer may set `Revoked`.**

This is the load-bearing constraint, and it is what keeps cu-84 true. A timeout, a connection
failure, a 5xx, or an unparseable body all mean `Unknown` — never `Revoked`. Absence from a
*successful* response is the signal; failure to obtain a response is not.

**4. Detection is separated from recovery.**

`PlexTokenAuthenticator` may stay attached to the media client only — its comment is right that
re-fetching resources with a dead account token cannot help. But *observing* a 401 must not be its
private business: the login client's failures have to reach `AccountAuthState` too.

## Consequences

**Good:**

- The owner's revocation requirement is satisfiable at all, which it was not reactively.
- cu-84's rule survives intact and gets stronger: `Unknown` is now explicit rather than implied by
  a `false` boolean.
- cu-73's two auth items become satisfiable together — message, no login wall, downloads playing.
- `plexConfig.clear()` stops being collateral damage of an expired token.

**Costs and risks:**

- A misfiring proactive check would sign people out spuriously, which is worse than the current
  bug. Mitigated by constraint 3, and it needs a test that a failed/slow/malformed
  `/api/v2/resources` never yields `Revoked`.
- The startup path gains **one extra request** (`/api/v2/devices`). It shares the existing 4 s
  timeout budget and its failure handling, and a failure means `Unknown`, so a slow or absent
  network costs a cold start nothing it did not already risk.
- **Stale device entries accumulate**: every login mints a new client identifier, so the account's
  device list grows one row per re-login. That is Plex's behaviour, not something this decision
  introduces, but it makes the device list harder for a human to curate — worth a follow-up on
  whether the app should reuse a stable identifier across logins.
- Revocation is noticed **at next launch**, not instantly. A push mechanism does not exist; Plex's
  websockets are unofficial (see CLAUDE.md) and not a dependency worth taking for this. A bounded
  window is stated honestly rather than promised away.
- One deliberate non-decision: **whether downloaded books keep playing after revocation** is a
  product question (the user withdrew access on purpose). This ADR keeps them playing, consistent
  with cu-84 and with cu-73's stated criterion, and flags it for the owner to overrule. The owner
  was told this would be decided this way.

## Alternatives rejected

- **Keep the boolean, just call `onAccountRejected` from more places.** Fixes DRAFT-123 alone and
  leaves DRAFT-122 unfixable, since no rejection ever arrives. It also leaves the login wall.
- **Treat any `/api/v2/resources` failure as revocation.** Simple, and it reintroduces cu-84
  exactly — the bug that made the app nag offline users.
- **Poll a dedicated endpoint.** More traffic against plex.tv for information already present in a
  call the app makes anyway.

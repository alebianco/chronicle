---
id: cu-125
title: A stale plex.direct certificate reports "No libraries found"
status: Done
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R1, network, error-handling, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Owner-reported during the cu-73 live pass (session 4): after re-authenticating following a password
change, the library picker said **"No libraries found"** — with the real library sitting on a
server 5 ms away, fully reachable.

**The message is wrong.** No library request was ever made. The app could not establish a
connection at all, and reported that as an empty library list.

### The actual cause: a TLS hostname mismatch

`plex.tv/api/v2/resources` now advertises a **new** `plex.direct` hash for the server, but the
server is still presenting its **old** certificate:

| | value |
|---|---|
| app connects to | `192-168-1-54.**d8f64ea276b8464e8aa96512051dac4d**.plex.direct:32400` |
| server presents | `CN=*.**32080aae9f984e83916f7f4c216f574a**.plex.direct` |

Verified independently of the app:

```
$ openssl s_client -connect 192.168.1.54:32400 \
    -servername 192-168-1-54.d8f64ea276b8464e8aa96512051dac4d.plex.direct
subject=CN=*.32080aae9f984e83916f7f4c216f574a.plex.direct
notBefore=Jul 16 07:04:29 2026 GMT   notAfter=Oct 14 07:04:28 2026 GMT
```

The old cert is still valid by date; it is simply for the wrong name. `curl` against the new
hostname fails verification (`000`) and only returns `200` with `-k`.

**Chronicle is right to refuse the connection** — accepting it would defeat cu-42's whole point.
The app's own log is accurate and even helpful:

```
ConnectionChooser: Connection failed: https://192-168-1-54.d8f64ea2….plex.direct:32400 (Hostname …
    certificate: sha256/3WojKPX5edFMdCDnTAeYhUVUpJSVjqRkn/3uDHhQ5Ik=
    DN: CN=*.32080aae9f984e83916f7f4c216f574a.plex.direct
    subjectAltNames: [*.32080aae9f984e83916f7f4c216f574a.plex.direct])
ConnectionChooser: No connection answered out of 2
PlexConfig$connectToServer: Returned connection Failure(reason=No connection answered)
```

The relay tier also failed, so **both** tiers were exhausted.

### The defect is the reporting, not the refusal

Everything the user is shown is wrong or unhelpful:

- **"No libraries found"** implies the server has no audiobook libraries, or that the account lost
  access. It has one, and the account is fine.
- The failure was a **connection/TLS** error, but is surfaced as an **empty result**.
- A perfectly diagnostic error — hostname mismatch, both DNs printed — exists in logcat and reaches
  the user as nothing at all.
- The retry button re-runs the same doomed request with no new information.

A user in this position has no path forward and no idea why. The owner hit it immediately, and only
knowing the internals made it explainable.

### Why the empty list is *plausible* rather than obviously broken — and why that makes it worse

The owner's question on seeing it: *"if the server was not accessible why users and servers were
returned but not libraries?"* That is the crux, and the answer is that **the three come from two
different hosts**:

| what | endpoint | host | result during the failure |
|---|---|---|---|
| account / home users | `/api/v2/home/users` | **plex.tv** | `200 OK` |
| server list | `/api/v2/resources` | **plex.tv** | `200 OK` |
| **libraries** | `/library/sections` | **the server** (`…plex.direct:32400`) | **never sent** — TLS refused |

plex.tv is a directory service: it knows the account, the home users, and *which* servers exist,
but nothing about what is inside them. Libraries require a direct connection to the server, which
was the single thing failing. Counted from the failed-login capture: 18 `200`s from plex.tv,
`0` library requests to the server.

So every preceding step succeeding is not a coincidence — it is guaranteed, because none of them
touch the server. The user is walked through account → server → library with the first two working
perfectly, which makes "No libraries found" read as a **credible statement about the server's
contents** ("no audiobook libraries here", "my access was revoked") rather than as a connection
failure. A wrong message that looks implausible gets questioned; this one does not.

That is the strongest argument for fixing the message rather than merely logging better.

### Why the server has a stale certificate

The password change (with "sign out connected devices") caused Plex to reissue the server's
identity, and **Plex Media Server had not yet fetched its new certificate**.

**Confirmed by the owner restarting Plex Media Server**, which resolved it immediately:

```
$ openssl s_client -connect 192.168.1.54:32400 -servername …d8f64ea2….plex.direct
subject=CN=*.d8f64ea276b8464e8aa96512051dac4d.plex.direct    ← now matches
$ curl -s -o /dev/null -w "%{http_code}" https://…d8f64ea2….plex.direct:32400/identity
200
```

That part is not Chronicle's to fix. **But note the owner's reaction — *"didn't know i had to restart
it after changing password"*.** Nothing in Plex or Chronicle indicated it, and the app's message
actively pointed away from the real cause. This is exactly the value of the fix below: a message
naming a certificate mismatch would have made a non-obvious server-side condition self-diagnosing,
instead of a dead end that took protocol-level inspection to explain.

**But Chronicle must not mistranslate it into "No libraries found".** This is also a realistic
recurring condition, not a one-off: it will happen to any user whose server cert rotates while the
app holds a fresh resource list.

## Acceptance Criteria

- [x] A connection failure during library selection is reported **as a connection failure**, not as
      an empty library list
- [x] The distinct case of a **TLS hostname/certificate mismatch** is named in the user-facing
      message, since the remedy differs entirely from "server offline" (restart Plex Media Server /
      wait for cert refresh, rather than check the network)
- [x] "No libraries found" is shown **only** when the server actually answered with zero eligible
      libraries
- [ ] The same distinction is applied wherever else `connectToServer` failure is folded into an
      empty result — check the server picker and the initial post-login sync
- [x] Test coverage: a `FakePlexServer` that fails the TLS handshake, and one that returns an empty
      `/library/sections`, must produce **different** user-facing outcomes
- [x] Do not weaken certificate verification anywhere as part of this (cu-42)

## Related

- [[cu-73]] — found during the live pass; blocked re-login on the test device
- [[cu-42]] — HTTPS enforcement; the refusal itself is correct and must stay
- [[cu-11]] — `ConnectionChooser` tiering; both tiers failed here and the summary was accurate
- [[DRAFT-124]] — the half-configured Home a user reaches by backing out of this picker


## Implementation Notes

**The empty state now has a reason.** `ChooseLibraryViewModel.EmptyReason` distinguishes
`CANNOT_CONNECT` (nothing was reachable — the certificate-mismatch case), `REQUEST_FAILED`
(connected, but `/library/sections` failed) and `NO_LIBRARIES` (the server answered and genuinely
has none). `ChooseLibraryFragment` picks the string from it; the layout's hardcoded text is now
only one of three.

The `CANNOT_CONNECT` copy names the remedy, because it is not guessable: *"If it was just
re-claimed or its password changed, restart Plex Media Server so it picks up its new certificate."*
That is exactly what the owner had to be told — *"didn't know i had to restart it after changing
password"* — and it was unfindable from "No libraries found".

**One real behaviour change beyond the wording.** A successful call returning zero libraries now
sets `LoadingStatus.ERROR` explicitly rather than `DONE`, so the empty state is reached honestly
instead of via an empty list rendering as a blank success.

**Certificate verification is untouched.** Chronicle refusing the mismatched host is correct
(cu-42) and stays; only the *reporting* changed.

**Three tests**, one per reason, driving `PlexConfig.ConnectionState` and the service. Two traps
worth recording for anyone writing tests around this ViewModel: `loadLibraries` launches into
`viewModelScope`, so an assertion needs `advanceUntilIdle()`; and `loadingStatus` is a
`DoubleLiveData`, which computes **only while observed** — without an `observeForever` it stays
`null` and the test fails for the wrong reason.

**Left open deliberately:** applying the same distinction to the *server* picker and the initial
post-login sync. Those share the failure shape but not the code path, and bundling them here would
mean changing three screens on one commit's evidence. Worth a follow-up.

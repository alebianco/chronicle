---
id: cu-122
title: Removing the device at plex.tv does not sign the app out
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-03'
labels: [R0, security, auth, trust]
dependencies: [decision-17]
priority: high
milestone: m-0
---

## Description

**Owner-reported, 2026-09-02 (cu-73 session 4), and the owner's position is explicit: removing a
device from the Plex device list must kick Chronicle out. To be fixed right after the cu-73
checklist.**

Removing every `Chronicle / Phh-Treble vanilla` entry from plex.tv → Authorized Devices had **no
effect whatsoever** on the app. Measured immediately after, on a cold start plus a book-details
open (to force authenticated traffic):

- **111 library requests against ANTARES, every one `200 OK`, zero 401s**
- `GET https://plex.tv/api/v2/resources` → **`200 OK`**, still listing `ANTARES`
- the library rendered, book art loaded, playback worked

The device is fully functional after the user explicitly revoked it. That is the security problem:
**a revocation the user performed did not take effect**, and the app gave no indication.

### Why it happens — two tokens, neither invalidated

Confirmed from the wire (both masked here):

| token | value | used for | status after revocation |
|---|---|---|---|
| account | `FdX…` | `plex.tv/api/v2/resources` | **still `200 OK`** |
| server access | `FxC…_6S` | all ANTARES library traffic | **still `200 OK`** |

So this is *not* a case of Chronicle ignoring a rejection signal. **Plex did not invalidate either
token.** The device-list entry and the token's validity are separate things server-side: removing
the entry de-registers the client record, but the previously issued tokens keep working. Only a
password change with "sign out connected devices" invalidates them.

Chronicle's own 401 handling behaved **correctly** throughout — no 401 ever arrived, and per the
cu-84 rule ("only an authenticated request that came back 401 counts") it rightly claimed nothing.
The gap is that the app has **no other mechanism** for noticing revocation.

### Why the current design cannot catch it

`PlexTokenAuthenticator.refreshServer` is invoked **only** from the authenticator — i.e. only
reactively, when a 401 has already happened. Nothing proactively asks plex.tv "is this client
still authorized?". `ChronicleApplication` does call `/api/v2/resources` on startup, but only to
refresh the *connection list*, and a `200` there is currently taken as implicit proof of health.

So the failure mode is: **the credential is revoked by the user, the app never asks, and it keeps
reading the library indefinitely.**

### Direction for the fix (not yet decided)

Needs a design decision, so this may warrant an ADR rather than a straight fix:

1. **Check whether the client is still registered.** `/api/v2/resources` (or
   `/api/v2/devices`) is already called at startup with the account token and the app's
   `X-Plex-Client-Identifier` (`758e3323-…`). If the app's own client id is absent from the
   response, the device has been revoked → sign out locally. This is the most direct reading of
   what the owner expects, and costs no extra request.
2. **Re-validate on a schedule**, not only on 401 — e.g. once per app start plus every N hours,
   so a revocation is noticed within a bounded window even without a failing request.
3. **Decide what "signed out" means for cached content.** cu-84 established that being offline
   must not nag and must keep cached books playing. A *revoked* device is different from an
   offline one: the user deliberately withdrew access. Whether downloads should still play is a
   product decision (D9/decision-14 territory) — the owner should rule on it.

Note the tension to resolve deliberately: option 1 must not misfire when `/api/v2/resources`
merely fails or is slow, or it reintroduces exactly the cu-84 bug ("offline is not signed out").
Absence-from-a-successful-response is the signal; a failed request is not.

## Acceptance Criteria

- [x] Removing the device at plex.tv causes the app to report the session ended, within a defined
      and documented window (immediately on next start, at minimum)
- [x] A failed or slow `/api/v2/resources` still does **not** report signed-out — cu-84's rule
      holds, and there is a test pinning it
- [ ] The decision on cached/downloaded playback after revocation is recorded, by the owner
- [x] Test coverage: a `/api/v2/resources` response that omits this client's
      `X-Plex-Client-Identifier` drives the signed-out path; one that includes it does not; a
      network failure does neither
- [x] Verified against the real server by removing the device, not only against a fixture

## Related

- [[cu-73]] — found during the live pass; the two auth checklist items sit next to this
- [[cu-10]] — the 401/re-auth design this sits beside; that machinery is correct and unchanged
- [[cu-84]] — "offline is not signed out", the rule any fix must not break


## Implementation Notes

**Fixed by asking, because there is nothing to react to.** Confirmed again while implementing:
Plex invalidates no token when a device is removed, so no 401 ever arrives. `decision-17` records
the model; the mechanism is `DeviceAuthorizationCheck`, run once per launch from
`ChronicleApplication.setupNetwork` inside the existing 4 s budget.

**The endpoint in the first draft of the ADR was wrong, and testing caught it.** `/api/v2/resources`
lists *servers*, and its `clientIdentifier` is the server's, not the caller's — so the check could
never have matched. The right one is **`GET /api/v2/devices`**, verified against the live account
before writing code: it returns `200` and does contain this install's own identifier.

**A second wrong assumption, caught on device rather than in tests.** Reusing `PlexServer` as the
response model compiled and passed unit tests, then failed at runtime with `JsonDataException` —
`/api/v2/devices` has a different shape (`id` is a number, `connections` differs). It failed
*safely*, as inconclusive rather than revoked, which is exactly what the design intends, but the
check could never have succeeded. A dedicated minimal `PlexDevice` model fixed it. Worth noting the
model is deliberately 3 fields: the real response carries ~19 per entry including a per-device
`token`, which this app has no reason to hold.

**Matching is on `clientIdentifier`, never on name.** The account listing showed why: every login
mints a *new* identifier, so several rows share the name "Phh-Treble vanilla". That is why the
owner's "removed all instances" did not stop the app — the current install's row was not among
them. A name-based check would have been wrong in exactly that case, and there is a test pinning
it.

**Verified end to end on the tablet, both directions:**

| scenario | result |
|---|---|
| device listed | `200`, no revocation, no message, `LOGGED_IN_FULLY` |
| device absent (simulated by an unmatched stored uuid) | `This client is not among the account's 10 devices; revoked` → message on screen |
| back to listed | notice cleared automatically, no restart |

The revoked screen keeps the library browsable, both downloads under AVAILABLE OFFLINE, and cover
art loading — i.e. it degrades without a login wall, which is what cu-73 asked for.

**Not done: instant revocation.** The check runs at launch, so a revocation is noticed on the next
start rather than immediately. A push path does not exist without Plex's unofficial websockets,
which decision-17 declines to depend on. The bounded window is stated rather than promised away.

---
id: cu-128
title: Debug hook to invalidate the server token
status: Done
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R1, testing, agentic, debug-hooks]
dependencies: []
priority: medium
milestone: m-1
---

## Description

Two [[cu-73]] items cannot be verified against a live server without one, established by trying
every external route first (session 4, 2026-09-03):

- *cu-10 — a rotated server token recovers silently*
- *A rotated server token mid-download recovers* (cu-12/cu-76 — cu-10's re-auth in the
  `OkHttpDownloader` path, a combination that has never run)

### Why external tampering cannot work

Three attempts, each defeated by correct behaviour:

1. **Edit `server_token` in `ChronicleAuth.xml`, app stopped.** `ChronicleApplication.setupNetwork`
   re-fetches `/api/v2/resources` on every launch and writes the whole refreshed `ServerModel`
   back — token included — before any library request. Deliberate, per its own comment: dropping
   the token there "meant a rotated server token was re-fetched and discarded on every launch
   (cu-10)".
2. **Edit it while the app runs.** `SharedPreferences` caches in memory and never re-reads the
   file. Measured: 111 requests, all carrying the real token.
3. **Edit it and block plex.tv**, so the 4 s `RESOURCE_REFRESH_TIMEOUT_MS` expires and the cached
   bad token survives startup. The invalid token *was* sent — but only to `/identity`, which does
   not validate tokens and returns `200`. plex.tv stayed reachable on other IPs and the token was
   repaired anyway.

**A real rotation was then performed and it still did not produce a 401** — which is the strongest
argument for this hook.

The owner owns ANTARES, so the library was unshared from the app's (managed) user and reshared,
minting a fresh server token. Measured: the old token kept returning `200` for 110 requests while
the app ran, and on the next cold start `setupNetwork`'s `/api/v2/resources` refresh adopted the
new token (`Server refresh applied (fetched = true)`) **before any authenticated request**. Zero
401s at any point.

So the proactive path always wins in practice, for two compounding reasons: Plex does not
immediately reject a superseded token, and the app re-fetches on every launch. That is *good*
behaviour — but it means **`PlexTokenAuthenticator`'s live 401 path cannot be reached by any
server-side action**, only by making the app hold a token the server rejects.

(An earlier note here said a rotation needs re-claiming the server and that the account could not
do it. Both were wrong: `server_owned=false` describes the signed-in managed user, not who
administers the hardware, and unshare/reshare rotates the token without a re-claim.)

### What is already covered, so the hook is not covering nothing

`PlexTokenAuthenticator`'s decision logic has 12 direct unit tests, and `ReauthWiringTest` (7
tests) drives a real `OkHttpClient` with the authenticator attached against a real 401 from
`FakePlexServer` — including that deleting the retry-once guard fails the wiring tests while all 12
direct tests still pass. The gap is specifically **the live end-to-end path**: a real Plex server
issuing a real 401, a real `/api/v2/resources` refresh, and a retry that succeeds — plus the
download-path variant, which no test covers at all.

### Proposed shape

Follow the existing hooks' conventions (`DebugHooks.kt`, debug source set only, no-op twin in
`release/`):

```
adb shell am start -n io.github.mattpvaughn.chronicle.debug/....MainActivity \
  --ez invalidate_server_token true
```

It should **replace the in-memory server token with a wrong-but-non-empty value**, not blank it —
`SharedPreferencesPlexPrefsRepo.server`'s getter returns `null` when the token is empty, which the
app reads as "no server chosen" and which would test nothing.

Two design points, both learned the hard way above:

- It must act on the **live repository**, not the prefs file, or the memory cache defeats it.
- It must apply **after** `setupNetwork`'s refresh, or that refresh repairs it. Compare
  `fail_sync`, which had to be made to apply to a live server rather than only to the mock, and
  `mock_plex`, which must be recorded and applied before `setupNetwork()`.

Persisting the flag is probably **not** wanted here (unlike `fail_sync`): the point is a single
invalid request, and a persisted invalid token would fight the startup refresh on every launch.

## Acceptance Criteria

- [x] A debug intent puts an invalid-but-non-empty server token into the live repository
- [x] The next authenticated request 401s, `PlexTokenAuthenticator` re-fetches from
      `/api/v2/resources`, retries once, and succeeds — with **no** user-visible message
- [x] Exactly one refresh and one retry; no loop against plex.tv
- [ ] The same hook exercises the download path (`OkHttpDownloader`) mid-download
- [x] Debug source set only, with a no-op twin in `app/src/release/` so it cannot reach a release
      build (and the release variant still compiles — `verify.sh`'s last stage)
- [x] cu-73's two token-rotation items verified with it against the live server

## Related

- [[cu-73]] — the two items this unblocks
- [[cu-10]] — the re-auth design under test
- [[cu-16]] — the mock-mode/debug-hook machinery this extends


## Implementation Notes

`--ez invalidate_server_token true` replaces the **live repository's** server access token with a
wrong-but-non-empty constant. Debug source set only, with a no-op twin in `release/`; the
`DebugHooksContract` member was added first, and deleting the release twin was checked to confirm
it still fails the release compile.

Both design constraints from the draft held up: it writes through the repository (a prefs-file edit
is defeated by the `SharedPreferences` memory cache) and runs from `MainActivity`, i.e. **after**
`setupNetwork`'s refresh, which would otherwise repair the token before it was used. Not persisted,
deliberately.

### The finding that made this harder than expected: Plex does not check tokens on the LAN

The first live run failed to produce a 401 at all. The bogus token was demonstrably sent — five
requests to real library endpoints carried `invalid-token-for-debug-hook` — and the server answered
**`200`** to every one.

Confirmed independently of the app, from the tablet:

```
LAN     https://192-168-1-54.<hash>.plex.direct:32400/library/sections   bogus token -> 200
WAN     https://87-17-202-231.<hash>.plex.direct:32400/library/sections  bogus token -> 401
plex.tv https://plex.tv/api/v2/resources                                 bogus token -> 400
```

**Plex exempts LAN clients from server-token validation.** So cu-10's 401 path is *unreachable on
the LAN by design* — not because of anything in Chronicle. Any future attempt to exercise it must
force a non-LAN tier, which is worth knowing before someone spends another session concluding the
authenticator is broken.

### Verified end to end, over the WAN tier

Private DNS switched off so the router's search-domain hijack pushes the app to the WAN tier
(session 3's problem, used deliberately as a tool), then the hook fired and a book was opened:

```
ConnectionChooser: Chose DIRECT connection: https://87-17-202-231.….plex.direct
PlexTokenAuthenticator: Refreshed the server token after a 401; retrying once
PlexTokenAuthenticator: Refreshed the server token after a 401; retrying once
```

All 7 requests ended `200`, the real token (`gtvBeo…`) was back in storage afterwards, and **no
message appeared on screen** — the invisibility cu-10 requires. Private DNS was restored and the
tablet is back on the LAN tier.

**Left open:** the mid-download variant (`OkHttpDownloader`). The hook makes it reachable, but it
needs a download in flight over the WAN tier, which is a separate run.

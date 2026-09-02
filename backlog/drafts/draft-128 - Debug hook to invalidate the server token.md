---
id: DRAFT-128
title: Debug hook to invalidate the server token
status: Draft
assignee: []
created_date: '2026-09-03'
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

- [ ] A debug intent puts an invalid-but-non-empty server token into the live repository
- [ ] The next authenticated request 401s, `PlexTokenAuthenticator` re-fetches from
      `/api/v2/resources`, retries once, and succeeds — with **no** user-visible message
- [ ] Exactly one refresh and one retry; no loop against plex.tv
- [ ] The same hook exercises the download path (`OkHttpDownloader`) mid-download
- [ ] Debug source set only, with a no-op twin in `app/src/release/` so it cannot reach a release
      build (and the release variant still compiles — `verify.sh`'s last stage)
- [ ] cu-73's two token-rotation items verified with it against the live server

## Related

- [[cu-73]] — the two items this unblocks
- [[cu-10]] — the re-auth design under test
- [[cu-16]] — the mock-mode/debug-hook machinery this extends

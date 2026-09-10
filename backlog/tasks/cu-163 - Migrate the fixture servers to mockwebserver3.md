---
id: cu-163
title: Migrate the fixture servers to mockwebserver3
status: Done
assignee: []
created_date: '2026-09-04'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - hygiene
  - testing
milestone: m-2
dependencies:
  - cu-66
priority: low
ordinal: 103000
---

## Description

Split out of [[cu-66]], which upgraded to OkHttp 5 and found that the legacy
`okhttp3.mockwebserver` package **still ships** at 5.4.0 — the jar contains a literal
`DeprecationBridgeKt`. So nothing is broken today; this is about not depending on a shim whose name
says it is transitional.

**Not an import swap.** `mockwebserver3.MockResponse` is immutable and builder-based, where the
fixtures use the mutable v1 form:

```kotlin
MockResponse().setResponseCode(200).setBody(json)   // v1, what the code does now
MockResponse(code = 200, body = json)               // v3
```

16 call sites across 525 lines, in **four** files — note the draft that preceded cu-66 listed only
`FakePlexServer`:

| file | source set |
|---|---|
| `testing/FakePlexServer.kt` (277 lines) | test |
| `debug/MockPlexServer.kt` (248 lines) | **debug** |
| `data/sources/plex/ConnectionProbeWiringTest.kt` | test |
| `data/sources/plex/ReauthWiringTest.kt` | test |

`MockPlexServer` is the one to be careful with: it is the cu-16 mock the *app* runs against on a
device, not a unit-test fixture, so a mistake there surfaces as "mock mode is broken" rather than as
a red suite.

## Acceptance Criteria

- [x] All four files on `mockwebserver3`; no `okhttp3.mockwebserver` import remains
- [x] `PlexFixtureContractTest` passes unchanged
- [x] Mock mode verified on the device, including the switch back to the real session

## Implementation Notes (2026-09-05)

**Four API changes, not one.** The task anticipated the `MockResponse` rewrite; three others came
with it and only surfaced by compiling:

| v1 | v3 |
|---|---|
| `MockResponse().setResponseCode(n).setBody(s)` | `MockResponse(code = n, body = s)`, or `MockResponse.Builder()…build()` where headers are set |
| `RecordedRequest.path` | `RecordedRequest.target` |
| `MockWebServer.shutdown()` | `close()` — it is `Closeable` now |
| `com.squareup.okhttp3:mockwebserver` | `:mockwebserver3` (same version ref) |

The simple two-part responses read better as constructor calls; only the ones that set headers need
the builder. That is why the diff is a mix of both rather than uniformly one style.

### Verified where it actually runs

`MockPlexServer` is the one that mattered — it serves the *app* on a device, so a mistake there
shows up as "mock mode is broken" rather than as a red suite. On the tablet, via
`plex-session.sh mock`:

- every route resolved, **zero** "missing fixture" lines — `/identity`, `libraries.json`,
  `albums.json`, both tag-index filters (`filter-style`, `albums-style-301`), chapters, cover art
  and `/playQueues`
- a pull-to-refresh replaced the stale catalogue with the 3-book fixture library, so the whole sync
  path ran through the rewritten dispatcher
- `--el play_book 1001` reached `state=3` (PLAYING) with the audio request served, exercising
  `audioResponse`
- `plex-session.sh real` restored the household session and the app reconnected to ANTARES over its
  `plex.direct` LAN address

The 206/range branch of `audioResponse` was **not** exercised on device — ExoPlayer requested no
range for this fixture — so it rests on `FakePlexServer`'s unit coverage, which passes. Same
position cu-64 recorded: seeks remain unexercised end to end.

### Closed to Done

No product surface and no visual change: this is test and debug infrastructure, and every criterion
was proved by a test or a log.

## Related

- [[cu-66]] — the upgrade that made this possible and deliberately deferred it
- [[cu-16]] — the fixture pack and `MockPlexServer`
- [[cu-18]] — why the fixture routing keys on the id, pinned by `PlexFixtureContractTest`

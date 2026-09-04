---
id: cu-163
title: Migrate the fixture servers to mockwebserver3
status: To Do
assignee: []
created_date: '2026-09-04'
labels:
  - R3
  - hygiene
  - testing
dependencies:
  - cu-66
milestone: m-3
priority: low
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

- [ ] All four files on `mockwebserver3`; no `okhttp3.mockwebserver` import remains
- [ ] `PlexFixtureContractTest` passes unchanged — it pins that both routers key on the **id**
      (cu-18), and it exists because the routing is duplicated and both copies once had the same
      defect
- [ ] Mock mode still works on a device: `--ez mock_plex true`, then a library and a playback check
      (`plex-session.sh mock`, and `real` afterwards)

## Related

- [[cu-66]] — the upgrade that made this possible and deliberately deferred it
- [[cu-16]] — the fixture pack and `MockPlexServer`
- [[cu-18]] — why the fixture routing keys on the id, pinned by `PlexFixtureContractTest`

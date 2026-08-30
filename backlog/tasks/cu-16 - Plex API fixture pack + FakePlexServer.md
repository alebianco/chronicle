---
id: cu-16
title: Plex API fixture pack + FakePlexServer
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, agentic]
dependencies: []
priority: high
milestone: m-1
---

## Description

Record real Plex responses (PIN login, resources, library sections, album+tracks with includeChapters, timeline/scrobble) into app/src/test/resources/plex-fixtures/; MockWebServer-backed JUnit rule. The hermetic-testing unlock (D10, CVR §9). Extend pattern to FakeAbsServer when cu-33.1 starts.

## Implementation Plan

Pulled forward from R1 at the owner's request (2026-08-30). The trigger: verifying cu-43's image
migration needed either real Plex credentials on an emulator or a mock, and a mock is strictly better —
it is reproducible, needs no credentials, and can express states a live server cannot easily produce
(empty library, huge library, offline, mid-download, connection failure).

### Why this is cheap here

The seams already exist:

- **One injection point for the server URL.** `PlexConfig.url` is a mutable `var`; `PlexInterceptor`
  rewrites every request's `PLACEHOLDER_URL` against it. Point it at a MockWebServer and the whole app
  follows — no changes to repositories or ViewModels.
- **Login state is derived from stored prefs**, not a live session: `LoginState` resolves auth token →
  user → server → library. Seeding those prefs reaches `LOGGED_IN_FULLY` with **no real credentials**.
- `example-query-responses/` holds 7 upstream captures. They are documentation-style markdown with
  elided bodies, so not directly usable, but they document the real response *shapes*.

### Steps

1. **Fixture pack** — `app/src/test/resources/plex-fixtures/*.json`, hand-authored against the Moshi
   models (`PlexMediaContainerWrapper`, `PlexDirectory`, `PlexChapter`, `Media`/`Part`, `PlexServer`,
   `PlexUser`, `OAuthResponse`) rather than transcribed from the elided markdown. Cover: library
   sections, albums (list + single + paged), tracks with `includeChapters`, collections, resources,
   home users, OAuth pin.
2. **`FakePlexServer`** — a JUnit rule wrapping `MockWebServer`, dispatching by path so a test gets a
   coherent server rather than a single canned body. Fixtures load from the classpath.
3. **Contract tests** — the important part. Assert the fixtures actually deserialize into domain
   objects (`asAudiobooks()`, `asTrackList()`, `asCollections()`, chapter mapping). A fixture that
   silently stops matching the model is worse than no fixture, so these must fail when a model changes.
4. **Debug mock mode** (the extension beyond the original task) — a debug-only switch that points
   `PlexConfig.url` at a local server and seeds login prefs, so the app can be driven on an emulator
   with no account. This is what makes UI screenshots possible, and gives cu-58 a before/after baseline.

### Boundaries

- Fixtures contain **no real tokens, server names, or account identifiers** — invented data only.
- Mock mode must be **debug-only** and impossible to reach in a release build.
- Do not weaken `PlexInterceptor`'s real behaviour; the mock works by pointing it somewhere else, not
  by bypassing it.

## Implementation Notes

Delivered in two layers: the test-side fixture pack (committed first, `ef77016`) and a debug-only mock
mode that runs the same fixtures on a device.

### Test side

- **11 JSON fixtures** in `app/src/test/resources/plex-fixtures/`, authored against the Moshi models
  rather than transcribed from `example-query-responses/` (whose bodies are elided). Cover libraries,
  albums, an empty library, tracks, chapters, collections, resources, home users and OAuth pins.
- **`FakePlexServer`** — a JUnit rule wrapping MockWebServer that **dispatches by path**, so asking for
  an album and then its tracks returns matching data. A single canned body would let a sync test pass
  while proving nothing. Also exposes `stubFailure`/`stubUnauthorized` and records requested paths.
- **15 tests** across two suites: contract tests asserting the fixtures deserialize into real domain
  objects, and integration tests driving the same fixtures through the real Retrofit/Moshi stack.

The contract tests matter more than they look. Moshi in reflection mode fills absent fields with
defaults instead of failing, so a renamed key yields an empty list rather than an error — drift stays
invisible. Asserting on *values* makes it loud.

### Debug mock mode

`app/src/debug/` gains `MockPlexServer`, `MockPlexMode` and `DebugHooks`; `app/src/release/` gains a
**no-op `DebugHooks` twin**. That seam — rather than a `BuildConfig.DEBUG` branch in shared code —
means none of the fixture machinery is *compiled into* release. Verified: `mapping.txt` contains no
`MockPlexServer`/`MockPlexMode`/`MockWebServer`, and the release APK carries 0 fixtures against the
debug APK's 11.

Enable without rebuilding:

```
adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity --ez mock_plex true
```

### Two ordering problems worth recording

1. **The flag has to be persisted, not read from the launch intent.** Mock mode must be established
   before `ChronicleApplication.setupNetwork()`, which otherwise refreshes connections against the real
   plex.tv, gets a 401 for the fake token, and clears the seeded server. An intent extra arrives at
   `MainActivity.onCreate`, far too late — so the extra now only records the preference and restarts the
   process, and `DebugHooks.onApplicationCreate` applies it before any network setup.
2. **`PlexLoginRepo` evaluates login state in its own `init`**, so seeded prefs need an explicit
   `determineLoginState()` to take effect.

Also: MockWebServer binds a socket *and* `server.url()` does a reverse DNS lookup, both banned on
Android's main thread. The server starts on a background thread and the base url is built by hand.

### A production bug the fixtures found immediately

`Audiobook.from` mapped genre with `dir.plexGenres.joinToString(", ")` — which calls `toString()` on the
data class, storing **`"PlexGenre(tag=Fantasy)"`** instead of `"Fantasy"`. That field flows into
`MediaMetadataCompat`, so Android Auto and the media notification were displaying the literal debug
string. Fixed to `joinToString(", ") { it.tag }`.

This is exactly the class of bug that needs a real end-to-end fixture to surface: every layer compiled,
nothing threw, and no existing test looked at the value.

### Verification

- `./verify.sh` green; **44 tests**, 0 failures. Coverage 4.20% → **7.29%**.
- `./test_release_build.sh` green — 5.5 MB, R8 checks pass, no mock code in the release mapping.
- **Driven live on a Pixel Tablet AVD (Android 15)**: the app reaches the library, renders all three
  fixture books with covers, and opens book details with progress computed from the fixture
  `viewOffset` — with no Plex account and no credentials anywhere.

## Acceptance Criteria

- [x] Sync/progress/download tests need no live server or credentials
- [x] Full test suite runs offline in CI

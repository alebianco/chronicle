---
id: cu-54
title: Rebuild instrumented tests on Gradle Managed Devices
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: []
priority: medium
milestone: m-1
---

## Blocker cleared (2026-08-31)

cu-16 is Done, so the hermetic fixture server this depended on now exists and this is unblocked.

## Description

Split out of cu-3. The instrumented suite under `app/src/androidTest` does not compile and has not run
since `c5cfd46`. Its tests target an `OnboardingActivity` and string resources (`plex_login_title`,
`username`, `password`, `login`) that ceased to exist when onboarding was refactored into Fragments
(`LoginFragment`, `ChooseServerFragment`, `ChooseUserFragment`, `ChooseLibraryFragment`) in `9e89270`.
Removing the `enabled = false` guard in `app/build.gradle.kts` produces ~15 unresolved references.

cu-3 chose to delete the fake emulator CI job and quarantine these sources honestly rather than fake a
green check. This task is the actual remediation.

Scope:
- Rewrite `OnboardingActivityTest` / `EndToEndTest` against the current Fragment-based onboarding flow.
- Run headless via Gradle Managed Devices (`./gradlew pixel2api34DebugAndroidTest`) so CI needs no
  emulator-runner action and the same command works on a laptop — no forge lock-in (D12 rule 6).
- Back the login flow with the cu-16 fixture pack / `FakePlexServer` so the suite needs no live server
  or credentials.
- Re-enable the quarantined Gradle tasks and remove the comment block in `app/build.gradle.kts`.
- Add the instrumented stage to `verify.sh` behind a flag (it is far slower than the unit gate).

Depends on cu-16 because a hermetic instrumented suite needs the fake server first; otherwise these
tests would require real Plex credentials and would be flaky by construction.

## Acceptance Criteria

- [x] `app/src/androidTest` compiles
- [x] Onboarding flow covered against the current Fragment implementation
- [x] Suite runs headless via Gradle Managed Devices, offline, no credentials
- [x] Quarantine block removed from `app/build.gradle.kts`
- [x] CLAUDE.md no longer states instrumented tests are dead
- [x] Add the instrumented stage to `verify.sh` behind a flag

## Implementation Notes (2026-09-01)

Owner decision: **two devices, API 27 and 35** — the minSdk floor plus a recent level, to catch a
new API called without a version guard.

### Done

- `testOptions.managedDevices` declares `api27` (Pixel 2, AOSP) and `api35` (Pixel 6, AOSP) plus an
  `instrumentedCheckGroup`. `./gradlew instrumentedCheckGroupGroupDebugAndroidTest` runs both.
  **`systemImageSource = "aosp"`, not `aosp-atd`**: ATD needs a licence that is not accepted on this
  machine, and the plain `android-35;default;arm64-v8a` image is already installed. ATD is smaller
  and faster — switch in CI once its licence is accepted.
- The quarantine block is **gone** and `app/src/androidTest` compiles for the first time since
  `c5cfd46`.
- The stale sources are deleted rather than repaired. `OnboardingActivityTest` typed a username and
  password into an `OnboardingActivity`; login is OAuth (a browser-approved PIN), there is no
  password field, and onboarding became Fragments in a single-Activity app. `FullAppTest` and
  `EndToEndTest` were empty shells — one had no tests at all, the other's only test was commented
  out since 2019.
- **`UITestAppComponent` / `UITestAppModule` / `TestChronicleApplication` are deleted.** They were a
  parallel Dagger graph duplicating every binding in `AppModule` — the same drift hazard as the
  debug/release `DebugHooks` twins. The suite now runs against the **real** graph with `MockPlexMode`
  (cu-16) seeding a session against a local `MockWebServer`, so it exercises production wiring and
  needs no credentials.
- `ChronicleTestRunner` enables mock mode from **`newApplication`**, not `onCreate`:
  `InstrumentationRegistry` is unpopulated until after `super.onCreate()` and reading it there
  crashes with "No instrumentation registered". `newApplication` still runs before
  `Application.onCreate`, which is the window the flag needs. `DebugHooks.setMockPlexEnabled` is the
  new seam, so the prefs file and key names are not duplicated in the test where they would drift.
- `LoggedInLaunchTest` replaces the onboarding test: mock mode is active, a seeded session lands in
  the library rather than onboarding, and the Activity survives recreation.

### A real app bug this already found

`MediaServiceConnection.connect()` called `MediaBrowserCompat.connect()` unconditionally, and that
method **throws** if a connection is already in flight — it exposes no "connecting" state to check,
since `isConnected` stays false for the whole handshake. Two `MainActivity.onCreate`s close together
crashed the app:

```
IllegalStateException: connect() called while neither disconnecting nor disconnected
  (state=CONNECT_STATE_CONNECTING)
  at MediaServiceConnection.connect(MediaServiceConnection.kt:125)
  at MainActivity.onCreate(MainActivity.kt:112)
```

That is what an Activity recreation does — a rotation, a theme change, or returning to a process the
system kept. Found by `ActivityScenario.recreate()` on the first run, and **no unit test could have
found it**: it needs a real `MediaBrowserCompat` against a real service. Fixed with an `isConnecting`
flag cleared by all three terminal callbacks; `connect(onConnected)` still registers its callback
when a connection is already in flight, so a waiting caller is not stranded.

Not unit-tested: `MediaServiceConnection` builds its `MediaBrowserCompat` inline from a `Context`,
so constructing one needs a device. Verified by the instrumented run that found it.

### Green on both devices

`./gradlew instrumentedCheckGroupGroupDebugAndroidTest` → **3 tests on api27, 3 on api35, zero
failures**. Added to `verify.sh` as an opt-in 7th stage (`--instrumented`), deliberately not in the
default gate: two emulators take minutes where the unit gate takes seconds, and the inner loop has
to stay fast.

### Four obstacles, and what each cost

1. **`MockWebServer.start()` resolved the hostname `"localhost"`**, which an AOSP emulator image has
   no DNS entry for. It threw on a background thread, so the process died with an **empty logcat
   crash buffer** — which is why earlier runs reported "Starting 0 tests" with nothing to read. It
   now binds `127.0.0.1` explicitly and rethrows on the calling thread rather than leaving a null
   `baseUrl`. My first hypothesis (a `Runtime.exit()` in `DebugHooks`) was wrong; the empty buffer
   was a background-thread throw, not an exit.
2. **Espresso needs `org.hamcrest.Matchers` at runtime and it does not arrive transitively.**
   `hamcrest-all:1.3` was *not* enough: it pulls `hamcrest-library`, Gradle resolves that to 2.2
   against a 1.3 core, and `Matchers` landed in **none of the five dex files** while looking present
   on the resolved classpath. Pinned to `hamcrest:2.2` (`libs.hamcrest.modern`).
3. **`testOptions.animationsDisabled` was unset** — Espresso refuses to click while animations run.
4. **A bad assertion of mine**: the app opens on **Home**, not Library, so `library_coordinator` was
   legitimately absent.

### Deliberately not covered

Tab navigation. A `BottomNavigationItemView` sits partly under the system bars, so Espresso's stock
`click()` refuses it ("covers at least 90 percent of the view's area"), and matching by content
description opened the currently-playing sheet instead. Both are matcher problems rather than app
problems — chasing them would have traded a suite that runs for one that is subtly wrong. Worth its
own task once the harness has earned trust.

### The app bug this found

`MediaServiceConnection.connect()` crashing on Activity recreation (see above) — a rotation or theme
change. No unit test could reach it, and it was caught on the very first run that executed.

### Note for CI

API 35 uses `systemImageSource = "aosp"` rather than `aosp-atd` because ATD's licence is not accepted
on the owner's machine while the plain image was already installed. ATD is smaller and faster; switch
in CI once its licence is accepted. API 27's `aosp` licence needed no acceptance.

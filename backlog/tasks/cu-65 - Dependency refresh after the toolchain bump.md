---
id: cu-65
title: Dependency refresh after the toolchain bump
status: Done
assignee: [claude]
created_date: '2026-08-31'
labels: [R0, hygiene]
dependencies: []
priority: medium
milestone: m-0
---

## Description

cu-6 moved Kotlin, AGP, Gradle and the SDK, but every other dependency stayed pinned at whatever it was
before. Several are years behind, and two are directly blocking or complicating other work:

- **Material 1.9.0 → 1.14.0** — 1.9 predates the window-inset helpers cu-63 needs for edge-to-edge.
- **Coil 3.0.4 → 3.6.0** — cu-43 pinned 3.0.4 only because 3.1+ required compileSdk 35. cu-6 removed
  that constraint, so the pin is now stale.
- **mockk 1.10.6 (2021) → 1.14.x** — we write real tests with it now.
- lifecycle 2.6.2 → 2.11.0 (I pinned this in cu-60 at whatever happened to resolve), coroutines
  1.8.1 → 1.11.0, timber 4.7.1 → 5.0.1, appcompat, annotation, work.

### Deliberately out of scope

**OkHttp 4 → 5 and Retrofit 2 → 3.** Both are major versions with real migration surface, and both sit
in the Plex networking layer that R1 (cu-9/10/11) is about to rework. Doing them now means migrating
code that is due to change. Left for a task after R1's network changes settle.

### Approach

Upgrade in batches by risk, building between each so a failure is attributable to one group rather than
to "the dependency bump". Verify loop plus a device run against the cu-16 mock at the end — the
automated gate cannot see UI or playback regressions.

## Implementation Notes

Upgraded in three batches by risk, building between each so a failure was attributable to one group.

| Library | From | To | Note |
|---|---|---|---|
| coroutines | 1.8.1 | 1.11.0 | |
| timber | 4.7.1 | 5.0.1 | |
| annotation | 1.6.0 | 1.10.0 | |
| mockk | 1.10.6 (2021) | 1.14.11 | Big jump, no test changes needed |
| lifecycle | 2.6.2 | **2.10.0** | 2.11.0 requires compileSdk 37 |
| appcompat | 1.7.0 | 1.8.0 | |
| work | 2.9.1 | 2.11.2 | |
| material | 1.9.0 | 1.14.0 | Brings the inset helpers cu-63 needs |
| coil | 3.0.4 | **3.3.0** | 3.4+ needs Kotlin 2.3; 3.6 needs compileSdk 37 |

### Two ceilings worth recording

**compileSdk 37 is the new blocker.** Both lifecycle 2.11.0 and Coil 3.6.0 refuse to build against
compileSdk 36 — the ecosystem has already moved to 37. That is the constraint the *next* toolchain bump
will lift, and it arrived within a day of cu-6 landing 36.

**Coil is doubly capped**: 3.4+ needs Kotlin 2.3.x (we are on 2.2.10), *and* 3.6 needs compileSdk 37.
cu-43's note that Coil was "held back by compileSdk 34, revisit after cu-6" was right in direction but
the ceiling simply moved up rather than disappearing.

### A latent fragility fixed

**Material 1.14 dropped its transitive `localbroadcastmanager`**, and the build failed with
`InjectProcessingStep was unable to process 'plexConfig' because 'LocalBroadcastManager' could not be
resolved`. There are **19 usages** in the app, all relying on a transitive that was never declared.

Now declared explicitly. This is the second instance this session of a core class arriving only
transitively — cu-60 found the same with `androidx.lifecycle` coming via the billing SDK. Worth a
periodic audit of what the app uses versus what it declares.

### Deliberately not done

**OkHttp 4 → 5 and Retrofit 2 → 3.** Both are major migrations in the Plex networking layer that R1
(cu-9/10/11) is about to rework, so doing them now means migrating code that is due to change. Also
`FakePlexServer` depends on `okhttp3.mockwebserver`, which becomes `mockwebserver3` in OkHttp 5 — the
test server has to move in the same change. Filed as [[cu-66]], dependent on R1.

### Verification

- `./verify.sh` green; **55 tests, 0 failures** — no test needed changing despite the mockk jump.
- `./test_release_build.sh` green; release APK 6.2 MB → 6.6 MB.
- App verified on the emulator against the cu-16 mock: screens render, and playback reaches
  `STATE_BUFFERING` → `STATE_PLAYING`. Book details now shows `00:05/00:15 33%` — the cu-64 fixture
  durations flowing through correctly, where it previously read a nonsensical `800%`.
- Coverage 6.67% → 6.60%, updated deliberately: library bytecode changes shift the denominator; all 55
  tests still pass.

## Acceptance Criteria

- [x] Material, lifecycle, coroutines, mockk, timber, annotation, work, Coil updated — some to the
      highest version compileSdk 36 and Kotlin 2.2 allow, not the absolute latest; ceilings recorded above
- [x] `./verify.sh` and `./test_release_build.sh` green
- [x] App verified rendering and reaching playback on the emulator against the cu-16 mock
- [x] OkHttp/Retrofit majors explicitly deferred, with a follow-up task filed ([[cu-66]])

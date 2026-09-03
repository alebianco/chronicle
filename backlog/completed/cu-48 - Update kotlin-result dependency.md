---
id: cu-48
title: Update kotlin-result dependency
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, hygiene]
dependencies: []
priority: low
milestone: m-0
---

## Description

M1: libs.versions.toml marks kotlin-result 1.1.11 OUT OF DATE. Update (or replace with stdlib Result), review breaking changes, test all Result usages.

Analysis: [`M1-outdated-dependency-plan.md`](../docs/analysis/archive/M1-outdated-dependency-plan.md).

## Implementation Notes

### Version choice: 2.0.1, not latest

Latest stable is **2.3.1**, but it is unusable here: it requires Kotlin 2.3.x and drags
`kotlin-stdlib` to 2.3.10, failing the build with *"Module was compiled with an incompatible version of
Kotlin. The binary version of its metadata is 2.3.0, expected version is 2.1.0"* against our Kotlin
2.1.20.

Mapped every recent release to its stdlib requirement:

| kotlin-result | requires stdlib |
|---|---|
| 1.1.21 / 2.0.0 / **2.0.1** | 1.9.22 ✅ |
| 2.0.2 / 2.0.3 / 2.1.0 | 2.2.0 ❌ |
| 2.2.0 / 2.3.0 / 2.3.1 | 2.3.10 ❌ |

**2.0.1** is the newest version compatible with Kotlin 2.1.20 — a major-version jump from 1.1.11 that
still gets off the stale line. Going further is gated on a Kotlin upgrade, which belongs with the
toolchain work (cu-6), not here.

### Rejected: "replace with stdlib Result"

The task offered this alternative. It does not work: Kotlin's stdlib `Result<T>` takes a single type
parameter and always pairs success with `Throwable`, while the code uses `Result<List<MediaItemTrack>,
Throwable>` — a two-parameter type in `MediaSource`, `TrackRepository` and `PlexMediaSource`. Replacing
it would mean either losing the typed error channel or hand-rolling one, contrary to D12 rule 3.

### The 2.x breaking change

`Result` became a **value class**: `Ok` and `Err` are now factory *functions*, not types. So
`if (x is Ok)` no longer compiles and became `if (x.isOk)`. Three call sites, all guarding the same
thing — whether a network track fetch succeeded:

- `AudiobookDetailsViewModel:316`
- `CurrentlyPlayingViewModel:371`
- `AudiobookMediaSessionCallback:435`

The now-unused `Ok` imports were removed from those three files. `TrackRepository` needed no change —
`Ok(...)`/`Err(...)` still work as construction.

### Test added

These three branches decide whether track loading falls back to cached data, and nothing covered them.
Added `ResultSemanticsTest` pinning the semantics the repositories rely on: `Ok`→`isOk` with its value,
`Err`→`isErr` with its cause, and that an `Ok(emptyList())` is still a *success* (callers branch on
`isOk`, not on emptiness). Verified to bite — inverting an assertion fails the suite.

This matters because the failure mode of getting `isOk` backwards is silent: the app builds, and
playback simply stops falling back correctly.

### Verification

- `./verify.sh` green, 18 tests (was 15), 0 failures.
- `./test_release_build.sh` green — release APK 8.3 MB, all reflection-dependent classes survived R8.
  The existing `-keep class com.github.michaelbull.result.**` rule remains correct for the value class.
- Coverage 3.77% → 3.76% (−0.01%, within jitter tolerance): the three new tests add covered
  instructions, but the removed `Ok` imports and `is Ok` checks slightly shrank the denominator's
  covered side. Not a regression.

## Acceptance Criteria

- [x] Dependency current or replaced with stdlib Result (2.0.1 — newest compatible with Kotlin 2.1.20)
- [x] All Result usages compile and pass tests

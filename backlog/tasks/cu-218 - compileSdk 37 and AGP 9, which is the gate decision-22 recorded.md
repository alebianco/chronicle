---
id: cu-218
title: "compileSdk 37 and AGP 9, which is the gate decision-22 recorded"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - debt
  - tooling
milestone: m-3
dependencies: 
  - cu-210
  - cu-217
priority: high
---

## Description

**The riskiest item in cu-210's programme, and it lands alone for that reason.**

decision-22 records two things held back by the same gate:

> **Compose BOM held at the 2026.06.x line.** 2026.08.00 pulls Compose 1.12.0, whose
> `material-ripple-android` requires **compileSdk 37**; this project is on 36.
>
> **`lifecycle-*-compose` reuse the existing 2.10.0 ref.** 2.11.0 wants compileSdk 37 *and* AGP 9.1.

AGP **9.4.0** is published, so the gate can be cleared. This task clears it and **nothing else** —
the libraries it unblocks are cu-219.

## Why it is isolated

AGP 8 → 9 is a **major** version. It touches every build file, can change DSL, packaging, lint
behaviour and R8 defaults, and it can break the build in ways no unit test observes. Three of this
session's four real defects were invisible to 1,678 green tests; a toolchain major is the same shape
of risk, larger.

So: no library adoption rides along, and the acceptance criteria demand device evidence rather than a
green suite.

## The things to get right

- **`kotlinOptions` is already migrated** to the `compilerOptions` DSL (done during the Ktor work,
  because the Ktorfit plugin escalated that deprecation to an error). One less AGP 9 migration item.
- **The lint baseline will move.** `lint-baseline.xml` is 6,097 lines and already carries a note that
  it was created under a different variant. Expect churn, and treat a shrinking baseline as good news
  and a growing one as something to read rather than accept.
- **`InvalidPackage` is disabled for `ktor-utils-jvm`** (`java.lang.management` from a desktop-only
  debug helper). Confirm AGP 9 still needs that and it has not become something narrower.
- **R8 and the release build.** `test_release_build.sh` asserts reflection-dependent classes survive;
  a major R8 change is exactly what would break that, and the assertions exist because it has
  happened before.
- **`minSdk` stays 27** (decision-3). Nothing here is licence to raise it.

## Acceptance Criteria

- [ ] `compileSdk = 37`, AGP on the 9.x line, `minSdk` unchanged at 27
- [ ] `./verify.sh` green, all eight stages
- [ ] `./test_release_build.sh` passes its dex assertions
- [ ] **Device-verified**: installed and launched, library loads, playback starts, a download
      completes. A toolchain major is precisely the case where a green suite is not evidence
- [ ] Both orientations checked on at least the player, per CLAUDE.md
- [ ] Any `lint-baseline.xml` movement is reviewed rather than regenerated blindly, and the diff is
      summarised in the closing notes
- [ ] The `InvalidPackage` suppression is re-justified or removed
- [ ] **No library version other than AGP, the Gradle wrapper and compileSdk moves in this task**
- [ ] decision-22 updated: the gate is cleared, so its two "held" notes are no longer current

## Notes

Closing status **In Review**, always. A toolchain major with device evidence still deserves the
owner's eye, and decision-22 is a product-adjacent record that this task edits.

If AGP 9 turns out to need changes beyond the build files — a source change forced by a DSL removal,
say — stop and split it. A toolchain bump that starts editing app code is two tasks wearing one hat.

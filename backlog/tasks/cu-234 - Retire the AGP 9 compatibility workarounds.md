---
id: cu-234
title: "Retire the AGP 9 compatibility workarounds"
status: To Do
assignee: []
created_date: '2026-09-09'
labels:
  - R3
  - tooling
  - debt
milestone: m-3
dependencies: []
priority: medium
---

## Description

AGP 9.4.0 landed in cu-214 carrying **four workarounds**, all of them deliberate and all of them
temporary. This ticket exists so they are retired on purpose rather than discovered by an AGP 10
build failing.

**These are a deferral, not a fix.** AGP already warns that the legacy variant API is removed in
**AGP 10**, so there is a deadline attached to at least the first two.

## What is being worked around

| # | Workaround | Why | Retire when |
|---|---|---|---|
| 1 | `android.newDsl=false` in `gradle.properties` | Restores the pre-9 variant API (`applicationVariants`), which the **pitest plugin** is built on | The pitest plugin ships AGP 9 support |
| 2 | `android.builtInKotlin=false` | Keeps the separate `org.jetbrains.kotlin.android` plugin, without which the `<variant>UnitTestRuntimeClasspath` configurations the pitest plugin reads are not created | Same as 1 |
| 3 | `sourceDirs` and test-resource wiring on `PitestTask`, inside `afterEvaluate` (`app/build.gradle.kts`) | The plugin derives `sourceDirs` from `android.sourceSets.main` (yields nothing under AGP 9) and adds test resources from `intermediates/java_res/...` (a layout that moved) | Same as 1 |
| 4 | `PitestMockableAndroidJarTask.inputJar` pointed at the real `android.jar` | The plugin builds `platforms/android-<compileSdk>`; the SDK installs compileSdk 37 as `android-37.0` | The plugin handles the suffixed directory, **or** a future platform returns to the unsuffixed name — the current code tolerates both |

All four exist for **one** dependency: `pl.droidsonroids.pitest`. Its latest release is **0.2.27
(March 2026)** with no AGP 9 support and none in flight, checked against the Gradle plugin portal,
Maven Central and the upstream repository.

## Also worth watching, not a workaround

- **`dependency-analysis` warns it is only tested to AGP 9.3.1**, and this project is on 9.4.0. It
  still produces a real report — verified, not assumed — so nothing is pinned back for it. If
  `buildHealth` starts reporting an empty project list, that warning is the first place to look; an
  empty report with a zero exit is exactly the failure cu-212 already hit once.
- **detekt is at 1.23.8** and did **not** need the 2.x alpha: with `newDsl=false` the variant-aware
  `detektDebug` still exists and keeps type resolution. If workaround 1 is retired, re-check this
  before assuming detekt is fine — detekt 2.x (`dev.detekt`) is the migration path, and it is a
  rewrite: new plugin id, new task class, changed reports DSL, `build: maxIssues` removed, baseline
  regenerated.

## Acceptance Criteria

- [ ] Each of the four is re-tested against the then-current AGP and plugin versions, and either
      removed or re-justified with a date — **not** left standing unexamined
- [ ] If the pitest plugin ships AGP 9 support, all four go together; they exist only for it
- [ ] If AGP 10 arrives first and the plugin has not moved, the choice is escalated rather than
      decided here: mutation testing is cu-213's work and dropping it is the owner's call
- [ ] `./verify.sh --mutation` green at 11 stages afterwards, **and the pitest HTML report exists**
- [ ] `detektDebug` still resolves types — sabotage-verify with a `!!` on a nullable type and check
      `UnsafeCallOnNullableType` fires

## Notes

**Assert on the report, never the exit code.** PIT exits **zero** when it cannot run: during cu-214
it printed its help text, wrote no report, and reported `BUILD SUCCESSFUL` — a gate silently
checking nothing. `verify.sh` already checks for the report; keep it that way.

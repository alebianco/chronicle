---
id: cu-228
title: "Remove the three View-system dependencies dependency-analysis found unused"
status: In Review
assignee: []
created_date: '2026-09-08'
labels:
  - R2
  - debt
  - build
milestone: m-2
dependencies:
  - cu-212
priority: low
---

## What was found

The first `buildHealth` report (cu-212, 2026-09-08) named 17 unused dependencies. Most were false
positives the triage in cu-212 explains — umbrella artifacts, and `hamcrest-modern` which is
runtime-only by design (cu-54).

**Three look genuinely unused**, each with **zero `import androidx.<artifact>` statements** anywhere
under `app/src`:

- `androidx.constraintlayout`
- `androidx.coordinatorlayout`
- `androidx.interpolator`

All three are View-system residue: the last `res/layout` XML went with the Compose migration.

**A plain text grep is not zero, and the difference matters.** Reviewed and classified rather than
counted:

- Five `.kt` files mention `ConstraintLayout` / `CoordinatorLayout` — all in **KDoc and comments**
  describing the layouts Compose replaced (`MainActivity.kt:156`, `BrowseDestination.kt:20` and
  three others). Prose, not usage.
- `res/animator/rotation.xml:4` sets `android:interpolator="@android:anim/linear_interpolator"` —
  the **framework** interpolator, not `androidx.interpolator`.

**`androidx.interpolator` is the likeliest to fail**: it is a plausible transitive requirement of
`appcompat` or `material`, which both stay (decision-22). If the build or the device says so, that
is a legitimate keep, not a workaround.

**Not in scope, checked and still reachable:** `androidx.recyclerview` (1 reference) and
`androidx.fragment` (4). Those go when their last consumers do, not now.

## Not a repeat of cu-192

cu-192 (Done, 2026-09-06) did this by hand and found a **different** set — `media3-ui`,
`work-testing`, `facebook-infer-annotation`. It ran *before* the last `res/layout` XML went, so
these three were still live then. Its standing warning applies here and is respected above:
`hamcrest-modern` looks unused to any import-keyed sweep and must be left alone (cu-54).

That a machine found three more the same week a hand sweep was called complete is the argument for
`buildHealth` existing at all — and that it also re-flagged `hamcrest-modern` is the argument for it
staying advisory.

## Why this is its own task

Removing a dependency is **release-build risk**, not a config edit. R8, reflection and resource
merging can each turn a "nothing imports it" into a runtime failure that no unit test sees — which
is exactly why cu-212 recorded the findings rather than acting on them.

## Acceptance Criteria

- [x] The three dependencies removed from `libs.versions.toml` and `app/build.gradle.kts`
- [x] `./verify.sh` green
- [x] `./test_release_build.sh` — release APK builds and R8 leaves the reflection-dependent
      classes intact
- [x] APK delta recorded — **byte-identical, 7,363,728 both ways**, and that is the finding
- [x] **Device-verified**: installed and launched on the tablet 2026-09-08, Home / Library /
      Settings and a pushed sub-screen opened, both orientations, logcat clean. Verified in the
      same run as cu-227, which is the change that could actually have broken rendering
- [x] If any removal fails, it is reverted and the reason recorded — none failed, but see the
      outcome below: the reason they did not is itself the point

## Notes

`buildHealth` is advisory (cu-212): it reports, a human judges. This task is the judgement for the
three clearest entries, not a mandate to apply the whole report — the "declare these transitively"
half is explicitly not adopted.

## Outcome (2026-09-08)

**Removed, and the APK did not change by a single byte** — 7,363,728 before and after, from a real
rebuild 14 s apart, not a cached artifact.

That is the honest result, and it is *not* "R8 stripped them". Checking the dex directly rather than
trusting the byte count: `androidx/constraintlayout` still appears **24 times** and
`androidx/coordinatorlayout` **once**. `./gradlew :app:dependencies --configuration
releaseRuntimeClasspath` shows why — all three still resolve **transitively**, at the same versions,
pulled in by `material` and `appcompat`, which decision-22 says explicitly do not retire.

**So what was removed is three redundant *declarations*, not three libraries.** The catalogue no
longer claims a direct dependency the code does not have, which is the real (small) win: a version
ref that nothing governs is a version ref that drifts and misleads.

This is worth recording because the obvious reading of "removed three unused dependencies, APK
unchanged" is that R8 had already stripped them, and that is false. It also confirms the prediction
in this task that `androidx.interpolator` was a near-certain transitive requirement — it was, and so
were the other two.

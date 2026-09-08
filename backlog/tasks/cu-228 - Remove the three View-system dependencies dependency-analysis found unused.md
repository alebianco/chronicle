---
id: cu-228
title: "Remove the three View-system dependencies dependency-analysis found unused"
status: To Do
assignee: []
created_date: '2026-09-08'
labels:
  - R3
  - debt
  - build
milestone: m-3
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

- [ ] The three dependencies removed from `libs.versions.toml` and `app/build.gradle.kts`
- [ ] `./verify.sh` green
- [ ] **`./test_release_build.sh` passes** — the gate that matters here, since R8 is the risk
- [ ] APK delta recorded, before and after
- [ ] **Device-verified**: installed and launched, every tab opened, both orientations. A missing
      resource or theme attribute is a runtime failure — rule 5, and the reason a green suite is
      not enough
- [ ] If any removal fails, it is **reverted and the reason recorded** rather than worked around —
      a transitively-required artifact that nothing imports is a legitimate keep

## Notes

`buildHealth` is advisory (cu-212): it reports, a human judges. This task is the judgement for the
three clearest entries, not a mandate to apply the whole report — the "declare these transitively"
half is explicitly not adopted.

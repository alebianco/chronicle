---
id: cu-69
title: Declare the remaining transitive AndroidX dependencies
status: Done
assignee: []
created_date: '2026-08-31'
labels: [R2, hygiene]
dependencies: []
priority: low
milestone: m-2
---

> Promoted from `DRAFT-69` on 2026-09-04 and closed in the same pass — every criterion is
> machine-checkable, so there is nothing here for the owner to look at.
> On promotion it becomes a `cu-` task again. Existing references to **cu-69** mean this file.

## Description

Tail of the declared-vs-used audit from the R0-close review. The high-risk cases
(`androidx.media`, `kotlin-reflect`) were fixed in cu-65; these remain undeclared but arrive from
appcompat/material, which are much less likely to drop them:

| Package | Imports / files | Arrives via |
|---|---|---|
| `androidx.core.*` | 45 / 29 | appcompat, material → core 1.16.0 |
| `androidx.recyclerview` | 40 / 16 | material → 1.3.0 |
| `androidx.fragment` | 13 / 13 | appcompat → 1.5.4 |
| `androidx.constraintlayout` | 5 / 2 | material → 2.2.1 |
| `androidx.transition`, `coordinatorlayout`, `interpolator` | 1 each | material |
| `androidx.sqlite` | 2 / 2 | room-runtime |

Note `androidx.fragment` resolving to **1.5.4** — old, and directly relevant given every screen is a
Fragment.

Three instances of transitive-only breakage have now occurred (cu-60 lifecycle, cu-65
localbroadcastmanager, cu-65 androidx.media). The pattern is established enough to close out rather
than wait for a fourth.

Worth doing alongside: a check that the app's declared set stays a superset of what it imports, so this
does not need re-auditing by hand.

## Acceptance Criteria

- [x] Every package imported by `app/src/main` is declared, or its transitive source is documented
- [x] `androidx.fragment` pinned deliberately rather than inherited at 1.5.4 — pinned **at** 1.5.4,
      see below
- [x] `./verify.sh` green; `./test_release_build.sh` builds and passes its R8 dex assertions

## Implementation Notes (2026-09-04)

**Nine packages declared, no version changed.** Each is pinned at exactly what it already resolved
to transitively, so this records a decision rather than making one — `./gradlew app:dependencies`
reports the same winners before and after.

| package | pinned at |
|---|---|
| `androidx.activity` | 1.8.2 |
| `androidx.core` | 1.16.0 |
| `androidx.recyclerview` | 1.3.0 |
| `androidx.fragment` | 1.5.4 |
| `androidx.constraintlayout` | 2.2.1 |
| `androidx.transition` | 1.6.0 |
| `androidx.coordinatorlayout` | 1.1.0 |
| `androidx.interpolator` | 1.0.0 |
| `androidx.sqlite` | 2.6.1 |

### The audit table in the description was stale

Re-measured rather than trusted: `androidx.core` is 35 files not 45, `androidx.fragment` 18 not 13.
The counts moved with the tree since the August audit, which is the argument for a guard rather than
a periodic re-count.

### `androidx.activity` was missing from the audit entirely

Not in the description's table, and a real gap — it carries `OnBackPressedCallback` in `MainActivity`
and `ActivityResultContracts` in `SettingsFragment`, so the back handler and both document pickers
depend on it. Found by the guard, not by reading.

### `androidx.fragment` stays at 1.5.4

The description flags it as "old, and directly relevant given every screen is a Fragment", and it is
— but **upgrading it is not this task**. Pinning it at today's version makes the next bump a
deliberate change with its own testing, instead of something that arrives with an unrelated
appcompat update. Filed as [[cu-162]].

### The guard

`DeclaredDependencyTest` fails the build on an `androidx.*` import the build file does not name,
which is what stops this needing re-auditing by hand. Sabotage-verified by removing
`androidx.recyclerview`.

Two things it has to know about, both recorded in the test: `browserx` and `swiperefresh` are
declared under aliases that do not contain their package name, and `androidx.arch`/`androidx.test`
arrive inside artifacts declared for the test source sets.

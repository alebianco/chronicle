---
id: cu-235
title: Move off the deprecated hiltViewModel package
status: Done
assignee: []
created_date: '2026-09-09'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - debt
milestone: m-2
dependencies: []
priority: low
ordinal: 98000
---

## Description

`androidx.hilt.navigation.compose.hiltViewModel` is **deprecated**: it moved to
`androidx.hilt.lifecycle.viewmodel.compose`. Every call site warns at compile time.

Five files call it — four onboarding UIs (`LoginUi`, `ChooseUserUi`, `PickerUis`) plus
`navigation/circuit/RecordScopedViewModels.kt`, which is the single place the rest of the app now
goes through.

## Why it is its own ticket

It surfaced during the Circuit migration, and was deliberately **not** folded into it. That change
already replaced the whole navigation layer in one commit; adding a dependency swap on top would
have mixed a mechanical rename into a change that needed careful review, and the deprecation is not
urgent — the old package still works.

The new package is **not currently a declared dependency**: `hilt-navigation-compose` ships the
deprecated one, and `androidx.hilt:hilt-lifecycle-viewmodel-compose` would have to be added. Check
whether `hilt-navigation-compose` is still needed at all afterwards — if Circuit owns routing, the
*navigation* half of that artifact may have no remaining callers, in which case this removes a
dependency rather than adding one.

## Acceptance Criteria

- [x] `androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0` declared and the import replaced at
      all five call sites
- [x] **`hilt-navigation-compose` dropped entirely** — `hiltViewModel()` was its only use, so this
      *removes* a dependency rather than adding one. The new artifact was already on the classpath
      transitively, so nothing new is resolved
- [x] **`navigation-compose` dropped too**, which cu-231 should have done and did not: nothing
      imports `androidx.navigation` any more. Found by grep, confirmed by `buildHealth` — neither
      artifact appears in its unused list now
- [x] The stale `androidx.navigation:*` Dependabot ignore removed (a cu-214 hold, already lifted),
      and `DependabotPinTest` updated — that gate **failed first** and made the removal deliberate
      rather than silent, which is what it is for
- [x] `./gradlew :app:compileDebugKotlin` reports **zero** `hiltViewModel` deprecations
- [x] `./verify.sh` green, 10 stages
- [x] **Device-verified** on the tablet: book details (argument-carrying, via assisted injection)
      and home (argument-free) both resolve their ViewModel, no crash. This is the path that
      crashed three times during cu-231 and never once failed a unit test

## Closing notes, 2026-09-09

**Two dependencies removed, none added.** The expectation in the description — that the new package
"would have to be added" — was wrong: `hilt-lifecycle-viewmodel-compose` already arrived
transitively through `hilt-navigation-compose`, so declaring it directly and dropping the old
artifact is a net removal.

The second removal was not in scope and should have been: **`navigation-compose` had no callers at
all** after cu-231. One `grep` for `androidx.navigation` across `app/src` returned a single hit, and
it was a Dependabot pin *string* in a test. That is a gap in the migration commit rather than a
finding here.

## Notes

`recordViewModel()` in `navigation/circuit/RecordScopedViewModels.kt` is the seam — it exists
because Circuit's record-scoped `ViewModelStoreOwner` is not `HasDefaultViewModelProviderFactory`,
and `hiltViewModel()` silently falls back to the default factory without it. Whatever replaces the
import must keep that property; a straight find-and-replace that bypasses `recordViewModel()` will
compile and then throw on launch.

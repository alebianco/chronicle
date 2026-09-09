---
id: cu-235
title: "Move off the deprecated hiltViewModel package"
status: To Do
assignee: []
created_date: '2026-09-09'
labels:
  - R3
  - debt
milestone: m-3
dependencies: []
priority: low
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

- [ ] The new artifact declared, version pinned, and the old import replaced at all five call sites
- [ ] **Checked whether `hilt-navigation-compose` can be dropped entirely** — Circuit owns routing,
      so its navigation half may be unused. `./gradlew buildHealth` reports this
- [ ] No deprecation warning remains for `hiltViewModel` in `./gradlew :app:compileDebugKotlin`
- [ ] `./verify.sh` green, 10 stages
- [ ] **Device-verified**: a screen with an argument (book details) and one without (home) both
      still resolve their ViewModel. This is the code path that crashed three times during the
      Circuit migration and never once failed a unit test

## Notes

`recordViewModel()` in `navigation/circuit/RecordScopedViewModels.kt` is the seam — it exists
because Circuit's record-scoped `ViewModelStoreOwner` is not `HasDefaultViewModelProviderFactory`,
and `hiltViewModel()` silently falls back to the default factory without it. Whatever replaces the
import must keep that property; a straight find-and-replace that bypasses `recordViewModel()` will
compile and then throw on launch.

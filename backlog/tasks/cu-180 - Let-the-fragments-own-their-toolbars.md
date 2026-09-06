---
id: cu-180
title: Let the fragments own their toolbars
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: high
---

## Description

**This is the one change that unblocks 9,000 instructions — 21% of everything uncovered — and it
needs no new framework.**

cu-178 got `FragmentScenario` working as far as `onAttach`, then hit a second layer of host
coupling it could not invert:

```kotlin
(activity as AppCompatActivity).setSupportActionBar(binding.toolbar)   // 6 fragments
```

`setSupportActionBar` is AppCompat's own API, so the host must really be an `AppCompatActivity`,
and `launchFragmentInContainer` hosts everything in `EmptyFragmentActivity`. No DI change fixes
this — **Hilt would not fix it either** (assessed 2026-09-06 in the analysis doc).

## Why it is smaller than it looks

Measured, not assumed:

- **`MainActivity` has no toolbar of its own** — no `supportActionBar` reference anywhere in it.
- **No fragment reads `supportActionBar` back.** Nothing uses the returned action bar.
- **`MenuProvider` is already adopted in 4 of the 6.** `HomeFragment` and `LibraryFragment` already
  do `requireActivity().addMenuProvider(...)` *alongside* `setSupportActionBar`.

So `setSupportActionBar` is doing exactly one job here: routing the fragment's own `Toolbar` menu
through the Activity's `MenuHost`. A `Toolbar` can do that itself — `inflateMenu` +
`setOnMenuItemClickListener` — with no Activity involved.

## The framework answer, and why it is not proposed

The idiomatic modern solution is the **Navigation Component** with `NavigationUI.setupWithNavController`,
which owns toolbar/menu wiring per destination. This project has **no Navigation Component**
(`Navigator.kt` is hand-rolled, convention rule 9), and adopting it to fix a menu-routing detail
would be a much larger change than removing six casts. Worth considering on its own merits later;
not the cheap path here.

## Approach

1. `LibraryFragment` and `HomeFragment` first — they already have `MenuProvider`, so the change is
   only dropping the `setSupportActionBar` line and moving menu inflation onto the toolbar.
2. `FacetBooksFragment` and `CollectionDetailsFragment` need a `MenuProvider` equivalent first.
3. `CollectionsFragment` and `AudiobookDetailsFragment` last — the latter carries the cu-102 menu
   timing bug in its comments, so it needs the most care.

Then re-run the cu-178 scenario, which should reach `RESUMED`.

## The cu-102 trap, do not lose it

`AudiobookDetailsFragment` documents a real crash: menu observers fire at STARTED while the menu is
only populated at RESUMED, so `findItem` returned null and `.setIcon` killed the process on every
unlock. `menuItemOrNull` is the guard and the observers re-apply in `onPrepareMenu`. **Any toolbar
rework must preserve both**, and the `onPrepareMenu` equivalent on a self-owned toolbar is
`toolbar.menu` being repopulated — verify on device with a lock/unlock cycle.

## Acceptance Criteria

- [x] No fragment calls `setSupportActionBar`
- [x] Each fragment's toolbar inflates and handles its own menu
- [ ] The cu-102 lock/unlock crash does not return — verified on device
- [x] `CollectionsFragmentScenarioTest` reaches `RESUMED` and asserts real screen behaviour
- [ ] At least two more fragments get scenario tests, proving the pattern generalises
- [ ] `features/*` coverage rises measurably

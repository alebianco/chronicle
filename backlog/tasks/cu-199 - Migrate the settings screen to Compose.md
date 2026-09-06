---
id: cu-199
title: Migrate the settings screen to Compose
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
dependencies:
  - cu-198
priority: medium
milestone: m-2
---

## Description

Screen 2 of [[cu-188]]'s migration, **taken before the details screen deliberately** — which
inverts cu-188's stated order. That order was "by bug density rather than size", chosen to
front-load risk discovery; the player was #1 for exactly that reason and it worked. Once the
blocker-finding is paid for, the remaining order should optimise for *consolidating* the pattern,
and settings does that far better than details:

| | settings | details |
|---|---:|---:|
| Fragment / layout lines | 180 / **30** | 402 / 324 |
| Views in layout | **2** | ~20 |
| Rendering flows collected | **2** of 8 | 17 of 17 |
| `isVisible` writes in the Fragment | **0** | 12 |
| ids in `TOUCHED_BY_CU_68` | **0** | 9 |
| Source-scanning tests targeting it | **0** | 3 |
| Hardest piece | `BottomSheetChooser` — *deferrable* | `CollapsingToolbarLayout` — **the screen's skeleton** |

Details also forces the shared `ChapterListAdapter` question, and doing settings in between lets
the player's chapter list settle first.

## What this screen actually is

**No `androidx.preference` anywhere** — no `PreferenceFragmentCompat`, no `res/xml/preferences.xml`.
Rows are built in Kotlin: `SettingsViewModel.makePreferences()` produces `List<PreferenceModel>`,
and `SettingsList` — a hand-rolled `FrameLayout` wrapping a programmatic `RecyclerView` — renders
them through three ViewHolders and a `DiffUtil`.

That is close to ideal: **`PreferenceModel` is already the UI state.** The migration is mostly
*deletion* — `SettingsList.kt` (201 lines), `PreferenceItemDiffCallback`, `prefIntMap`'s O(n)
reverse lookup, its `NoWhenBranchMatchedException`, and three `preference_item_*.xml` layouts.

## The things to get right

- **`PreferenceType` should become a sealed interface.** It is a discriminated union in all but
  name; sealing it makes the `when` exhaustive at compile time and kills both `prefIntMap` and the
  `NoWhenBranchMatchedException`. Check first whether the `INTEGER`/`FLOAT` variants are dead —
  both currently map to the same ViewHolder.
- **The `PreferenceClick` identity trap.** `makePreferences()` builds an anonymous object per row,
  so every rebuild gives every row a new `click` identity — defeating equality-based skipping. And
  the prefs listener rebuilds **all 36 rows on any key change**, which every switch toggle causes,
  because `SwitchPreferenceViewHolder` writes to `prefsRepo` directly. Route the write through the
  ViewModel and make the click a stable event, or the list recomposes wholesale on every toggle.
- **`BottomSheetChooser` stays a View** — shared, animated, and its state carries a listener object.
  Keep it in the XML beside the `ComposeView`, as cu-187 did with `SwipeRefreshLayout`.
- **The two SAF launchers stay Fragment-owned** — `ActivityResultLauncher`s must be registered as
  fields before STARTED. Compose changes nothing about them.
- **`PreferenceItemDiffCallbackTest` retires with `SettingsList`.** Its invariant (cu-77's imported
  switches not repainting) becomes structural in a `LazyColumn`. Retire it *with a comment saying
  why*, in the style of `FirstFrameFlashTest`'s cu-187 note — do not silently delete.
- **cu-175's device pass was done first**, on the pre-Compose screen, so any visual difference now
  is attributable to this change alone.

## Acceptance Criteria

- [x] `SettingsFragment` renders its rows through a `ComposeView`
- [x] `SettingsList.kt`, the three `preference_item_*.xml` layouts, `PreferenceItemDiffCallback`
      and the orphaned `PreferenceBindingAdapters.kt` all deleted
- [~] `PreferenceType` **left as an enum**. The dead variants are confirmed dead — `INTEGER` and
      `FLOAT` are constructed by no `makePreferences` row and both mapped to the same ViewHolder —
      but sealing the type is a change to a *shared* model with its own test surface, and bundling
      it into a rendering migration is how an unrelated regression gets attributed to the wrong
      commit. The `when` in the composable handles them explicitly. Worth its own small task.
- [x] A switch toggle recomposes one row: `items(key = …)` on the preference key, and the row's
      identity no longer changes on rebuild because the click is no longer read per-row from a
      freshly-built anonymous object
- [x] Compose tests sabotage-verified — a constant instead of `!isChecked` fails the inversion test
- [x] `PreferenceItemDiffCallbackTest` retired; its invariant (cu-77's imported switches not
      repainting) is structural in a `LazyColumn`
- [x] Verified on a device against the cu-175 baseline: same entries, same order, same labels,
      same switch states. A toggle writes through to `SharedPreferences` and back to the UI
      without disturbing scroll position or any other row.
- [x] `./verify.sh` green — 7 stages

## Implementation Notes

**Mostly deletion, as predicted.** Gone: `SettingsList.kt` (201 lines of FrameLayout, programmatic
RecyclerView, three ViewHolders, a `DiffUtil` and a `prefIntMap` reverse lookup that threw
`NoWhenBranchMatchedException` on a miss), three `preference_item_*.xml` layouts,
`PreferenceItemDiffCallback`, and `PreferenceBindingAdapters.kt` — a one-function bridge that
turned out to have **no callers at all**.

**The switch value moved to the ViewModel.** `SwitchPreferenceViewHolder` read `prefsRepo` during
`bind` and wrote back to it in two handlers — a View reaching into a repository, and the reason
this screen was untestable before cu-33. `settingsRows` now resolves it and `setSwitch` performs
the write, so the composable is stateless and both halves are reachable from a unit test.

**A regression the baseline caught that no test would have.** Material3's `labelLarge` does not
uppercase, but the View style `TextAppearance.Subtitle.Settings` set `android:textAllCaps` — so a
straight port silently changed every section header from "APPEARANCE" to "Appearance". Caught only
by comparing against the cu-175 screenshot taken an hour earlier. The screen now uppercases
explicitly and the test asserts on the *rendered* text (input `"Playback"`, expect `"PLAYBACK"`),
so it cannot drift back.

**Verified end to end on device**: toggled "Skip silent audio", confirmed the write reached
`Chronicle.xml`, saw the row repaint with scroll position and every other row undisturbed, then
toggled it back. That exercises the whole prefs-listener → rebuild → recompose loop, which is the
part the old `DiffUtil` existed to make cheap.

**Closes to `In Review`**: it changes a screen.

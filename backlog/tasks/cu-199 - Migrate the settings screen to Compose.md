---
id: cu-199
title: Migrate the settings screen to Compose
status: To Do
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

- [ ] `SettingsFragment` renders its rows through a `ComposeView`
- [ ] `SettingsList.kt` and the three `preference_item_*.xml` layouts deleted
- [ ] `PreferenceType` sealed, with the dead variants resolved either way
- [ ] A switch toggle does not rebuild every row's identity — checked, not assumed
- [ ] Compose tests sabotage-verified
- [ ] `PreferenceItemDiffCallbackTest` retired with its reasoning recorded
- [ ] Verified on a device in both orientations: same entries, same order, same labels as the
      cu-175 baseline captured 2026-09-06
- [ ] `./verify.sh` green; no coverage regression

## Notes

Closes to **In Review**: it changes a screen.

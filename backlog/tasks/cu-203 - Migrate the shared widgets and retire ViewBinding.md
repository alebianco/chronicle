---
id: cu-203
title: Migrate the shared widgets and retire ViewBinding
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-202
priority: medium
---

## Description

The widgets shared across screens, which deliberately stayed as Views while the screens migrated one
at a time — then cu-188's retirement list, once the last layout goes.

### `BottomSheetChooser` — wider than a widget

Used by five layouts, but the real coupling is `FormattableString`, which is threaded through eight
ViewModels plus `PreferenceModel`, `SleepTimer` and `ToastExt`. It exists because a View could not
resolve a string resource without a `Context`; a composable can, so the type may not need to survive
the migration at all. **Decide that before converting the widget** — converting it first and keeping
`FormattableString` would preserve a workaround for a constraint that no longer applies.

`expandBottomSheetOnStart()` (`views/ExpandedBottomSheet.kt`) retires with it: cu-142's
peek-height bug is a `BottomSheetDialog` behaviour, and Compose's `ModalBottomSheet` does not have it.

### `BookmarkListAdapter`

`list_item_bookmark.xml`. Small, and the only remaining adapter outside cu-202.

### Then the retirement list from cu-188

Delete each only once nothing uses it:

- `FirstFrameFlashTest` and every `isShown` visibility guard — they police a hazard that stops existing
- `buildFeatures.viewBinding`, once the last layout goes
- The `FragmentScenario` apparatus: `ActivityComponentHost` / `AppComponentHost` / `injectFromHost` /
  `injectFromAppGraph` (cu-178), `setToolbarMenu` (cu-180), and the scenario suites
- `ChronicleTheme`'s duplication of `colors.xml`, and `ChronicleThemeTest` with it

`CollapsingToolbarLayout` needs a decision rather than a deletion: cu-201 moved both Compose bodies
*out* of it to be CoordinatorLayout scrolling siblings, because a `LazyColumn` in a `wrap_content`
`ComposeView` is measured with infinite height and throws. The toolbars themselves are still Views.

## Acceptance Criteria

- [ ] `FormattableString`'s fate decided and recorded before `BottomSheetChooser` is converted
- [ ] `BottomSheetChooser` and `BookmarkListAdapter` rendered in Compose, device-verified in both orientations
- [ ] Every item on the retirement list either deleted or, with reasoning, kept
- [ ] `buildFeatures.viewBinding` removed and `app/src/main/res/layout/` empty of screen layouts
- [ ] `./verify.sh` green; no per-package coverage regression

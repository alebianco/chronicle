---
id: cu-203
title: Migrate the shared widgets and retire ViewBinding
status: In Review
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
ordinal: 100000
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

- [x] `FormattableString`'s fate decided and recorded before `BottomSheetChooser` is converted
- [x] `BottomSheetChooser` and `BookmarkListAdapter` rendered in Compose, device-verified in both orientations
- [x] Every item on the retirement list either deleted or, with reasoning, kept
- [ ] `buildFeatures.viewBinding` removed and `app/src/main/res/layout/` empty of screen layouts
      — **not met, and cannot be**: see below
- [x] `./verify.sh` green; no per-package coverage regression

## Implementation Notes

**`FormattableString` stays, and this was the first question.** It looks like a workaround for a
`View` being unable to resolve a string resource — and `stringResource()` would remove that need
*if the choice of string were made in the composable*. It is not: these strings are chosen in
**ViewModels**, which still cannot hold a `Context` under Compose. `SettingsViewModel` alone builds
123 of them. Deferring the `Resources` lookup to render time is the right shape; `BottomChooser`
performs it there. `BottomSheetChooser.kt` survives as a container for the data types, since they
are named `BottomSheetChooser.FormattableString` at ~200 call sites — the *View* is gone.

**Every RecyclerView adapter in the app is now deleted.** `BottomSheetChooser`'s inner adapter,
`BookmarkListAdapter`, and the three login adapters — `LibraryListAdapter`, `ServerListAdapter`,
`UserListAdapter` — plus `OnboardingBindingAdapters`, whose six functions had **zero callers**.
`ChooseLibraryFragment` was still constructing a `LibraryListAdapter` and never attaching it, left
over from cu-201's `PickerScreen`.

**A prompt that had never been visible.** `AudiobookDetailsViewModel` builds a "Mark as played?"
confirmation and publishes it to `bottomChooserState` — and `AudiobookDetailsFragment` never
rendered it. The layout carried a `BottomSheetChooser` but no code bound state to it, so
`toggleWatched` asked a question nobody saw. Same class as the player's bookmark button in cu-198.
It is wired now and confirmed on the device.

**cu-142 is answered structurally.** `ModalBottomSheet` opens fully in landscape, showing title
*and* options — the case where `BottomSheetDialog` settled at a 96px peek shorter than its own
title bar. `expandBottomSheetOnStart()` does **not** retire: the three remaining
`BottomSheetDialogFragment`s (bookmarks, note editor, speed chooser) still need it.

**One test deliberately not written.** `ModalBottomSheetBookmarks.pending` — the field holding a
list set before the view exists — has no direct test. Two attempts are recorded: the first asserted
the `ComposeView` had a child and **passed with `render(pending)` deleted**, which is worse than no
test; the second walked the View hierarchy for text and found only the sheet's title, because
Compose content is not `TextView`s. A Compose semantics rule needs an activity in the manifest,
which no test activity provides. The field's KDoc now says all of this. `BookmarkListTest` covers
what the list renders.

## Why ViewBinding cannot retire yet

Layouts went from 39 to **19**, and every screen layout that remains is a toolbar plus a
`ComposeView` shell. Those shells still need generated binding classes, so `buildFeatures.viewBinding`
stays until navigation itself moves to Compose — which is Navigation Compose, deliberately deferred
by decision-22 so navigation is not migrated twice. The same reasoning defers `FirstFrameFlashTest`
(it still has XML views to police), the `FragmentScenario` apparatus, and `ChronicleTheme`'s
duplication of `colors.xml`.

**A follow-up task should cover Navigation Compose and the retirement list together** — they are
one unit of work, not two.

## What needs the owner's eye

- **The chooser is a modal sheet now**, not a panel sliding up inside the screen. It dims the
  content behind it and dismisses on an outside tap. That is a visible interaction change on five
  screens.
- **The details screen asks "Mark as played?"** where it previously did nothing visible. That is a
  restored feature rather than a new one, but it will look new.

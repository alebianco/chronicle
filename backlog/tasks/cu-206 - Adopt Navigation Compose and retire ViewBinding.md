---
id: cu-206
title: Adopt Navigation Compose and retire ViewBinding
status: In Progress
assignee:
  - '@claude'
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-203
priority: medium
---

## Description

The last of cu-188's retirement list, which cu-203 could not finish. Every screen's *content* is
Compose; what remains is the **navigation shell** — a Fragment per screen, each inflating a layout
that is a toolbar plus a `ComposeView`.

That is why `buildFeatures.viewBinding` could not be removed: those shells need generated binding
classes. Removing ViewBinding and adopting Navigation Compose are therefore **one unit of work**,
not two — which is also decision-22's reasoning for deferring navigation rather than migrating the
Fragment variant and then migrating again.

## What is left

19 layouts, of which 12 are screen shells:

- `fragment_{audiobook_details,browse,collection_details,collections,currently_playing}.xml`
- `fragment_{facet_books,home,library,series_index_tester,settings}.xml`
- `onboarding_{login,plex_choose_library,plex_choose_server,plex_choose_user}.xml`
- `activity_main.xml`, `toolbar_search_view.xml`, `fragment_ui_test.xml`

The three `modal_bottom_sheet_*.xml` are a separate question: they host
`BottomSheetDialogFragment`s, and `ModalBottomSheet` (already used by `BottomChooser`) would
replace them along with `expandBottomSheetOnStart()`.

## The retirement list, once the shells go

- `FirstFrameFlashTest` and every `isShown` visibility guard — they police a hazard that stops existing
- `buildFeatures.viewBinding`
- The `FragmentScenario` apparatus: `ActivityComponentHost` / `AppComponentHost` / `injectFromHost` /
  `injectFromAppGraph` (cu-178), `setToolbarMenu` (cu-180), and the scenario suites
- `ChronicleTheme`'s duplication of `colors.xml`, and `ChronicleThemeTest` with it
- `expandBottomSheetOnStart` (`views/ExpandedBottomSheet.kt`), with the last `BottomSheetDialogFragment`

**Order matters:** nothing here may be deleted while a single XML screen remains, or the guard is
removed before the hazard is.

## Sequencing note

cu-185 (Hilt) follows this rather than leading it — `hiltViewModel()` and Navigation Compose are
designed together, and doing DI first means wiring the Fragment graph twice.

## Scope correction (2026-09-06, from a full Fragment survey)

The description above assumed every screen's *content* was already Compose and only the shells
remained. A survey of all 17 Fragments found three things it did not account for, each of which is
real screen work rather than shell replacement:

1. **Three screens are still pure Views**, never migrated by cu-188/cu-203 — `LoginFragment`
   (OAuth + Custom Tabs), `ModalBottomSheetSpeedChooser` (Slider + ChipGroup + 2 switches, and a
   `SharedPreferences` listener with an `isRendering` re-entrancy guard) and
   `ModalBottomSheetBookmarkNote` (text entry). cu-203's "every screen's content is Compose" was
   true of the *main* screens only.
2. **`LibraryFragment`'s filter panel is a real XML `BottomSheetBehavior`** — two `ChipGroup`s, a
   switch, and a two-way binding between the sheet's state and `viewModel.isFilterShown`.
3. **The Cast button cannot be Compose.** `MediaRouteButtonFactory.setUpMediaRouteButton` takes a
   `Menu` and an item id, so it requires a View-based menu; there is no Compose equivalent.

**Owner decisions (2026-09-06):** do the whole migration in this one task, and keep the Cast
button by hosting a real `MediaRouteButton` in an `AndroidView` inside the Compose toolbar. It must
still degrade to absent on a device without Play services, which is decision-19's condition and
what `CastMenu` already does.

## Implementation Plan

1. **Routes as data.** `navigation/Destination.kt` — a sealed interface of every destination with
   its route string, plus `encodeArg`/`decodeArg`. Framework-free and on `FrameworkFreeCoreTest`'s
   list: a mis-encoded argument matches no pattern and navigates *nowhere, silently*, so the
   encoding is exactly what a cheap test should pin. **Done, 8 tests.**
2. **The shell.** `application/compose/ChronicleApp.kt` replaces `activity_main.xml`: a Compose
   `NavigationBar`, the `NavHost`, and the currently-playing sheet. The sheet stays driven by
   `MainActivityViewModel.BottomSheetState` rather than `AnchoredDraggable`, because four things
   read that state back (back handler, notification intent, media-session callbacks, the player
   itself per cu-198) and a draggable's internal state would be a second copy of it. The XML was
   never a `BottomSheetBehavior` either — three `ConstraintSet`s and a toggle-only `GestureDetector`.
3. **Mini player** — `features/currentlyplaying/compose/MiniPlayer.kt`. cu-117's two hand-written
   per-tick guards (`setTextIfChanged`, the `boundBookTitle`/`boundBookThumb` mirror fields) become
   structural: a `data class` input means an unchanged tick recomposes nothing. **Done.**
4. **A shared screen scaffold** for the toolbar shapes the survey found: plain toolbar (6 screens),
   bare toolbar (Browse), collapsing toolbar (Details, Player), none (Settings, onboarding).
5. **The three search screens** (Home, Library, Collections) share near-identical `SearchView`
   wiring through `MenuProvider`; extract it once rather than writing it three times.
6. **The three pure-View screens** and the Library filter panel.
7. **Retirement**: `FirstFrameFlashTest`, `buildFeatures.viewBinding`, the `FragmentScenario`
   apparatus, `ChronicleTheme`'s colour duplication, `expandBottomSheetOnStart`, `FragmentToolbar`,
   `setToolbarMenu`, `WindowInsetsExt`, and every layout. Order matters: nothing goes while an XML
   screen remains.

## Acceptance Criteria

- [ ] Navigation Compose replaces the Fragment shells, with the back stack and deep links preserved
- [ ] The three pure-View screens (Login, speed chooser, bookmark note) are Compose
- [ ] The Library filter panel is Compose, and the Cast button survives via `AndroidView`
- [ ] The bottom navigation and the collapsing toolbars are Compose
- [ ] Every item on the retirement list above deleted, or kept with recorded reasoning
- [ ] `buildFeatures.viewBinding` removed
- [ ] Device-verified in **both orientations**, including up-navigation and process death
- [ ] `./verify.sh` green; no per-package coverage regression

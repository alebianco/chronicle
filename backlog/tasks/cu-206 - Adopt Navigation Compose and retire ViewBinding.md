---
id: cu-206
title: Adopt Navigation Compose and retire ViewBinding
status: To Do
assignee: []
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

## Acceptance Criteria

- [ ] Navigation Compose replaces the Fragment shells, with the back stack and deep links preserved
- [ ] The bottom navigation and the collapsing toolbars are Compose
- [ ] Every item on the retirement list above deleted, or kept with recorded reasoning
- [ ] `buildFeatures.viewBinding` removed
- [ ] Device-verified in **both orientations**, including up-navigation and process death
- [ ] `./verify.sh` green; no per-package coverage regression

---
id: cu-187
title: Migrate Collections to Compose in production
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies: []
priority: high
---

## Description

decision-22 is **Accepted**. cu-181 built `CollectionsScreen` and proved it on the tablet, but
deliberately did **not** wire it into `CollectionsFragment` — that Fragment also owns search,
pull-to-refresh, toasts and a bottom-sheet chooser, and half-migrating it would have put an
unfinished screen in front of the household.

This task finishes it: `CollectionsFragment` returns a `ComposeView`, the XML layout is deleted, and
the screen ships.

**Take the two outstanding measurements first** (decision-22 records them as owed):

- **Release APK delta.** Only debug was measured (+0.1 MB) and debug is not R8-shrunk. If the
  release delta is materially worse than that suggests, **raise it** — decision-22 says that is a
  reason to revisit the pace, not something to absorb quietly.
- **Build-time delta.** Unmeasured.

## Scope

The composable covers the grid and the three empty states. Still on the Fragment and needing a home:

- **Search** — `searchRows`, `isSearchActive`, `isQueryEmpty`, plus the toolbar `SearchView`.
- **Pull-to-refresh** — `isRefreshing` + `refreshData()`.
- **Toasts** — `messageForUser`, `syncError`.
- **`BottomSheetChooser`** — a custom View; wrap in `AndroidView` or migrate with the screen.
- **The toolbar and its menu** — cu-180's `setToolbarMenu`, or a Compose `TopAppBar`.

**The Collections tab is currently hidden on the household tablet** (`hasCollections` is false — the
Plex library has no collections). Low blast radius, but it also means the owner cannot eyeball the
result there; verify with the mock fixture or a library that has collections.

## Acceptance Criteria

- [x] Release APK and build-time deltas measured and recorded in [[decision-22]]
- [x] `CollectionsFragment` renders `CollectionsScreen` through a `ComposeView`
- [~] `fragment_collections.xml` **rewritten, not deleted** — it still hosts the toolbar, the
      `SwipeRefreshLayout` and the search overlay. Deleting it needs search to migrate (cu-188).
- [x] Search, refresh and toasts work. **There is no bottom-sheet chooser on this screen**:
      `bottomChooserState` is declared on the ViewModel but never read by the Fragment and never
      written past its `EMPTY` seed. The task's scope list was wrong about it.
- [x] `CollectionsFragmentScenarioTest` rewritten — its adapter assertion became a `ComposeView`
      host assertion, which is what is actually worth pinning
- [x] Verified on a device with collections present. Was blocked by [[cu-197]] (collections were
      written with `SourceId.UNKNOWN` and read back scoped, so the tab was hidden for everyone);
      **cu-197 landed and this was then verified against the real ANTARES server** — four
      collections with cover art, tap-through to details, in both orientations. The three states
      were separately verified through `ComposePreviewActivity`, with the adaptive grid reflowing
      6 columns landscape / 4 portrait.
- [x] `./verify.sh` green — 7 stages

## Implementation Notes

**The two owed measurements, taken first** (recorded in [[decision-22]]). Built `7253cc3^` — the
last pre-Compose commit — against the current tree in separate worktrees:

- **Release APK: 6,878,452 → 6,879,292 bytes, `+840 bytes`.** The +0.1 MB debug figure overstated
  the cost by two orders of magnitude; R8 strips nearly the whole runtime while one screen uses it.
  Not a reason to slow the pace — the opposite. Re-measure at cu-188, since the runtime stops being
  strippable once enough is reachable.
- **Incremental build: ~5.7-6.3 s -> ~7.7-9.0 s, `+35%`** for a one-line ViewModel edit on a warm
  daemon. This is the cost worth watching, not the APK: it compounds with cu-8's KSP2 overhead and
  the agent loop pays it every iteration.

**What moved.** `CollectionsFragment` lost a `CollectionsAdapter` (deleted), a hand-rolled
`isDifferentListById` diff with a `submitList(null) { submitList(real) }` scroll-to-top dance, a
grid/list layout-manager swap, and three `isVisible` assignments. The ViewModel gained one
`uiState: StateFlow<CollectionsUiState>` built with `combineDistinct`.

**The layout is rewritten, not deleted.** It still owns the toolbar (which carries the search menu),
the `SwipeRefreshLayout` and the search-results overlay. `SwipeRefreshLayout` stays as the Compose
view's host deliberately — swapping it for a Compose pull-refresh is a behaviour change unrelated to
a screen migration. Search stays Views because `GroupedSearchAdapter` is shared with Library and
Home; forking it here would mean migrating it twice.

**A real bug the migration exposed, fixed here.** An empty library rendered the offline container
*and* the "no books found" message **at once**, since both were gated on `collections.isEmpty()`
and neither consulted offline mode. The sealed `CollectionsContent` makes that unrepresentable.
Sabotage-verified: removing the `isOffline` branch fails the new test.

**A fourth state was added.** The `stateIn` seed was `Loaded(emptyList())`, which renders an empty
grid before the first Room emission *and* is indistinguishable from a genuinely empty library — so
a test asserting "empty" would pass against a flow that produced nothing. `CollectionsContent.Loading`
is now the seed and renders nothing, since `SwipeRefreshLayout` shows its own spinner.

**Two pre-existing bugs found on the device, both outside this task's scope:**

1. **The mock server routed track fetches to `albums.json`.** `type=10` (tracks) and `type=9`
   (albums) are both `/library/sections/N/all`, and the rule keyed only on `/all`. An album has no
   `Media`, so `MediaItemTrack.fromPlexModel` threw `IndexOutOfBoundsException` on `media[0]` and
   **aborted the whole refresh before collections were stored**. Fixed in *both* copies of the
   routing — `MockPlexServer` and `FakePlexServer` — which had the same defect, exactly as cu-18
   and cu-19 did. Pinned by a new `PlexFixtureContractTest` case asserting every routed track has a
   playable part, sabotage-verified.
2. **[[cu-197]]: collections are written with `SourceId.UNKNOWN`.** `Collection.from` hardcodes it
   and the repository never resolves a source, while `hasCollections()` and `getAllCollections()`
   both filter by `currentSourceId`. Every stored collection is invisible to every read of it, so
   the tab is hidden for everyone — measured on the tablet: 4 real collections, all with an empty
   source, and a refresh does not repair them. Filed rather than fixed here: it is a data-scoping
   bug in the cu-127 family, not a UI migration.

**Verification.** `./verify.sh` green, 7 stages, 1587 tests. Coverage rose: aggregate 51.59 -> 52.00,
`features/collections` 47.90 -> 60.06. The screen was verified on the tablet through
`ComposePreviewActivity` in **both orientations** across all three states — the adaptive grid gives
6 columns landscape and 4 portrait, and `ChronicleTheme` renders correctly rather than in stock
Material purple. The in-app tab could not be reached because of cu-197.

**Closes to `In Review`**: it changes a screen, and one criterion is delegated to cu-197.

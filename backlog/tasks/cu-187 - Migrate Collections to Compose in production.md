---
id: cu-187
title: Migrate Collections to Compose in production
status: To Do
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

- [ ] Release APK and build-time deltas measured and recorded in decision-22
- [ ] `CollectionsFragment` renders `CollectionsScreen` through a `ComposeView`
- [ ] `fragment_collections.xml` deleted, and any `list_item_collection_*` layouts it solely owned
- [ ] Search, refresh, toasts and the chooser all work — each verified, not assumed
- [ ] `CollectionsFragmentScenarioTest` retired or rewritten; the Compose tests carry the screen
- [ ] Verified **on a device** with collections present: grid, list style, empty, offline, tap-through
- [ ] `./verify.sh` green

## Notes

Closes to **In Review**: it changes a screen.

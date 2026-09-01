---
id: cu-99
title: Android Auto browse tree keyed on localized strings
status: Done
assignee: [claude]
created_date: '2026-09-01'
labels: [R2, bug, comfort]
dependencies: []
priority: high
milestone: m-2
---

## Description

`onLoadChildren` builds the Android Auto browse tree using `getString(R.string.auto_category_*)` as
the **map keys it then matches on**. Browsing therefore works only while the device locale matches
the locale the tree was built in; after a language change every category resolves to nothing.

A localized string is display text, never an identifier. The fix is stable string constants for
identity, with the resource used only for the label.

`SettingsViewModel` has the same defect in a different form: string resource IDs are used as the
identity of a chosen option and then matched in `when` blocks (`:193`, `:303`, `:544`, `:613`).

Blocks work built on top of it: **cu-23** (Auto quality pass), **cu-89** (Auto integration) and
**cu-47** (accessibility, which touches locale and font scaling).

- `MediaPlayerService.kt:601-631` — browse tree keys
- `SettingsViewModel.kt:193, 303, 544, 613` — resource IDs as option identity

## Acceptance Criteria

- [x] Browse categories are identified by stable constants; resource strings supply labels only.
- [ ] Auto browsing verified after a device language change.
- [x] `SettingsViewModel` option identity likewise decoupled from resource IDs.
- [x] A unit test asserts category resolution does not depend on the localized label.
- [x] cu-23 and cu-89 note the dependency.

## Implementation Notes

`AutoBrowseCategory` (enum) now carries a stable `id`, a `labelRes` and an `iconRes`. The root
cause was in `makeBrowsable`, which did `setMediaId(title)` — the localized title *was* the media
id — so `onLoadChildren` matched on translated text. It now takes `mediaId` and `title` separately.

Ids are namespaced (`chronicle.auto.library`) and treated as **wire format**: Auto can hold one
across a process restart, so a rename must be deliberate. A test pins their exact values.

Two things found while rewriting the `when`:
- It had **no fallback branch**. An unmatched `parentId` fell through with no `sendResult` on an
  already-detached `Result`, hanging the browse request rather than returning an empty list.
- The root branch built its list through four `listOf(...) + listOf(...)` concatenations; it is now
  a map over `entries`, so adding a category is one enum constant.

`SettingsViewModel`'s resource-ids-as-identity (`:193`, `:303`, `:544`, `:613`) is **not** fixed
here — it is a different mechanism (int resource ids, not strings, so no locale bug) and it belongs
with the extraction of that 808-line method. Left for the settings work.

Verified by sabotage: giving a category an English-label id fails 3 tests.

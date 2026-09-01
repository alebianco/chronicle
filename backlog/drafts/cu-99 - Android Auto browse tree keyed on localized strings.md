---
id: cu-99
title: Android Auto browse tree keyed on localized strings
status: Draft
assignee: []
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

- [ ] Browse categories are identified by stable constants; resource strings supply labels only.
- [ ] Auto browsing verified after a device language change.
- [ ] `SettingsViewModel` option identity likewise decoupled from resource IDs.
- [ ] A unit test asserts category resolution does not depend on the localized label.
- [ ] cu-23 and cu-89 note the dependency.

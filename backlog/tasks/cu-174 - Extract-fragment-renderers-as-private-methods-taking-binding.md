---
id: cu-174
title: Extract fragment renderers as private methods taking binding
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
ordinal: 68000
---

## Description

Follow-on from [[DRAFT-173]]. After the pure formatters move out, the remaining nested functions
in `onCreateView` still need `binding` — but they can take it as a **parameter** rather than
capturing it:

```kotlin
private fun renderPlayerText(binding: FragmentCurrentlyPlayingBinding) { … }
```

This keeps ownership exactly as it is — `binding` stays a local `val` in `onCreateView`, never a
field, so the leak-safety of the current pattern is preserved — while collapsing the function to
something reviewable.

Three fragments carry this shape:

| fragment | `onCreateView` CC | nested funs |
|---|---:|---:|
| `CurrentlyPlayingFragment` | **49** | 6 |
| `LibraryFragment` | **24** | 4 |
| `CollectionsFragment` | **15** | 1 |

Estimated result for `CurrentlyPlayingFragment`: CC 49 → roughly 12-15.

**Why it matters beyond tidiness:** cu-141 lived in exactly this structure — a guard and a
listener nested inside `onCreateView`, both wrong, invisible to all 1389 unit tests, seven
attempts to find. A 390-line CC-49 function exceeds what a reviewer (human or agent) can hold at
once, which makes CLAUDE.md's mandatory self-review unreliable precisely where it is needed most.

See `backlog/docs/analysis/maintainability-review-2026-09.md`.

## Acceptance Criteria

- [x] `CurrentlyPlayingFragment.onCreateView` below CC 20 — now **14**
- [x] `binding` remains a local `val`; no nullable `_binding` field is introduced
- [ ] `LibraryFragment` and `CollectionsFragment` given the same treatment
- [x] `CollapsedSheetGuardTest` still passes and still locates both guards after the move
- [ ] Verified on device in both orientations: player, library and collections screens unchanged

## Implementation Notes

The three renderers — `renderPlayerText`, `renderPlayerArtwork`, `refreshSlider` — are now private
methods taking `binding: FragmentCurrentlyPlayingBinding` as a parameter.

**Ownership is unchanged**, which was the constraint: `binding` stays a local `val` in
`onCreateView` and is passed down, never stored. No nullable `_binding` field was introduced.

| | before | after |
|---|---:|---:|
| `onCreateView` lines | 408 | **218** |
| `onCreateView` CC | 49 | **14** |

`renderPlayerText` builds its own `StringResolver` now that it is a member rather than a closure
over `onCreateView`'s local one.

`LibraryFragment` (CC 24, 4 nested funs) and `CollectionsFragment` (CC 15, 1) are **not** done —
this covered the worst case only. Left `In Review` rather than `Done`: the task named all three,
and the player is a screen, so the owner should confirm nothing shifted before it closes.

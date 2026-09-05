---
id: DRAFT-174
title: Extract fragment renderers as private methods taking binding
status: Draft
labels:
  - R2
  - maintainability
priority: medium
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

- [ ] `CurrentlyPlayingFragment.onCreateView` below CC 20
- [ ] `binding` remains a local `val`; no nullable `_binding` field is introduced
- [ ] `LibraryFragment` and `CollectionsFragment` given the same treatment
- [ ] `CollapsedSheetGuardTest` still passes and still locates both guards after the move
- [ ] Verified on device in both orientations: player, library and collections screens unchanged

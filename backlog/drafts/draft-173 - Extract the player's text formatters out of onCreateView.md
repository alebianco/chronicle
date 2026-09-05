---
id: DRAFT-173
title: Extract the player's text formatters out of onCreateView
status: Draft
labels:
  - R2
  - maintainability
priority: medium
---

## Description

`CurrentlyPlayingFragment.onCreateView` is **408 lines with cyclomatic complexity 49**, the
highest in the codebase. It contains six nested local functions that close over the local
`binding` val.

Three of them need **no `binding` at all** — they take a `PlayerProgress` and return a `String`:

- `bookProgressText`
- `chapterPositionText`
- `chapterRemainingText`

They only reach for `getString`, so a resource resolver parameter is enough.

Moving them to a `PlayerText.kt` makes them **directly unit-testable**, which nothing inside
`onCreateView` can be today. `util/DurationFormat.kt` already proves the pattern here: pure over
millis, tested without a `Context`, and `RawDurationFormatTest` pins the wording.

This is the highest value-to-risk item from the 2026-09-05 maintainability review: pure
functions, no ownership change, no behavioural change.

**Do not** reintroduce a nullable `_binding` field. The local-`val` capture is the safer pattern
and is not the problem — function length is.

See `backlog/docs/analysis/maintainability-review-2026-09.md`.

## Acceptance Criteria

- [ ] The three formatters live outside `onCreateView` and take no `binding`
- [ ] Each has unit tests covering the no-chapters fallback and the normal case
- [ ] `RawDurationFormatTest` still passes — the readout stays human-formatted (cu-19)
- [ ] `features/currentlyplaying` coverage rises in `coverage-baseline-packages.txt`
- [ ] No behavioural change on device: chapter position, chapter remaining and book progress
      read identically in both orientations

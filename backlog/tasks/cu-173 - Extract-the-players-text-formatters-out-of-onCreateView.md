---
id: cu-173
title: Extract the player's text formatters out of onCreateView
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
ordinal: 67000
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

- [x] The three formatters live outside `onCreateView` and take no `binding`
- [x] Each has unit tests covering the no-chapters fallback and the normal case
- [x] `RawDurationFormatTest` still passes — the readout stays human-formatted (cu-19)
- [x] `features/currentlyplaying` coverage rises in `coverage-baseline-packages.txt`
- [x] No behavioural change on device: chapter position, chapter remaining and book progress
      read identically in both orientations

## Implementation Notes

`PlayerText.kt` holds the three formatters as pure functions over a `PlayerProgress` plus a
`StringResolver` typealias — a function type rather than a `Context`, because that is the single
capability they need and naming it keeps them callable without Robolectric.

49 lines left `onCreateView`. The call sites build one `strings` resolver and pass it down, so
`binding` ownership is unchanged.

`RawDurationFormatTest` **failed on the move**, correctly: it asserted `formatCoarseDuration` and
`formatPrecisePosition` appear in the fragment, and they had moved. Its scope now follows them into
`PlayerText`, and it additionally refuses `DateUtils` there — sabotage-verified, so the guard came
out of this stronger rather than weaker.

`PlayerTextTest` covers all three, including the two branches that matter: `hasChapters` needs a
positive count **and** a positive number, so a book reporting a count with no current chapter takes
the no-chapters path; and the chapter line falls back to the book's remaining time rather than
going blank, which is the one place the two formatters are coupled.

Overall coverage 43.48% → 43.58%.

## Device check (2026-09-05)

Verified on the tablet in landscape. With the sheet collapsed the readouts still measure correctly
— `progress` 1656-1856 (200px), `chapter_progress` 48-161 (113px) — so the extraction changed
nothing the fragment renders.

Status corrected from `Done` to `In Review`: this changed a screen, and the workflow rule reserves
`Done` for work a machine can prove right. The measurement above is strong evidence, not an owner's
eye on the running player.

**Observed while verifying, filed as [[DRAFT-176]]:** the *details* screen shows
`00:00/9:26:42 0%`, the raw pair §3.1 rule 3 rules out. Not a regression from this work —
`RawDurationFormatTest` is scoped to the player's four views and the details screen was never
converted — and the right wording there is a product choice rather than a mechanical swap.

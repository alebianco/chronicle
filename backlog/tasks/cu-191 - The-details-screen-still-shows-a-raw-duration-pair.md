---
id: cu-191
title: The details screen still shows a raw duration pair
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - polish
milestone: m-2
dependencies: []
priority: low
ordinal: 73000
---

## Description

The book-details screen renders `00:00/9:26:42  0%` — the literal
`h:mm:ss/h:mm:ss` pair that RESEARCH_FINDINGS §3.1 rule 3 rules out and that cu-19 removed from the
**player**. `AudiobookDetailsViewModel.progressString` builds it from two
`DateUtils.formatElapsedTime` calls.

Observed on the tablet 2026-09-05 while verifying cu-173/174, so it is real rather than
theoretical. It is **not** a regression from that work: `RawDurationFormatTest` is deliberately
scoped to the player's four progress views, and the details screen was never converted.

The fix is the one cu-19 already made: `formatCoarseDuration` for the span and
`formatPrecisePosition` for a position, both in `util/DurationFormat.kt`, both pure and tested.
`PlayerText.kt` (cu-173) is the shape to follow.

Whether the details screen should say the same thing as the player is a **product choice** — a
book you have not started may want its total length shown plainly rather than "9h 26m left" — so
this needs the owner's call on wording before implementation, not just a mechanical swap.

## The wording, as chosen by the owner

State-dependent, so the line reads naturally in each state rather than using one grammar
everywhere:

| state | left of the row | right |
|---|---|---|
| not started | `9h 26m` — the book's length, stated plainly | `0%` |
| in progress | `6h 12m left` — the player's grammar, shortened for a narrow row | `34%` |
| finished | `Finished` — the state named, not a countdown to nothing | `100%` |

The player always says "left in book" because it is only ever showing a book being listened to.
This screen is most often looked at for a book **not** started, where "9h 26m left" reads as though
something had already happened. The percentage carries the progress in every state, which is why the
length alone is enough for an unstarted book.

## Acceptance Criteria

- [x] The details screen shows no raw `h:mm:ss/h:mm:ss` pair
- [x] The wording is the owner's choice, recorded in the task
- [x] `RawDurationFormatTest` extended to cover the details screen, so it cannot regress
- [x] Verified on device

## What was found on the way

Three things the mechanical swap would have missed, each caught by measuring rather than reasoning:

- **A book *marked as played* rendered as unstarted.** "Mark as played" zeroes the position on both
  the tracks and the book row, so a finished book and a never-opened one both sit at `progress = 0`.
  Branching on position showed the total length for both, with only the eye icon disagreeing on the
  same screen. Decision-16 already settles this — completion is an explicit fact (`viewCount`), never
  inferred from position — so the formatter now takes a `BookProgressState` from
  `Audiobook.progressState()` and does not decide the state itself. Reproduced and fixed on device.
- **The readout was blank on every unopened book.** It read the *track* table, which is fetched
  lazily on first open, while the book row already carries duration and progress from the library
  sync. Now: tracks when loaded, the book row otherwise.
- **The percentage disagreed with the text** — `7m left` beside `0%`, because the two halves read
  different sources. Both now derive from the same two numbers, which makes the contradiction
  unrepresentable rather than merely fixed.

A fourth was a test-harness gap rather than a defect: `plexConfig.connectionState` was an unstubbed
relaxed mock, so it never emitted and `uiState`'s `combine` sat on its seed forever — every field
default, no error, and no failing assertion unless a test happened to read one.

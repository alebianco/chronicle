---
id: DRAFT-176
title: The details screen still shows a raw duration pair
status: Draft
labels:
  - R2
  - polish
priority: low
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

## Acceptance Criteria

- [ ] The details screen shows no raw `h:mm:ss/h:mm:ss` pair
- [ ] The wording is the owner's choice, recorded in the task
- [ ] `RawDurationFormatTest` extended to cover the details screen, so it cannot regress
- [ ] Verified on device

---
id: cu-145
title: Show narrator and series on a book
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
labels:
  - R2
  - comfort
milestone: m-2
dependencies:
  - cu-24
priority: medium
ordinal: 46700
---

## Description

cu-24 added `narrator`, `series` and `seriesIndex` to `Audiobook`, but they surface **only** on the
browse screen and in cu-25's search results. A book itself never shows them: the detail screen
binds `author` and nothing else, and the library grid, home shelves and player show title + author.

So the app knows a book is *Mistborn #2, narrated by Michael Kramer* and never says so on the book.
For an audiobook library that is a real gap — the narrator is a primary reason for choosing an
edition, and a series position tells the user what to play next.

**Scope note (owner, 2026-09-03).** The owner raised this and suggested it might be R3. Splitting
it: the **detail screen** is a straightforward addition to an existing metadata block and belongs
here in m-2, next to the cu-24/cu-25 work that produced the data. **Reworking the library grid,
home shelves and player** to carry a third and fourth metadata line is a density and typography
decision that belongs with the redesigns — cu-26 (player) and cu-27 (library shelves/chips), where
RESEARCH_FINDINGS §3.1 already calls for an "audiobook-native information hierarchy". Don't
retrofit extra lines into the current grid ahead of those; it is exactly the "everything present,
nothing composed" failure §3.1 diagnoses.

## Acceptance Criteria

- [x] The book detail screen shows the narrator when known
- [x] The book detail screen shows the series and the book's position in it when known
- [x] A book with no narrator or series shows **no empty label** — the row is absent, not blank
      (an empty "Narrated by" reads as "narrated by nobody")
- [x] The series line is tappable, opening that series in browse (the facet screen already exists
      from cu-24)
- [x] Series position reads naturally ("Book 2 of Mistborn"), and degrades to just the series name
      when `seriesIndex` is 0 — which is the common case until cu-143 lands
- [x] User-facing strings in `strings.xml`, plurals where needed
- [x] Tests for the "known", "series without index" and "neither known" states
- [x] Contributes no new per-second work to the player (cu-110)

## Background

Filed out of cu-25. Two things worth knowing before starting:

- **Coverage is mostly absent until cu-143.** Narrator and series are read only from
  `/library/metadata/{id}`, so they are populated for books the user has opened. Until cu-143 seeds
  them library-wide, most books will legitimately have neither — which is why the "absent, not
  blank" criterion above matters more than it looks.
- **`seriesIndex` is 0 far more often than not.** It is parsed from `titleSort`, not Plex's `index`
  (which is the album ordering index and is 1 for nearly every audiobook), so a book whose
  `titleSort` carries no number has no position. The UI must read well without it.

## Implementation Notes

Two rows on the detail screen, between the author and the play button: `Narrated by <name>` and
`<Series>, Book <n>`. The series row is tappable and opens cu-24's browse facet for that series.

**The wording and the absence rules are pure** (`BookMetadataLines`), tested without a screen,
because the absence rules are the substance: cu-24 learns narrator and series only for books the
user has opened, and cu-146 leaves the position unknown wherever the tagging carried no number, so
**most books are missing one or both today**. Both rows are `gone` in XML and un-hidden only when
there is something to say — a blank "Narrated by" line states the book has no narrator, which is a
wrong claim rather than a missing one. Three shapes for the series line, not two, since "series but
no number" is the common case: `Mistborn, Book 2` / `Mistborn` / nothing.

A fractional position keeps its fraction (`Book 1.5` — a novella genuinely sits there, and cu-146
stores hundredths) while a whole number never shows a trailing `.0`.

**The layout bug this introduced, caught and fixed.** Dropping two rows into the packed chain
squeezed it: bounded top *and* bottom by the play button, four rows laid out in the space sized for
two and the narrator line overlapped the series line by 17px on the tablet — visible, readable, and
wrong. The chain is relinked `title → author → narrator → series` with only the last row closing at
the play button. `BookDetailsMetadataLayoutTest` measures the tops and bottoms and asserts they do
not intersect, for all four visibility combinations; a `uiautomator` dump reports bounds without
noticing they overlap, and the gap is small enough to miss in a screenshot.

The series row's `contentDescription` says *"Browse the Mistborn series"* rather than repeating the
text, applying cu-149's lesson from the same session: a control's label must say what a tap does.

**Verification**

- `./verify.sh --format` green, 7 stages. **1095 unit tests** (was 1074), 0 failures.
- Coverage rose 35.91% → **36.14%**.
- **Sabotage-verified**: restoring the squeezing chain fails three stacking tests.
- **Device-verified on the tablet** against fixture book 1003: rows at y 461→494→523→552→581, no
  overlap, reading "Mistborn Book 10" / "Brandon Sanderson" / "Narrated by Michael Kramer" /
  "Mistborn, Book 10"; tapping the series row lands on `facet_books_grid`.

**Scope kept as filed**: the library grid, home shelves and player are untouched — carrying extra
metadata lines there is a density decision for cu-26/cu-27, per RESEARCH_FINDINGS §3.1.

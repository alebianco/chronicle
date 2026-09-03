---
id: cu-145
title: Show narrator and series on a book
status: To Do
assignee: []
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

- [ ] The book detail screen shows the narrator when known
- [ ] The book detail screen shows the series and the book's position in it when known
- [ ] A book with no narrator or series shows **no empty label** — the row is absent, not blank
      (an empty "Narrated by" reads as "narrated by nobody")
- [ ] The series line is tappable, opening that series in browse (the facet screen already exists
      from cu-24)
- [ ] Series position reads naturally ("Book 2 of Mistborn"), and degrades to just the series name
      when `seriesIndex` is 0 — which is the common case until cu-143 lands
- [ ] User-facing strings in `strings.xml`, plurals where needed
- [ ] Tests for the "known", "series without index" and "neither known" states
- [ ] Contributes no new per-second work to the player (cu-110)

## Implementation Notes

Filed out of cu-25. Two things worth knowing before starting:

- **Coverage is mostly absent until cu-143.** Narrator and series are read only from
  `/library/metadata/{id}`, so they are populated for books the user has opened. Until cu-143 seeds
  them library-wide, most books will legitimately have neither — which is why the "absent, not
  blank" criterion above matters more than it looks.
- **`seriesIndex` is 0 far more often than not.** It is parsed from `titleSort`, not Plex's `index`
  (which is the album ordering index and is 1 for nearly every audiobook), so a book whose
  `titleSort` carries no number has no position. The UI must read well without it.

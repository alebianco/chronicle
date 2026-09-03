---
id: cu-25
title: Fuzzy grouped search via /hubs/search
status: Done
assignee:
  - '@claude'
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies: []
priority: medium
ordinal: 46000
---

## Description

Typo-tolerant, grouped search.

## Acceptance Criteria

- [x] Typo-tolerant results grouped author/book
- [x] Narrator and series are searchable, not just title/author
- [x] Search works in offline mode
- [x] Results are grouped with per-group counts
- [x] Input is debounced so typing does not fire a query per keystroke

## Implementation Notes

**Local-first, not `/hubs/search`** — the significant decision, and it contradicts the task title.
Research into the endpoint (sources cited in cu-143) found it cannot meet the criteria:

- **`Style`/`Mood` are absent from search results**, verified by diffing the real-server captures.
  So a server search cannot answer a narrator or series query — half of what this feature is for.
- A server search is **unavailable offline**, while every other read path honours `offlineMode`.
- `sectionId` only *re-orders* results rather than filtering to a library, and `limit` defaults to
  **3 per hub**.

`/hubs/search` does spell-check server-side and is explicitly built for type-ahead, so it remains
worth adding later as a complement for books not yet synced — but as an addition, not the
foundation. The related finding, that narrator/series *can* be indexed for the whole library up
front, is filed as **cu-143** (it also makes a CLAUDE.md gotcha false).

**What changed**

- `data/model/BookSearch.kt` (new) — pure matching, ranking and grouping over `List<Audiobook>`.
  Tiers exact → prefix → word-prefix → substring → fuzzy; `fieldBonusFor` makes field order
  dominate match quality, so a fuzzy title hit outranks an exact series hit.
- `features/search/SearchController.kt` (new) — the search half of a ViewModel, shared by all three
  screens. Debounces at 250 ms and cancels the in-flight query.
- `features/search/SearchRow.kt` + `GroupedSearchAdapter.kt` (new) — headed rows with counts.
- `BookRepository.searchGrouped` — reads through `getAllBooksAsync`, which already applies
  `offlineMode`. The flat `search`/`searchAsync` stay for Android Auto voice search.
- Deleted `AudiobookSearchAdapter` and `SearchBindingAdapters` (dead once all three screens moved),
  and removed the standing `TODO: refactor search to reuse code from Library + Home fragments`,
  which the controller resolves.

**Decisions taken**

- **Damerau-Levenshtein, not Levenshtein.** A transposition is the commonest typo and plain
  Levenshtein charges 2 for it, so "dnue" fell outside a budget real typos must fit; widening the
  budget to 2 instead would admit genuinely different words. Counting a swap as one edit separates
  those cases.
- **Character-count prefilter, not bigrams.** A first cut prefiltered on shared bigrams, which
  silently discarded true matches: a transposition rewrites *every* adjacent pair, so "dnue" and
  "dune" share none. Character counts are transposition-invariant and still cut distance
  computations from 4000 fields to ~12 for a six-letter query.
- **Hand-rolled distance** rather than a dependency — the principle-3 "trivial utility" exception;
  it is ~15 lines and a string-similarity library would add a ProGuard surface.
- **Fuzzy matching has a floor** (`MIN_FUZZY_QUERY_LENGTH = 4`). Below it, only prefix/substring
  match, or the first keystrokes would answer most of the library.
- **One result per book**, under its strongest field, so a book matching by title *and* series is
  not listed twice.

**Verification**

- `./verify.sh --format` green, 7 stages. **1008 unit tests** (was 962), 0 failures.
- Coverage rose: aggregate 33.60% → **35.14%**; `features/search` 0.00% → **83.55%**; every package
  moved up, none down, no new package introduced.
- **Sabotage-verified** the two claims that could silently not hold: removing the `offlineMode`
  argument fails `SearchGroupedTest`, and removing the `delay` fails the debounce test. (Both need
  `--rerun-tasks`; Gradle otherwise reports the task up-to-date and the sabotage never runs.)
- **Device-verified on the tablet** against the mock fixture pack: `hobit` → "BOOKS · 1 book" →
  *The Hobbit*; `Kramer` → "NARRATORS · 1 book" → *"Narrated by Michael Kramer"*; `Mistborn` →
  one row under BOOKS despite matching title and series; typing `hobbit` a character at a time
  settles on the correct final result.

**Defect found after merge (owner review, 2026-09-03)**

The series group was ordered by *match score*, whose final tiebreak is the title — so a Mistborn
search returned books `[1, 3, 2]`, putting *The Hero of Ages* between books 1 and 2. Reading order
is the whole reason to search a series name, and an ordering the user trusts but that is wrong is
worse than an obviously arbitrary one. `groupedSearch` now routes the series group through
`inSeriesOrder()` (cu-24), so search and browse share one reading order; every other group stays
ranked by match quality, which is what a title or narrator query means. Three tests, all
sabotage-verified. I had tested that series *matching* worked and never checked the *order* of the
matches.

**Follow-ups**

- **cu-143** (new task) — seed narrator/series for the whole library at refresh time, so grouping
  is not limited to already-synced books.
- **cu-145** (new task) — show narrator and series *on a book*; cu-24's data surfaces only in
  browse and search today, never on the detail screen, grid or player.
- `/hubs/search` as a complement for un-synced books is *not* filed: it needs cu-143 first, which
  may make it unnecessary.

---
id: cu-161
title: Cut the whole-table read out of search
status: Done
assignee:
  - '@claude'
created_date: '2026-09-04'
updated_date: '2026-09-10 07:00'
labels:
  - R4
  - performance
milestone: m-2
dependencies:
  - cu-51
priority: low
ordinal: 105000
---

## Description

**Not a known problem — a measured headroom item.** Filed by [[cu-51]] so the finding is not lost,
and deliberately at R4: on the owner's 196-book library the path this describes costs **1.5 ms**.

`searchGrouped` calls `bookDao.getAllBooksAsync(...)`, which deserializes **every column of every
row** — including the serialized `chapters` blob — and then scans the result in memory. cu-51
measured that read at **39 ms for 10,000 books**, which is most of the 29–39 ms a search costs at
that size. The scan itself is cheap and linear; the read is the expensive half.

Two ways to fix it, if it ever needs fixing:

- **A projection.** `BookSearch` reads exactly four fields — title, author, narrator, series — plus
  the id. A `@Query` returning a small POJO of those would avoid materialising `chapters` entirely,
  which is the single largest column. This is the smaller change and probably enough.
- **An FTS4/FTS5 table.** Room supports `@Fts4`, and it would move the matching into SQLite. But
  cu-25's search is **Damerau-Levenshtein with a character-count prefilter**, deliberately, because
  a transposition is the commonest typo and FTS does not do fuzzy matching in the way this needs.
  FTS would replace the substring/prefix tier and *not* the fuzzy one, so it is a bigger change for
  a partial win. Read cu-25's notes before assuming it is the obvious answer.

## Acceptance Criteria

- [x] A search at 10,000 books costs materially less, with the before/after numbers recorded —
      **196.7 → 75.4 ms, 62%**, stable across three runs
- [x] `LargeLibraryScaleTest` still passes — the growth must stay linear, not merely get faster
- [x] cu-25's matching behaviour is unchanged: the fuzzy tier, the 4-character floor and the
      character-count prefilter all still work — 46 existing search tests pass untouched, plus a
      new equivalence suite that runs the *old* path beside the new one and asserts they agree

## Related

- [[cu-51]] — the measurement that produced this, including the method for re-running it
- [[cu-25]] — why the matching is Damerau-Levenshtein and what FTS would and would not replace

## Implementation Notes

**Chosen: the projection, not FTS.** As the task predicted, it is the smaller change and it is
enough. `searchProjection` reads the five columns the matching actually uses — id, title, author,
narrator, series — matches over those, then fetches only the books that matched, by id.

### Measured, and the first two numbers were wrong

| | 10,000 books |
|---|---|
| Whole-table read (`SELECT *`) | **69–75 ms** |
| Projection read | **11.6 ms** |
| Fetch 50 hits by id | **2.0 ms** |
| `searchGrouped` before | **196.7 ms** |
| `searchGrouped` after | **75.4 ms** (**62% less**) |

Two measurement traps, both worth recording because both produced a confident wrong answer:

1. **The first end-to-end run said the change was 2% *worse*.** The read was 5× faster and the
   whole search had not improved. Cause: the synthetic library used `"Series ${i % 200}"`, so the
   query `"Series 42"` fuzzy-matched `Series 142/242/342…` and pulled **6400 of 10,000** books in
   the second fetch. A fixture artefact, not a real query — the same shape as cu-110's "a
   performance fix verified against the easy fixture is not verified", in reverse.
2. **Then it said 53% — with `hits=0`.** The rewritten fixture used varied names, and the old query
   matched nothing at all, so it measured only the no-match path.

The honest number is the third: a real author surname, **2324 hits** (23% of the library), 62%
faster, stable to ±1% over three runs. The win survives a query matching a quarter of the table.

### Why not generics

The first attempt made the matching generic over a `Searchable` interface that both `Audiobook` and
the projection implement. It compiled, but `GroupedSearchResults<T>` leaked into `SearchController`,
`SearchRow` and the repository interface — a type parameter through the whole UI layer so that one
DAO query could be cheaper. Reverted. The projection converts to an `Audiobook` stub carrying only
the matched fields, `groupedSearch` runs **unchanged**, and `withRealBooks` swaps in the real rows
afterwards.

`withRealBooks` **drops** a result whose id is missing — the book was deleted between the two reads,
and a row with no cover, duration or progress is worse than one fewer result.

### Verification

Full `./verify.sh` green. `SearchProjectionEquivalenceTest` is the load-bearing check: each case
runs the **old** path (`getAllBooksAsync(...).groupedSearch(query)`) over the same real database and
asserts the two agree on groups, order, ids, matched values, counts and scores — across exact,
partial, author, narrator, series, transposition, one-character typo, sub-floor, no-match and
matches-most queries.

Sabotage-verified twice: dropping `narrator` from the projection (caught by the narrator case) and
skipping the swap-back (caught by the full-book case).

`SearchReadCostTest` is a **measurement harness, not a gate** — it prints and asserts nothing tight,
because a wall-clock number under Robolectric on a laptop must not fail a build on a busy machine.
It exists so re-running the measurement is one command.

### Note for whoever revisits this

The task's premise named the serialized `chapters` blob as the largest column. [[cu-159]] dropped
that column earlier the same day, and the whole-table read still costs 69–75 ms — so the cost is
the twenty *other* columns, not that one. The projection is still worth it.

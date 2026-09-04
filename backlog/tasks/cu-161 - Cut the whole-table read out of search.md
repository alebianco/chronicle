---
id: cu-161
title: Cut the whole-table read out of search
status: To Do
assignee: []
created_date: '2026-09-04'
labels:
  - R4
  - performance
dependencies:
  - cu-51
milestone: m-4
priority: low
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

- [ ] A search at 10,000 books costs materially less than the 29–39 ms cu-51 measured, with the
      before/after numbers recorded
- [ ] `LargeLibraryScaleTest` still passes — the growth must stay linear, not merely get faster
- [ ] cu-25's matching behaviour is unchanged: the fuzzy tier, the 4-character floor and the
      character-count prefilter all still work, pinned by its existing tests

## Related

- [[cu-51]] — the measurement that produced this, including the method for re-running it
- [[cu-25]] — why the matching is Damerau-Levenshtein and what FTS would and would not replace

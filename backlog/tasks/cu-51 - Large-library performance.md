---
id: cu-51
title: Large-library performance
status: In Review
assignee: []
created_date: '2026-07-13'
labels:
  - R2
  - performance
milestone: m-2
dependencies: []
priority: medium
ordinal: 62000
---

## Description

M7: huge libraries load slowly (stopgap: 15s→30s timeout). Real fix: incremental/paged loading, repository query optimization (sub-n^2), DB indexes; test with 1000+ books.

Analysis: [`M7-large-library-performance-plan.md`](../docs/analysis/M7-large-library-performance-plan.md).

**Two in-memory scans to measure here (added 2026-09-03).** cu-25's grouped search and cu-24's
browse facets both read the **whole library into memory** and scan it — the search per keystroke
(debounced 250 ms), the facets per screen open. Both were designed against this task's 1000+ book
target and are cheap by construction (`BookSearch` prefilters on length and character counts before
any edit distance; cu-147 compiles the series-index rules once), but neither has been *measured* at
that scale — the fixture pack has three books. Profile them alongside the load paths rather than
assuming: `am profile start --sampling` named the cu-110 cause at once where four rounds of
inspection had produced plausible wrong answers.

## Implementation Notes (2026-09-04) — measured, and the premise did not survive

**The task was written from a TODO, not from a measurement.** Phase 1 of the analysis plan is
"profile current performance", and doing that first changed what the remaining work is. Three of the
five criteria turn out to be already met or measurably unnecessary; the honest outcome is a
benchmark that will *catch* a regression rather than an optimisation nothing needed.

`LargeLibraryScaleTest` is the durable artefact. It generates libraries of realistic **shape** —
varied authors, narrators and series in roughly the owner's proportions, because a library of
identical books collapses every group and measures nothing — and asserts the **growth factor**
between two sizes rather than a wall-clock budget, which would pin the CI runner rather than the
code.

### What the measurements say

Best of five after a warm-up, on the development machine:

| n | fuzzy search | search with a typo | facet grouping | series-index parse |
|---|---|---|---|---|
| 196 (the real library) | 1.56ms | 1.94ms | 0.15ms | 0.53ms |
| 1,000 | 4.79ms | 6.60ms | 0.34ms | 0.73ms |
| 5,000 | 14.72ms | 20.00ms | 1.00ms | 2.75ms |
| 10,000 | 28.75ms | 39.19ms | 1.04ms | 4.99ms |

5,000 → 10,000 is **~2x for a 2x library** on every path. Nothing is quadratic, and the `BookSearch`
prefilter is doing its job: a typo query — the only one that reaches Damerau-Levenshtein at all —
costs about 35% more than an exact one rather than several times more.

The 39ms figure is the one worth understanding rather than fearing: `searchGrouped` runs on
`dispatchers.io` behind cu-25's 250 ms debounce, so it is background work with an order of magnitude
of headroom, not a main-thread stall. It would matter if it ever moved to the main thread.

### DB queries, and why no index was added

Measured against **real SQLite** through Room, then re-measured with candidate indexes on
`(isCached, titleSort)` and `(isCached, addedAt)` — the columns eight of the book queries actually
filter and sort on:

| query at 10,000 books | plain | indexed |
|---|---|---|
| `getAllBooksAsync` | 39.08ms | 40.44ms |
| `getCachedAudiobooksAsync` | 5.24ms | 5.29ms |
| `getRecentlyAddedAsync` | 3.37ms | 3.26ms |

**No measurable benefit**, and `getAllBooks` was marginally *worse*. The reason is straightforward
once measured: `getAllBooks` reads every row, so there is no lookup for an index to accelerate — the
cost is deserializing 10,000 rows — and the filtered queries already complete in single-digit
milliseconds. An index would cost write time on every sync and space in the database, for nothing.

`id` is the primary key and already indexed, which covers the six `WHERE id = :bookId` queries.

## Acceptance Criteria

- [x] Incremental/paged library loading — **already in production before this task**.
      `BookRepository.refreshDataPaginated` and its track twin are what `LibrarySyncRepository`
      calls, and they already handle the dangerous case: an incomplete fetch aborts rather than
      falling through, because a refresh deletes every local book the fetch did not return.
- [x] Repository gets scale better than n^2 — measured linear on every path, and
      `LargeLibraryScaleTest` now fails the build if that stops being true
- [x] 1000+ book library performant — 10,000 books search in 29ms and group in 1ms, both off the
      main thread
- [x] cu-25's grouped search and cu-24's facet grouping measured at 1000+ books, not assumed —
      this was the real content of the task, and the numbers are above

**Retired rather than met: "DB indexes added".** Adding one was measurably useless (table above) and
would cost write time and space. The criterion assumed a lookup bottleneck that profiling did not
find. If a future query filters on something selective this should be revisited — the measurement
method is in the notes, so re-running it is cheap.

### What is left, and what it is not

Nothing here blocks a large library. The remaining opportunity is **not** query speed: it is that
`getAllBooksAsync` deserializes the whole table for a search, which is ~39ms of allocation per
query at 10,000 books. An FTS table or a projection that reads only the four searchable columns
would cut that, and is worth doing **if** a real library of that size ever exists — the owner's is
196 books, where the same path costs 1.5ms. Filed as a follow-up rather than done speculatively,
since the measurement says there is no user-visible problem to fix.

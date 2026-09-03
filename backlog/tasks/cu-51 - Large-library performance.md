---
id: cu-51
title: Large-library performance
status: To Do
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

## Acceptance Criteria

- [ ] Incremental/paged library loading
- [ ] Repository gets scale better than n^2
- [ ] DB indexes added
- [ ] 1000+ book library performant
- [ ] cu-25's grouped search and cu-24's facet grouping measured at 1000+ books, not assumed

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

## Acceptance Criteria

- [ ] Incremental/paged library loading
- [ ] Repository gets scale better than n^2
- [ ] DB indexes added
- [ ] 1000+ book library performant

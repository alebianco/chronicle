---
id: cu-49
title: Refactor chapters into their own DB table
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: [cu-13, cu-15]
priority: medium
milestone: m-1
---

## Description

M4: chapters are embedded in tracks/books, complicating queries. Design a chapter schema + ChapterDao + migration; playback + UI read chapters from the DB. Sequence with cu-13 (chapter correctness) and the neutral Chapter model (cu-13 builds it; this persists it).

Analysis: [`M4-chapter-management-refactor-plan.md`](../docs/analysis/M4-chapter-management-refactor-plan.md).

## Acceptance Criteria

- [ ] ChapterDao + entity + migration
- [ ] Playback + UI read chapters from DB
- [ ] Migration from old schema tested

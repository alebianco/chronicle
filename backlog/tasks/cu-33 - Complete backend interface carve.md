---
id: cu-33
title: Complete backend interface carve
status: To Do
assignee: []
created_date: '2026-07-13'
labels:
  - R4
  - architecture
milestone: m-4
dependencies:
  - cu-15
priority: medium
ordinal: 53000
---

## Description

Route the 27 direct data.sources.plex.* imports through the interface; login-flow redesign for URL+token backends; per-source capability flags (hasNarrator, hasSeries, hasServerProgress) so facets/'synced'/series shelves degrade gracefully (D11).

## Acceptance Criteria

- [ ] No direct plex imports in features/
- [ ] UI renders correctly against a source lacking narrator/series/server progress

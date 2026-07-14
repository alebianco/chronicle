---
id: cu-1
title: Upgrade Room from 2.7.0-alpha12 to stable
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, foundation]
dependencies: []
priority: high
milestone: m-0
---

## Description

Room ships an alpha build in production — the highest immediate toolchain risk (RESEARCH_FINDINGS §8). Move to the current stable line.

## Acceptance Criteria

- [ ] App runs on stable Room
- [ ] All migrations written and tested (schema version bumped per CLAUDE.md rule 6)
- [ ] Verify loop green

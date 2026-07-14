---
id: cu-52
title: StateFlow migration (LiveData replacement)
status: Draft
assignee: []
created_date: '2026-07-13'
labels: [R4, architecture]
dependencies: []
priority: low
milestone: m-4
---

## Description

M2: migrate ViewModels/repositories from LiveData to StateFlow. Upstream's own todo flags this as uncertain ('may not be worth it if LiveData works well'). Depends on dispatcher injection (cu-15). CLAUDE.md currently mandates LiveData — this draft is the trigger to revisit that convention, not a committed task.

Analysis: [`M2-stateflow-migration-plan.md`](../docs/analysis/M2-stateflow-migration-plan.md).

## Open question / why this is a draft

Needs an owner decision before it becomes a tracked task (see below).

## Acceptance Criteria (provisional)

- [ ] Decision recorded: migrate or stay on LiveData
- [ ] If migrate: pilot one feature, then phased rollout
- [ ] CLAUDE.md convention updated to match

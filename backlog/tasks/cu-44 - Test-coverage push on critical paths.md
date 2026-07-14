---
id: cu-44
title: Test-coverage push on critical paths
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, agentic]
dependencies: [cu-16, cu-15]
priority: high
milestone: m-1
---

## Description

H1: standing effort to raise coverage where it unblocks autonomous work — ViewModels, repositories, DAOs (in-memory Room), sync/progress/download logic. Not one PR: the JaCoCo ratchet (cu-3) enforces it per-PR; this task tracks the initial backfill on the R1 risk surface, fixture-backed (cu-16).

Analysis: [`H1-test-coverage-plan.md`](../docs/analysis/H1-test-coverage-plan.md).

## Acceptance Criteria

- [ ] Repositories + sync/progress/download logic have unit tests
- [ ] DAO tests on in-memory Room
- [ ] Coverage baseline established and ratcheting in CI

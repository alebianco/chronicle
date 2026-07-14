---
id: cu-48
title: Update kotlin-result dependency
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, hygiene]
dependencies: []
priority: low
milestone: m-0
---

## Description

M1: libs.versions.toml marks kotlin-result 1.1.11 OUT OF DATE. Update (or replace with stdlib Result), review breaking changes, test all Result usages.

Analysis: [`M1-outdated-dependency-plan.md`](../docs/analysis/M1-outdated-dependency-plan.md).

## Acceptance Criteria

- [ ] Dependency current or replaced with stdlib Result
- [ ] All Result usages compile and pass tests

---
id: cu-9
title: Progress-reporting overhaul
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-7, cu-16]
priority: high
milestone: m-1
---

## Description

Port fabiogermann PlexProgressReporter pattern: immediate pause report, WorkManager retry with backoff, correct duration (fixes #88/#112/#68/#67). Fixture-backed tests per cu-16.

## Acceptance Criteria

- [ ] Kill-app/airplane-mode/pause tests: position always recovered server-side
- [ ] Visible 'synced' indicator
- [ ] Tests land with the port (D6)

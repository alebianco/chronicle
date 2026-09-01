---
id: cu-50
title: Fix notification issues (chapter-change update)
status: To Do
assignee: []
created_date: '2026-07-13'
labels:
  - R2
  - playback
milestone: m-2
dependencies: []
priority: medium
ordinal: 61000
---

## Description

M6: notification not refreshed on chapter change; verify across Android versions + Android 13+ POST_NOTIFICATIONS. Fix update-on-chapter-change, test actions.

Analysis: [`M6-notification-issues-plan.md`](../docs/analysis/M6-notification-issues-plan.md).

## Acceptance Criteria

- [ ] Notification updates on chapter change
- [ ] Correct across Android versions incl. 13+ permission
- [ ] Notification actions tested

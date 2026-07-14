---
id: cu-10
title: Silent re-auth on 401 + token validation on launch
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-16]
priority: high
milestone: m-1
---

## Description

No 401 re-auth exists today (error string in MainActivity). Fixes #110 stuck login.

## Acceptance Criteria

- [ ] Expired token never shows a login wall mid-book
- [ ] Re-auth invisible to user
- [ ] Fixture-backed tests

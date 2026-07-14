---
id: cu-13
title: Chapter correctness on neutral Chapter model
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-15]
priority: high
milestone: m-1
---

## Description

Multi-file mapping, duplicate names, current-chapter highlight, chapterSource respected; album-art-not-track-art fix (#119/#76/#12/#113). Per D11: build against a backend-neutral Chapter entity fed by the adapter — the fix must be inherited by ABS/local later.

## Acceptance Criteria

- [ ] Chapters match m4b embedded data
- [ ] Correct cover always shown
- [ ] Chapter logic has no Plex imports

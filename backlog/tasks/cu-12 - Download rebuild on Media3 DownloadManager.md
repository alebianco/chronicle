---
id: cu-12
title: Download rebuild on Media3 DownloadManager
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-7, cu-16]
priority: high
milestone: m-1
---

## Description

HTTP-Range resumable, chunked-to-disk (kills 2GB OOM #83), per-book cache status for offline play (PR #114 concept modernized). Replaces Fetch2.

## Acceptance Criteria

- [ ] 2GB m4b survives Wi-Fi drops and app kill
- [ ] Offline books play with server unreachable
- [ ] No OOM

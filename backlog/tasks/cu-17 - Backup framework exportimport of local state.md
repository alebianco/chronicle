---
id: cu-17
title: Backup framework: export/import of local state
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Per D8: versioned JSON/zip export-import of everything not re-derivable from Plex via SAF picker (cloud-folder friendly, zero cloud SDKs); Android Auto Backup rules (dataExtractionRules). Phase c (scheduled snapshots) is cu-17.1.

## Acceptance Criteria

- [ ] Wipe app, restore file: identical state minus auth
- [ ] Tokens never leave the device
- [ ] Schema versioned and forward-compatible

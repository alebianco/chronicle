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

## Warning from the R0-close review (2026-08-31)

cu-60 removed the premium/IAP code but shipped **no prefs migration**, so `key_is_premium` and
`key_premium_token` remain in SharedPreferences on any install that predates it. Nothing reads them
today, so they are inert.

They stop being inert here. A natural implementation of this task enumerates `sharedPreferences.all`
to build the export, which would serialize a Play **purchase token** into a plaintext JSON backup.

Use an explicit allowlist of exported keys rather than a blanket dump — which is better practice
regardless — or ship a one-shot removal migration first. Decide before writing the exporter, not after.

## Acceptance Criteria

- [ ] Wipe app, restore file: identical state minus auth
- [ ] Tokens never leave the device
- [ ] Schema versioned and forward-compatible

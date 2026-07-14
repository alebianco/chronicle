---
id: cu-42
title: Remove cleartext traffic, enforce HTTPS
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, security]
dependencies: []
priority: high
milestone: m-1
---

## Description

C1: `android:usesCleartextTraffic="true"` (AndroidManifest.xml:22) allows unencrypted HTTP — MITM exposure, may fail Play security review. Audit connections, remove the flag, add a Network Security Config for any genuine plain-HTTP LAN Plex exception (self-hosted servers are often http:// on LAN — handle deliberately, not blanket-allow).

Analysis: [`C1-cleartext-traffic-resolution-plan.md`](../docs/analysis/C1-cleartext-traffic-resolution-plan.md).

## Acceptance Criteria

- [ ] No blanket cleartext
- [ ] Documented per-domain exceptions only where a real LAN http server needs it
- [ ] Works against actual Plex servers over HTTPS and explicit LAN exceptions

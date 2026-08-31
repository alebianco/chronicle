---
id: cu-11
title: Connection resiliency: LAN/WAN/relay tiering
status: In Progress
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-16]
priority: high
milestone: m-1
---

## Description

Tiered attempts LAN then WAN then relay (relay deprioritized ~2Mbps), refresh coordinator (fabiogermann pattern). Fixes #103/#98.

## Acceptance Criteria

- [ ] LAN-only server works
- [ ] Network switch mid-playback recovers in <5s

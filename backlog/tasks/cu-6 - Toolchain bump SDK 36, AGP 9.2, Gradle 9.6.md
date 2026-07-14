---
id: cu-6
title: Toolchain bump: SDK 36, AGP 9.2, Gradle 9.6
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, foundation]
dependencies: []
priority: high
milestone: m-0
---

## Description

targetSdk/compileSdk 36 + AGP 9.2 + Gradle 9.6.1; QA edge-to-edge, predictive back, FGS types, notification permission; 16KB page-size check (Fresco). See RESEARCH_FINDINGS §8. Play deadline 2026-08-31 only binds if Play distribution is ever chosen (D9 dormant).

## Acceptance Criteria

- [ ] Clean build on new toolchain
- [ ] Full manual playback checklist passes on Android 16 device
- [ ] 16KB check done

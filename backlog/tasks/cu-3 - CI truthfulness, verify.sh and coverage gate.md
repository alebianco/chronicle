---
id: cu-3
title: CI truthfulness, verify.sh and coverage gate
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, agentic]
dependencies: []
priority: high
milestone: m-0
---

## Description

Resolve the green-no-op: DebugAndroidTest tasks are force-disabled (app/build.gradle.kts:139) while ci.yml runs a full emulator matrix. Re-enable via Gradle Managed Devices (headless: ./gradlew pixel2api34DebugAndroidTest) or delete the emulator job and document instrumented tests as dead. Add verify.sh (fail-fast: ktlint, unit, assemble, lint; --quick flag) as the portable gate of record per D12 rule 6 — forge-level required checks are convenience, never source of truth. JaCoCo report as CI artifact, ratchet +2%/PR on touched files. See COMMERCIAL_VIABILITY_REPORT (docs/research/) §9.

## Acceptance Criteria

- [ ] CI can never pass while running zero tests
- [ ] verify.sh exists and is referenced by CI (thin wrapper)
- [ ] Coverage visible per PR
- [ ] CI config portable to any forge

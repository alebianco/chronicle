---
id: cu-45
title: Complete ProGuard/R8 keep rules
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, release]
dependencies: []
priority: high
milestone: m-0
---

## Description

H2: release builds use R8 but keep rules were minimal (Room/Retrofit/Moshi/Dagger/Media3 all reflection-adjacent). In-flight uncommitted work exists (~170 lines + test_release_build.sh). Finish, commit, and gate: every dependency needing keep rules covered; release build smoke-tested.

Analysis: [`H2-proguard-rules-plan.md`](../docs/analysis/H2-proguard-rules-plan.md).

## Acceptance Criteria

- [ ] Keep rules for Room, Retrofit, Moshi, Dagger, Media3, Coil
- [ ] ./test_release_build.sh passes
- [ ] R8 full-mode considered

---
id: cu-43
title: Migrate image loading Fresco to Coil
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R0, architecture]
dependencies: [cu-6]
priority: medium
milestone: m-0
---

## Description

C3: deprecated Fresco DraweeView API in use; app also carries Glide (duplication). Consolidate on Coil (Kotlin-first, coroutine-native, 16KB-clean — supports cu-6). Replace ChronicleDraweeView + BindingAdapters image bindings; drop the redundant library.

Analysis: [`C3-fresco-to-coil-migration-plan.md`](../docs/analysis/C3-fresco-to-coil-migration-plan.md).

## Acceptance Criteria

- [ ] Single image library (Coil)
- [ ] No deprecated DraweeView usage
- [ ] Image load/cache/transform verified
- [ ] Release build passes (ProGuard)

---
id: cu-70
title: Guard the debug/release DebugHooks twins against drift
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R1, agentic]
dependencies: []
priority: medium
---

## Description

From the R0-close review. `app/src/debug/.../DebugHooks.kt` and `app/src/release/.../DebugHooks.kt`
must expose identical signatures — `main` calls into them from `ChronicleApplication` and
`MainActivity`. Their signatures currently match, and `main` correctly touches only `DebugHooks` and
never `MockPlexServer`/`MockPlexMode`.

**Nothing enforces this.** No interface, no shared test. Adding a parameter to the debug twin and
forgetting the release one produces a **release-only compile failure** — and CI runs `assembleDebug`
only, so it would land green and break the first release build.

This already nearly happened during cu-64: adding `onPlayBookIntent` required a matching stub, caught
only because the same change built both variants locally.

### Options

1. Add `assembleRelease` to CI (`verify.sh` runs `lintDebug`/`assembleDebug` only). Catches this and
   any other release-only breakage, at the cost of build time.
2. Extract a shared `interface DebugHooksContract` in `main` that both twins implement — the compiler
   then enforces the shape.

(1) is broader and probably the better default; the release build is currently only exercised by
`test_release_build.sh`, which is not part of the standard gate.

## Acceptance Criteria

- [ ] A signature divergence between the twins fails a build that CI actually runs
- [ ] Decision recorded on whether `assembleRelease` joins the verify loop

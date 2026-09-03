---
id: cu-70
title: Guard the debug/release DebugHooks twins against drift
status: Done
assignee: [claude]
created_date: '2026-08-31'
labels: [R1, agentic]
dependencies: []
priority: medium
milestone: m-1
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

## Implementation Notes

**Both options were needed, and finding that out was the useful part.**

Option 2 first: `DebugHooksContract` in `main/`, implemented by both twins, so the compiler
enforces the shape. Then I tested it by adding a hook to the contract and the debug twin and
omitting the release stub — the exact mistake this guards.

**The debug variant compiled clean.** Zero errors. Only `compileReleaseKotlin` failed. The
interface makes the compiler check the shape, but it can only check *the variant being built* —
so on its own it would not have helped, because everything in `verify.sh` built debug only.

So option 1 as well: `compileReleaseKotlin` is now stage 6 of the verify loop. Chosen over
`assembleRelease` because it catches the same class of breakage — a release source set that does
not compile — without R8, resource shrinking or APK packaging. 17s cold, ~1s warm, against
`assembleRelease`'s ~90s. `test_release_build.sh` still covers the R8-specific risks and stays
separate.

Verified by sabotage: with the drift in place, `./verify.sh` fails at stage 6.

## Acceptance Criteria

- [x] A signature divergence between the twins fails a build that CI actually runs — CI calls
      `verify.sh`, which now compiles the release variant
- [x] Decision recorded: `compileReleaseKotlin` joins the loop, not `assembleRelease` — the
      cheaper task catches the same failure, and the release *packaging* risks stay with
      `test_release_build.sh`

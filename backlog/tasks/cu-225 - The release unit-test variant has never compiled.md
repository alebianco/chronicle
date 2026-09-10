---
id: cu-225
title: The release unit-test variant has never compiled
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - tooling
  - debt
milestone: m-2
dependencies: []
priority: high
ordinal: 118000
---

## What was found

Promoted from draft-221. `./gradlew :app:compileReleaseUnitTestKotlin` failed, and had been failing
for as long as the debug hook tests existed — reproduced on `feature/agentic-dev` with no local
changes:

```
e: app/src/test/.../debug/MoveSyncLocationHookTest.kt:23:37
   Unresolved reference 'resolveSyncTarget'
   ... 7 more, plus one "Cannot infer type for this parameter"
```

**Cause.** `app/src/test/` is shared by every build variant, but `MoveSyncLocationHookTest` called
`DebugHooks.resolveSyncTarget(...)`, declared only in `app/src/debug/.../DebugHooks.kt`. Compiled
against the *release* variant, the symbol does not exist. All eight errors were in that one file;
the other two `DebugHooks` test files touch members both twins declare.

**Why nothing caught it.** `verify.sh` built `testDebugUnitTest` and `compileReleaseKotlin` — the
release *production* half. Nothing built the release *unit test* half, so the gap was invisible to
the gate of record and to CI. `DebugHooksContract` makes the compiler check the two twins' shapes,
but only for the variant being built, and `resolveSyncTarget` was `internal` and outside the
contract, so the contract could not see it either.

## What was done

**The function moved rather than the test.** `resolveSyncTarget` is pure — a `String`, a
`List<File>`, and an exact-match lookup — with no Android dependency and no reason to live in a
variant-specific object. It is now a top-level `internal fun` in
`app/src/main/.../debug/SyncTargetResolver.kt`, beside `DebugHooksContract`, which already
establishes `main/` as the shared seam for this package. The debug twin's single call site is
unchanged; the test calls the top-level function.

The alternative — moving the test into `app/src/debug/test/` — was rejected: it would have made the
test invisible to the release variant rather than making the code correct, and left the same trap
for the next pure helper someone adds to the debug twin.

**`verify.sh` gained a 10th stage**, `compileReleaseUnitTestKotlin`, immediately after the existing
release-compile stage whose comment ("everything above builds debug only") was correct for
production code and blind to the test half.

## Acceptance Criteria

- [x] `:app:compileReleaseUnitTestKotlin` succeeds — it had never succeeded before
- [x] `resolveSyncTarget` lives in a variant-shared source set, not in the debug twin
- [x] `MoveSyncLocationHookTest` passes under **both** variants — `testDebugUnitTest` and
      `testReleaseUnitTest`, both with `--rerun-tasks` so neither came from cache
- [x] `verify.sh` builds the release unit-test variant, so this cannot regress silently
- [x] **The new stage is sabotage-verified**: restoring the `DebugHooks.` qualifier makes it fail
      with the original `Unresolved reference`, and the sabotage was reverted in a separate call
- [x] `11-verify-loop.md`, its stage table and `CLAUDE.md`'s stage count synced in the same change —
      the table now lists 10, and `--instrumented` becomes the 11th
- [x] `./verify.sh` green
- [x] cu-212's dependency-analysis criteria are unblocked by this

## Notes

**This is the second gate in this programme found unable to fail** — cu-218 found two others (an
`assumeTrue`-guarded chmod test, and truncate-on-restart never exercised). The pattern is the same
each time: a check exists, reads as passing, and never runs the thing it names. Worth treating "does
this gate actually execute?" as a standing question rather than a per-task discovery.

The `DebugHooksContract` KDoc already described this exact failure mode for production code and
recorded that it nearly shipped twice. The test half was simply never considered.

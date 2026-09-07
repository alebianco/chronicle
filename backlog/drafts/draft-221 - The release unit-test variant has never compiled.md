---
id: draft-221
title: "The release unit-test variant has never compiled"
status: Draft
created_date: '2026-09-07'
labels:
  - tooling
  - debt
---

## What was found

`./gradlew :app:compileReleaseUnitTestKotlin` fails, and has been failing for as long as the debug
hook tests have existed. Reproduced on a clean checkout of `feature/agentic-dev` with **no local
changes and no extra plugins** — this is not caused by anything cu-212 added:

```
e: app/src/test/.../debug/MoveSyncLocationHookTest.kt:23:37
   Unresolved reference 'resolveSyncTarget'
   ... 7 more, plus one "Cannot infer type for this parameter"
BUILD FAILED
```

**Cause.** `app/src/test/` is shared by every build variant, but `MoveSyncLocationHookTest` calls
`DebugHooks.resolveSyncTarget(...)`, which is declared only in
`app/src/debug/java/.../debug/DebugHooks.kt:494`. Compiled against the *release* variant, the
symbol does not exist. Three test files reference `DebugHooks` — `MoveSyncLocationHookTest`,
`PostValueUsageTest` and `FailSyncInjectionTest` — but **only `MoveSyncLocationHookTest` fails**,
because it is the only one reaching a member the release twin does not carry. All eight compile
errors are in that one file. The other two touch members both twins declare, so the fix is
narrower than "the test source set is variant-unsafe" would suggest.

**Why nothing caught it.** `verify.sh` builds `testDebugUnitTest` (debug) and `compileReleaseKotlin`
(the release *production* half). Nothing anywhere builds the release *unit test* half, so the gap is
invisible to the gate of record and to CI. `DebugHooksContract` makes the compiler check the shape
of the two `DebugHooks` twins, but only for the variant being built — the same limitation
`verify.sh`'s own comment on the `compileReleaseKotlin` stage describes.

## Why it matters now

It blocks the third part of cu-212. The Dependency Analysis plugin analyses **every** variant, so
`./gradlew buildHealth` compiles `releaseUnitTest` and dies on this before producing any report. The
plugin's `ignoreSourceSet("releaseTest")` filters the *advice* but not the *task graph*, so it is
not a way around this. cu-212 shipped Dependabot and CodeQL and left the plugin out rather than
either modify app code out of scope, or configure the tool to depend on the breakage staying.

## Options

1. **Move the debug-only hook tests to `app/src/testDebug/`.** Smallest change, and honest about
   what they test: they exercise a debug-source-set object. Costs nothing at runtime.
2. **Give the release `DebugHooks` the missing member**, so the twins really are twins. Larger, and
   argues for shipping release code whose only consumer is a test.
3. **Add `compileReleaseUnitTestKotlin` to `verify.sh`** so the gap cannot reopen — worth doing
   alongside 1 or 2, not instead of them.

Recommendation is 1 plus 3. Whoever takes this should confirm by sabotage that the new gate fails
before the fix and passes after.

## Notes

Found while implementing cu-212. Filed rather than fixed because cu-212 states plainly that none of
its three parts changes app code, and moving test files is app-code scope with its own verification.

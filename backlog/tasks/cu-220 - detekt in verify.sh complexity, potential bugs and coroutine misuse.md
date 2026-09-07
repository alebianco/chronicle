---
id: cu-220
title: "detekt in verify.sh: complexity, potential bugs and coroutine misuse"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - maintainability
milestone: m-3
dependencies: 
  - cu-210
  - cu-217
priority: medium
---

## Description

cu-194 frames detekt as *"ktlint is formatting; detekt is complexity and code smells"*. That
undersells it — detekt carries roughly two hundred rules, and three of its rule sets are directly
relevant here.

**Complexity.** The maintainability review measured cyclomatic complexity **by hand** to find
cu-173 and cu-174; detekt would have flagged `onCreateView` at CC 49 automatically.

**Potential bugs.** `UnsafeCallOnNullableType`, `IgnoredReturnValue`, `UnreachableCode` — this is the
set that catches defects rather than style. Note `tryEmit`'s ignored return value appears in
`SleepTimerBus` and `PlaybackErrorBus` deliberately; expect to justify those rather than silence the
rule wholesale.

**Coroutines.** `GlobalCoroutineUsage` mechanically enforces the `GlobalScope` ban that convention 4
currently states in prose and `CachedFileManagerScopeTest` checks by reading source text.
`SuspendFunWithFlowReturnType` and `SleepInsteadOfDelay` are the same family.

**Not style or naming** — ktlint owns formatting, and running both on the same concerns produces
contradictory advice.

## The thing to get right

**It must land ratcheted, or it gets disabled.** Enabling ~200 rules on a codebase this size
produces hundreds of findings at once, and the honest outcome of that is someone turning the stage
off. So:

1. Generate a **baseline** so existing findings do not block, exactly as `lint-baseline.xml` does.
2. Fail the build only on **new** findings.
3. Work the baseline down deliberately, as its own later effort — not inside this task.

`verify.sh`'s ktlint stage is the model: a real gate that means something because it was introduced
against a clean tree.

## Acceptance Criteria

- [ ] detekt on the latest 1.23.x, wired as a `verify.sh` stage
- [ ] Rule sets limited to **complexity, potential-bugs and coroutines**; style and naming disabled
      with the reason recorded (ktlint owns them)
- [ ] A baseline file committed, so the stage fails only on new findings
- [ ] **Sabotage-verified**: a deliberately over-complex function, and a `GlobalScope.launch`, each
      fail the stage. A linter that cannot be shown to fail is not a gate
- [ ] Runtime measured and recorded; if it is material, say which `verify.sh` mode it belongs in
- [ ] The baseline's size is recorded, so the debt it represents is visible rather than hidden
- [ ] `./verify.sh` green

## Notes

Sequenced after cu-217 so detekt runs against the Kotlin version it will keep analysing — a
compiler-version mismatch in a static analyser produces confusing parse failures rather than
findings.

Closing status **Done**: a build gate, sabotage-verified, no user-visible surface.

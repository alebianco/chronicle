---
id: cu-215
title: 'detekt in verify.sh: complexity, potential bugs and coroutine misuse'
status: Done
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - tooling
  - maintainability
milestone: m-2
dependencies:
  - cu-210
  - cu-214
priority: medium
ordinal: 101000
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

- [x] detekt on the latest 1.23.x, wired as a `verify.sh` stage
- [x] Rule sets limited to **complexity, potential-bugs and coroutines**; style and naming disabled
      with the reason recorded (ktlint owns them)
- [x] A baseline file committed, so the stage fails only on new findings
- [x] **Sabotage-verified**: a deliberately over-complex function, and a `GlobalScope.launch`, each
      fail the stage. A linter that cannot be shown to fail is not a gate
- [x] Runtime measured and recorded; if it is material, say which `verify.sh` mode it belongs in
- [x] The baseline's size is recorded, so the debt it represents is visible rather than hidden
- [x] `./verify.sh` green

## Notes

Sequenced after cu-214 (its Kotlin step) so detekt runs against the Kotlin version it will keep analysing — a
compiler-version mismatch in a static analyser produces confusing parse failures rather than
findings.

Closing status **Done**: a build gate, sabotage-verified, no user-visible surface.

## Closing notes (2026-09-07)

detekt **1.23.8** (the last of the 1.23 line) is stage 7 of `verify.sh`, between `assembleDebug` and
`lintDebug`. Config `config/detekt/detekt.yml`, baseline `config/detekt/baseline-debug.xml`, pinned
by `DetektRuleSetTest`.

**The stage is `:app:detektDebug`, not `detekt`, and that is the single most important thing here.**
The bare task analyses without a classpath. Roughly half of potential-bugs needs type resolution to
decide anything, and without it those rules do not report a false negative — they report *nothing*,
which looks exactly like a clean tree. The measurement:

| | findings |
|---|---|
| `detekt` (no type resolution) | **31** |
| `detektDebug` (type resolution) | **120** |

The 89-finding gap is `UnsafeCallOnNullableType`, `ElseCaseInsteadOfExhaustiveWhen`,
`UnnecessarySafeCall`, `UnreachableCode` and `NullableToStringCall` — every one of them invisible in
the cheap task. Wiring the cheap one would have shipped a gate that passed vacuously. The guard test
asserts on the substring specifically, because `detektDebug` *contains* `detekt` and a naive check
would pass on the downgrade.

**Baseline: 52 findings**, after two rules were switched off. Composition is tabulated in
`11-verify-loop.md`; the largest block is `LongMethod` at 18, then
`ElseCaseInsteadOfExhaustiveWhen` at 11 and `UnsafeCallOnNullableType` at 7. That is the debt, and
it is small enough to work down as a real task rather than a permanent fixture.

**Two rules were turned off despite sitting in enabled sets**, both with the reason recorded beside
them in `detekt.yml`:

- **`UnreachableCode` is broken** under 1.23.x on this code. It flags the right-hand side of every
  elvis-return — `val x = foo() ?: return null` reports the `return null` as unreachable. All 34 of
  its findings were that shape and none were real. Baselining 34 false positives would have hidden
  the true debt figure behind noise, and a rule whose every finding is wrong teaches people to
  ignore the linter.
- **`NullableToStringCall` is correct but not about defects.** All 34 were log lines interpolating
  a nullable (`Timber.e("… ${e.message}")`). "null" in a diagnostic string is the honest rendering
  of a null. Left on it would fire on every future log line and catch nothing.

**`IgnoredReturnValue` does not do what the ticket expected, and this is worth recording.** The
ticket anticipated justifying the deliberate `tryEmit` calls in `SleepTimerBus` and
`PlaybackErrorBus`. The rule never fires on them: it defaults to `restrictToConfig: true`, so it
only reports functions annotated `@CheckReturnValue`, and `MutableSharedFlow.tryEmit` carries no
such annotation. It is left active — it will catch an annotated API the day one arrives — but it
currently reports nothing, and nothing needed justifying.

**Runtime: ~12s** of analysis on top of an already-compiled debug variant (measured with `--rerun`
on the detekt task alone, upstream compilation up to date). Material enough to keep out of
`--quick`, which is also the right call for a second reason: the task *needs* the debug compile
that `--quick` deliberately skips, so putting it in the inner loop would add the whole compilation,
not 12 seconds.

**Sabotage-verified three ways**, each restored in a separate call:

| Sabotage | Result |
|---|---|
| A `GlobalScope.launch` and a CC-19 function in a new `SabotageProbe.kt` | `detektDebug` FAILED — "Analysis failed with 2 weighted issues", naming `GlobalCoroutineUsage` and `CyclomaticComplexMethod`. The 52 baselined findings stayed correctly ignored, which proves the ratchet as well as the rules |
| `style: active: true` in `detekt.yml` | `DetektRuleSetTest > detekt does not enable a rule set ktlint owns` FAILED |
| `verify.sh` downgraded to the bare `"$GRADLE" detekt` | `DetektRuleSetTest > verify_sh runs the type-resolving detekt task` FAILED |

**Two things outside the task's scope, noted rather than fixed.** `list-build-gates.sh`'s `is_gate`
heuristic only matched tests that walk the source tree, so a config-reading guard like this one was
invisible to the generated table — widened, since shipping a gate the gate-index cannot see is the
exact rot that script exists to prevent. Separately, that table in `09-enforced-rules.md` is stale
by six guards (`BaseUrlContractTest`, `PitestScopeTest`, `RetiredDependencyTest`,
`TaskIdReferenceTest`, `CoverImageTest`, and a since-documented `ViewModelFactoryTest`); only this
task's own row was added, because regenerating the whole table belongs in a backlog audit rather
than here.

`./verify.sh` green — **9 stages**, 1710 tests, coverage 54.47% against a 54.48% baseline (-0.01%,
within the 0.05% tolerance; no production code changed, so this is codegen jitter).

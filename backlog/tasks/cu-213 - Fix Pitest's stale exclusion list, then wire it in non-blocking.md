---
id: cu-213
title: 'Fix Pitest''s stale exclusion list, then wire it in non-blocking'
status: Done
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - testing
  - tooling
milestone: m-2
dependencies:
  - cu-210
priority: medium
ordinal: 100000
---

## Description

**Pitest does not currently run.** Measured, not assumed — `./gradlew pitestDebug` fails in 51 s:

```
SEVERE : ...SettingsBackupRepoTest... did not pass without mutation
SEVERE : ...DetailsScreenTest... did not pass without mutation
MINION : Method isLoggable in android.util.Log not mocked
```

The cause is the one cu-57 predicted in its own comment. PIT sends **all 218 test classes** to its
minion, and `excludedTestClasses` is a **hand-maintained list** of Robolectric tests. There are now
**62 Robolectric classes** — the Compose migration added most of them, every `*ScreenTest` among
them — and the list fell behind. PIT + Robolectric is broken upstream
(koral--/gradle-pitest-plugin#80, open since 2022) and fails *silently*, so a stale list does not
merely error: it can report sabotage-verified tests as worthless.

So the owner's ask — maintain and strengthen mutation testing — starts with making it work again,
and specifically with removing the hand-maintained list, because a list that must be updated by hand
has now demonstrably not been.

## The fix, in order

1. **Stop hand-maintaining the exclusion.** Either derive it (a class using `RobolectricTestRunner`
   is excluded) or invert to an explicit `targetTests` allowlist of plain-JVM classes. Deriving is
   better: the failure mode of forgetting is then impossible rather than merely documented.
2. **Guard it.** A test that fails if a Robolectric class is in PIT's scope — this is a
   configuration invariant, and the last one was only enforced by a comment.
3. **Then** wire a `verify.sh` stage, **non-blocking at first**: report the score, fail nothing.
4. **Then** ratchet a floor, once there is a real baseline to ratchet from. Not before — a threshold
   guessed ahead of the measurement is either vacuous or blocks the build on day one.

## Where it may run, and what it must not do

Not in `--quick`. The current run burns 51 s *failing*; a working run over 18 target classes will be
longer. It answers a different question from coverage — "would the tests notice if this code
changed?" rather than "was this line executed?" — and that answer is worth minutes, not seconds.

**A gate that is too slow gets disabled**, which is worse than not having it. If the measured runtime
makes a `verify.sh` stage untenable, put it in CI and say so in the task rather than forcing it into
the inner loop.

## Why this is worth doing at all

It would plausibly have caught two vacuous tests found by hand in this session:

- a token-redaction test that passed with `sanitizeHeader` **deleted**, because logging was off under
  unit test
- a download fixture where `value = id`, so an assertion that the callback reported the right field
  could not fail

Both were found by deliberately sabotaging the code — the same question PIT automates.

## Acceptance Criteria

- [x] `./gradlew pitestDebug` completes successfully, and its runtime is **recorded** in the closing
      notes
- [x] The Robolectric exclusion is derived or inverted, **not** a hand-maintained list
- [x] A guard fails if a Robolectric test enters PIT's scope
- [x] Sabotage-verified: a deliberately weakened assertion in a targeted class shows up as a
      surviving mutant. Without this, the tool is trusted rather than known to work
- [x] Wired non-blocking, with where-it-runs decided from the measured runtime
- [x] A score floor is **either** ratcheted with its baseline recorded **or** explicitly deferred
      with a reason
- [x] `./verify.sh` green

## Notes

Do **not** widen `targetClasses` in this task. The allowlist is cu-57's design and its reasoning
holds — generated code and Robolectric-covered classes produce meaningless mutants. Getting the
existing 18 working and honest is the whole job; widening is a later, separate judgement.

## Closing notes

### Runtime, measured

| Run | Wall clock |
|---|---|
| `./gradlew pitestDebug` before (failing) | **1m 25s** to fail |
| `./gradlew pitestDebug` after, cold | **1m 00s** (PIT's own total: 47s) |
| `./verify.sh --mutation` with everything else up to date | 13s |

### The baseline

472 mutations generated, **185 killed (39%)**, 122 survived, 165 with no coverage. Line coverage of
the mutated classes 80%, **test strength 60%**, 170 test classes examined, 1723 tests run.

### What was actually wrong

Three things, and only the first was known:

1. The list was 48 classes short — 62 Robolectric classes against 14 listed.
2. **Two of the 14 listed classes no longer existed** (`ChapterBackfillSqlTest`,
   `ProgressIndicatorTest`), so the list was rotting in both directions at once.
3. `ReauthenticationTest.AgainstTheRealImplementation` is a Robolectric class *nested inside a
   plain-JVM outer class*. The old list caught it with a trailing `*` wildcard, which also excluded
   the perfectly usable outer class. The derivation emits the JVM binary name `Outer$Inner` and
   excludes only the nested one.

### The fix

`robolectricTestClasses()` in `app/build.gradle.kts` scans the test sources for
`@RunWith(RobolectricTestRunner::class)` and emits binary names. It is called **once**, into
`pitestRobolectricExclusion`, which feeds both `excludedTestClasses` and a `writePitestScope` task
that publishes the list for the guard. Calling it twice was the first draft, and it was wrong: the
guard could then pass against a list PIT never saw.

Source-scanning rather than reading compiled classes is deliberate — the value is needed at
configuration time, and reading `build/` would make the exclusion silently *empty* whenever PIT is
configured before the test classes exist, which is the same failure mode in a new costume.

### Sabotage verification

Both directions, each restored in a separate call.

**The guard.** Two independent sabotages, both caught:

- Derivation made to skip `*Screen*` files → `every Robolectric test is excluded from PIT's scope`
  FAILED.
- `pitestRobolectricExclusion` truncated to `.take(3)` → both that assertion and `the exclusion is
  derived from the sources rather than hand-maintained` FAILED.

**PIT itself** (the AC-4 check that the tool actually works). Weakened
`CompletionStateTest."a book exactly at the window threshold is completed"` so its `threshold` no
longer sat on the boundary. Killed mutants went **185 → 184**, and a diff of the two XML reports
named the single mutant that flipped:

```
AudiobookKt | isCompleted | line 345 | changed conditional boundary
  BASELINE KILLED -> SABOTAGED SURVIVED
```

That is an off-by-one in the book-completion window that the weakened test could no longer see —
exactly the class of defect this task existed to make visible.

One earlier attempt is worth recording because it *failed usefully*: weakening
`ActiveTrackTest."book progress is the active track offset plus everything before it"` to a
vacuous `assertEquals(x, x)` did **not** drop the score. `BookProgressDerivationTest` independently
killed the same mutant. Redundant coverage absorbs a single weakened test, so a sabotage check has
to target a mutant with exactly one killing test.

### Where it runs, and the deferred floor

`./verify.sh --mutation` — a 9th stage, opt-in, **never fatal**. Proven non-blocking by pointing the
stage at a task that does not exist: it printed the failure and the gate still exited 0.

Not in the default gate (~60s on top of a full verify) and not in CI. `--quick --mutation` is
**refused with exit 2** rather than accepted and ignored: `--quick` returns before the opt-in
stages, so the flag would otherwise be silently dropped.

**The score floor is deferred, deliberately.** 39% is the first honest measurement this project has
ever had, taken the same day the tool started working. A floor set from a single run is a number
nobody has interrogated, and 165 no-coverage mutants say the allowlist reaches code the plain-JVM
suite barely touches — that wants understanding before it wants a threshold. Ratchet it once the
number has held across a few runs, and preferably after deciding whether those 165 are worth
covering or the allowlist is simply pointed at the wrong classes.

### Closed `Done`, with one thing worth a glance

Every criterion here is machine-proved — a build gate, a sabotage diff, a recorded runtime — and
nothing changed a screen or made a product choice, so this is `Done` rather than `In Review`.

The one judgement that is mine rather than the record's: **deferring the score floor**, which AC 6
explicitly permits either way. If you would rather have a floor now, 39% is the number to ratchet
from, and `coverage-ratchet.sh` is the pattern to copy. My argument for waiting is in the section
above.

### Incidental corrections

- `CLAUDE.md` said "18 build gates"; the table had 29 before this change, 30 after.
- `11-verify-loop.md` called `--instrumented` "a 7th stage" while listing 8 stages.
- The build comment pointed at `reports/pitest/index.html`; the real path is `pitest/debug/`.
- `./list-build-gates.sh --check` fails on `ModelsWithoutDiTest`, which has no KDoc summary line.
  **Pre-existing and left alone** — that script is not a `verify.sh` stage, so it gates nothing, and
  fixing it is not this task.

---
id: cu-213
title: "Fix Pitest's stale exclusion list, then wire it in non-blocking"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - testing
  - tooling
milestone: m-3
dependencies: 
  - cu-210
priority: medium
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

- [ ] `./gradlew pitestDebug` completes successfully, and its runtime is **recorded** in the closing
      notes
- [ ] The Robolectric exclusion is derived or inverted, **not** a hand-maintained list
- [ ] A guard fails if a Robolectric test enters PIT's scope
- [ ] Sabotage-verified: a deliberately weakened assertion in a targeted class shows up as a
      surviving mutant. Without this, the tool is trusted rather than known to work
- [ ] Wired non-blocking, with where-it-runs decided from the measured runtime
- [ ] A score floor is **either** ratcheted with its baseline recorded **or** explicitly deferred
      with a reason
- [ ] `./verify.sh` green

## Notes

Do **not** widen `targetClasses` in this task. The allowlist is cu-57's design and its reasoning
holds — generated code and Robolectric-covered classes produce meaningless mutants. Getting the
existing 18 working and honest is the whole job; widening is a later, separate judgement.

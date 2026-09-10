---
id: cu-237
title: A guard test's comment regex overflowed the stack only on CI
status: Done
assignee: []
created_date: ''
updated_date: '2026-09-10 07:00'
labels:
  - testing
  - tooling
  - ci
milestone: m-2
dependencies: []
priority: medium
ordinal: 104000
---

## Description

`RawDurationFormatTest > no player source contains a literal raw duration pair` failed on a GitHub
runner with `java.lang.StackOverflowError at Pattern.java:4847`, while passing on the owner's
machine — including under `--rerun-tasks` and across the full 1812-test suite.

Found incidentally in run 34341471957 while probing cu-222's api27 question. Unrelated to cu-222 and
predating it (the test last changed in 50095985), but it failed `verify.sh` on CI by itself.

## Cause — measured, and not what was first supposed

The stripper at `RawDurationFormatTest.withoutComments()` was:

```kotlin
Regex("""/\*(?:[^*]|\*(?!/))*\*/""", RegexOption.DOT_MATCHES_ALL)
```

Both branches of `(?:[^*]|\*(?!/))` can match at the same position, and Java's `Pattern` explores
that recursively — one stack frame per character. The CI trace confirms it: the frames cycle
`Loop → GroupTail → BranchConn → CharProperty → Branch → GroupHead`, which is the alternation, not
the outer quantifier.

**What actually differs between local and CI is thread stack size, and only that.** Measured by
recursing until overflow:

| JVM / stack | frames available |
|---|---|
| JDK 17, arm64 macOS default | 45,304 |
| JDK 21, arm64 macOS default | 39,243 |
| JDK 17, `-Xss1m` | 18,237 |
| JDK 17, `-Xss512k` | 5,966 |

A dev machine has over twice the headroom of a 1 MB stack, and the real files sit inside that
margin. Running the exact chain over the 51 scanned sources:

```
-Xss8m    0 overflowed
-Xss2m    0 overflowed
-Xss1m    1 overflowed  (CastButton.kt, 3210 bytes)
-Xss768k  6 overflowed
-Xss512k 16 overflowed
```

Two hypotheses were **ruled out** by measurement, so they are not retried:

- **Not the JDK version.** Local is JDK 21 (an uncommitted `.tool-versions` edit), CI is JDK 17.
  Both scan all 51 files with 0 overflows at their default stack. The version is a red herring.
- **Not comment length.** `SleepTimerBus.kt` has the longest block comment (2320 chars) and
  survives; `CastButton.kt` (3210 bytes total) is the first to fail at 1 MB. What matters is the
  number of backtrack-eligible positions, not span length.

## Fix

`/\*.*?\*/` with `DOT_MATCHES_ALL` — a reluctant quantifier, no alternation, constant in stack
depth.

**Verified equivalent, not assumed:** old and new patterns produce byte-identical output on all 51
scanned files, and agree on 8 adversarial inputs (`/* a ** b */`, `/**/`, `/* * / */`, `/***/`,
back-to-back comments, an unterminated `/*`, a multi-line comment, and a trailing stray `*/`).

## Acceptance Criteria

- [x] Reproduced locally before fixing — `-Xss768k` on the unit-test JVM turns the CI-only failure
      into a local one (3 tests failed, same `StackOverflowError`)
- [x] Cause measured rather than inferred: the alternation frames in the CI trace, plus the
      stack-depth table above
- [x] The JDK-version and comment-length hypotheses explicitly disproved by measurement
- [x] Fixed with a reluctant quantifier, and proved output-equivalent on all 51 real files plus 8
      adversarial cases
- [x] Regression guard added — `stripping comments does not recurse per character` runs the stripper
      on a deep star-heavy input inside a **256 KB** thread, so the hazard is caught on any machine
      regardless of the host's default stack
- [x] Sabotage-verified: restoring the alternation fails exactly the new guard and nothing else
- [x] The fix survives 256 KB, a third of the stack that broke the old pattern
- [x] `./verify.sh` passes all 10 stages

## Notes

Closing **Done**: every claim here is machine-checked, and the guard fails when the fix is reverted.

Two traps worth carrying forward:

- **A KDoc cannot quote this pattern.** Writing the old regex into the doc comment above the fix
  embeds a literal `*/`, which closes the comment early and produced 30 "Expecting member
  declaration" errors. The doc now points at the code instead of quoting it — the same
  documentation-quoting trap this guard was originally written to survive.
- **A sabotage run that fails to compile proves nothing.** The first sabotage attempt failed on
  syntax errors, which looks identical to a caught regression in a filtered log. Check *why* the
  build went red, not just that it did.

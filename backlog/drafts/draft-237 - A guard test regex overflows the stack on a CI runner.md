---
id: DRAFT-237
title: "A guard test's comment-stripping regex overflows the stack on a CI runner"
status: Draft
assignee: []
labels: [testing, tooling, ci]
dependencies: []
priority: medium
milestone: m-3
---

## The fault

`RawDurationFormatTest > no player source contains a literal raw duration pair` fails on a GitHub
runner with:

```
java.lang.StackOverflowError at Pattern.java:4847
```

Found incidentally in run **34341471957** (2026-09-09) while probing cu-222's api27 question. It is
**unrelated to cu-222** and predates that probe — the test last changed in 50095985 — but it fails
`verify.sh` on CI on its own, so it will keep the gate red until fixed.

## Why it passes locally and fails on CI

It is not flakiness and not a code change. The suspect is
`RawDurationFormatTest.kt:258`, which strips block comments before scanning:

```kotlin
replace(Regex("""/\*(?:[^*]|\*(?!/))*\*/""", RegexOption.DOT_MATCHES_ALL), "")
```

`(?:[^*]|\*(?!/))*` is a quantified alternation where both branches can match at the same position,
which is the textbook shape for catastrophic backtracking; Java's `Pattern` recurses per backtrack
frame, so a long comment block blows the stack rather than merely running slow. A CI runner's
default JVM thread stack is smaller than a local one's, which is why the same input crosses the
limit there and not here.

**Unverified** — the mechanism is inferred from the stack trace and the regex shape, not measured.
Confirm before fixing.

## Shape of a fix

Cheapest correct fix is a non-backtracking formulation, e.g. a reluctant quantifier
(`/\*.*?\*/` with `DOT_MATCHES_ALL`), which needs no alternation at all and is what this was
presumably reaching for. Worth checking the sibling patterns on lines 250 and 259 for the same
hazard while there.

Prove it the way this project proves guards: reproduce first (a long enough comment block, or a
deliberately small `-Xss`), then fix, then sabotage-verify.

## Why a draft, not a task

The mechanism is a hypothesis. Whoever picks it up should reproduce it before writing the fix —
otherwise a green run proves nothing, since it already passes locally.

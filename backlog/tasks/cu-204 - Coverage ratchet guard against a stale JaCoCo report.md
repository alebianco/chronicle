---
id: cu-204
title: 'Coverage ratchet: guard against a stale JaCoCo report'
status: Done
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - testing
milestone: m-2
dependencies: []
priority: medium
ordinal: 70000
---

## Description

**The original premise was wrong, and is corrected here rather than deleted** — the reasoning is
worth keeping so it is not re-derived.

This task was filed claiming the per-package gate lets within-tolerance drops accumulate, because
`coverage-ratchet.sh` reads its baseline from the working tree and rewrites it in place. Reading
`compare-package-coverage.py` disproves it:

```python
ratcheted = {n: max(v, baseline.get(n, v)) for n, v in current.items()}
```

A dip keeps the **higher** number, exactly like the aggregate gate. Drops cannot accumulate, and
the file's own docstring says so ("a within-tolerance dip keeps the higher number"). The
self-test covers it.

## The real defect

What actually misled cu-201 was a **stale report**. `./coverage-ratchet.sh` consumes whatever
JaCoCo XML is on disk, and `jacocoTestReport` is frequently `UP-TO-DATE` — so the ratchet can
compare a report that predates the test run, and record its numbers as the committed baseline.
That is how `features/settings/compose 84.84` was committed while a clean tree measures 83.14; the
number was never reproducible.

The same trap is recorded for sabotage verification (`--rerun-tasks` is required there too), so
this is the second time it has cost real debugging.

## Acceptance Criteria

- [x] The ratchet cannot consume a JaCoCo report older than the test results it describes — either
      it produces the report itself, or it refuses a stale one with a clear message
- [x] Sabotage-verified: a deliberately stale report makes the gate fail rather than silently
      recording old numbers
- [x] The aggregate/per-package tolerance difference documented where the tolerances are described,
      **including** that both keep a high-water mark

## Closing notes

**The stale-report defect reproduced on this branch before the fix**, which is the evidence the
task wanted. Same tree, same tests, two answers:

| report | ratchet says | exit |
|---|---|---:|
| whatever was on disk (`jacocoTestReport` UP-TO-DATE) | `COVERAGE REGRESSION  baseline: 53.30%  current: 53.23%  delta: -0.07%` | 1 |
| regenerated with `--rerun-tasks` | `coverage rose 53.30% -> 53.31% (+0.01%)` | 0 |

So the trap costs both ways: it can fail a green tree, and — the dangerous direction — it can
*record* the stale numbers as the committed baseline, which is how
`features/settings/compose 84.84` was committed against a real 83.14.

**The fix** is a staleness gate in `coverage-ratchet.sh`, ahead of every read of the report: if any
file in `app/build/test-results/testDebugUnitTest/` is newer than the JaCoCo XML, the report cannot
describe those results, so the script refuses with the regeneration command rather than comparing.
It runs before the baseline-seeding branches, so a stale run cannot write a baseline by any path.

Chose "refuse" over "produce the report itself": the script is invoked from `verify.sh` right after
the test stage and also by hand, and having it shell out to Gradle would both duplicate `verify.sh`'s
staging and make a fast check slow. Refusing keeps it a pure gate.

**Sabotage-verified** — `touch -t 202001010000` on the report:

- fresh report → `exit=0`, compares normally
- backdated report → `exit=1`, `STALE COVERAGE REPORT`, and `git status` confirms **neither**
  baseline file was modified

**Tolerances** documented at their declaration, replacing the note that covered only the aggregate.
Both gates keep a high-water mark — the aggregate by never rewriting on the `OK` branch, the
per-package explicitly via `max(v, baseline.get(n, v))` in `compare-package-coverage.py` — so a
tolerance is a per-comparison allowance against that mark and within-tolerance drops cannot
accumulate. The two differ only in magnitude, because a 400-instruction package moves 0.25% per
instruction.

`verify.sh` deliberately unchanged: the guard failing loudly is the wanted behaviour, and adding a
`--rerun-tasks` there would slow every run to paper over a bug now detected.

Closing **Done** rather than In Review — this is a build gate with no user-visible surface, and the
guard is proved by sabotage in both directions.

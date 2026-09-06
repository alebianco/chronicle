---
id: cu-204
title: "Coverage ratchet: guard against a stale JaCoCo report"
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - testing
milestone: m-2
dependencies: []
priority: medium
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

- [ ] The ratchet cannot consume a JaCoCo report older than the test results it describes — either
      it produces the report itself, or it refuses a stale one with a clear message
- [ ] Sabotage-verified: a deliberately stale report makes the gate fail rather than silently
      recording old numbers
- [ ] The aggregate/per-package tolerance difference documented where the tolerances are described,
      **including** that both keep a high-water mark

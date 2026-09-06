---
id: cu-204
title: The per-package coverage gate lets drops accumulate
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

`coverage-ratchet.sh` reads `coverage-baseline-packages.txt` from the **working tree**, not from
`HEAD`, and rewrites it in place on every run. So a run that lowers a package by less than the
0.50% tolerance writes the lower number, and the *next* run compares against that — successive
within-tolerance dips accumulate without any single run reporting a regression.

This is precisely the property the aggregate gate was deliberately built to avoid. CLAUDE.md says
so about `coverage-baseline.txt`: *"the no-regression branch deliberately does not rewrite the
file, so a second consecutive dip is measured against the same high number and fails. Drops cannot
accumulate."* The per-package gate does not share that behaviour, and nothing recorded the
difference.

**Found during cu-201**, where it masked a real discrepancy for several rounds: `verify.sh`
reported PASSED while the committed baseline said `features/settings/compose 84.84` and the tree
measured 83.14. The run that would have caught it had already written 83.14 into the file.

## Additional finding, already corrected

The committed 84.84 was **never reproducible**. Stashing all of cu-201's work and measuring the
clean tree gives 83.14 — no change of ours was in that package. The entry came from a stale JaCoCo
report: `jacocoTestReport` was UP-TO-DATE, so the ratchet read a report that predated the test run.
cu-201 corrected the file. Worth noticing that `--rerun-tasks` was needed to get an honest number,
the same trap the `chronicle-sabotage-rerun-tasks` memory records for sabotage verification.

## Acceptance Criteria

- [ ] A within-tolerance dip does not lower the committed baseline — match the aggregate gate's
      high-water-mark behaviour, so consecutive dips fail
- [ ] The ratchet's inputs cannot be stale: either it produces the JaCoCo report itself or it
      refuses one older than the test run
- [ ] Both behaviours sabotage-verified — a check that cannot fail proves nothing
- [ ] The aggregate/per-package difference documented wherever the tolerances are described

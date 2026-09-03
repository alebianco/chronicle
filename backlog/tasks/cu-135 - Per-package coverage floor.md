---
id: cu-135
title: Per-package coverage floor
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - agentic
  - testing
milestone: m-2
dependencies: []
priority: medium
---

## Description

Found in the 2026-09-02 branch review. The single aggregate coverage number hides an inversion:
**coverage is highest where bugs are cheapest, and lowest where they are most expensive.**

Measured at 28.49% overall (now 31.74%):

| Package | Coverage | Instructions |
|---|---|---|
| `data/model` | 82.4% | 3,446 |
| `data/local` | 56.2% | 6,599 |
| `features/player` | **33.7%** | 9,570 |
| `features/settings` | **12.2%** | 4,479 |
| `features/download` | **10.7%** | 2,378 |
| `features/login` | **9.3%** | 3,298 |
| `features/library` | **2.2%** | 3,234 |
| `features/home` | **0.0%** | 1,344 |
| `features/collections` | **0.0%** | 2,726 |

A single global ratchet cannot see this: a 0%-covered `features/home` sits next to an 82%
`data/model` and averages out to a passing number. The aggregate can even *rise* while the risky
packages get worse.

Note this is not a request for a coverage push. The suite is good — 762 tests, none hollow, none
disabled, real sabotage-verification in `RoomSchemaTest`. The problem is that the *gate* is blind
to where the coverage sits.

## Acceptance Criteria

- [x] `coverage-ratchet.sh` (or a sibling) enforces a floor per package, baselined per package in
      a plain committed file so movements show up in a diff (D12 rule 6)
- [x] Baselines are set at *current* values, so this is a ratchet and not a migration project
- [x] A drop in any single package fails the gate even when the aggregate rises — **verified by
      deliberate sabotage**, per the m-0 rule
- [x] ~~Fix the aggregate ratchet's downhill walk while here~~ — **the walk does not exist; see
      below.** The real defect in that area, a comment contradicting the code by 5×, is fixed.
- [x] `CLAUDE.md` describes the real behaviour of both gates

## Implementation Notes

`coverage-ratchet.sh` now runs two gates off one JaCoCo report: the existing aggregate against
`coverage-baseline.txt`, and a new per-package floor against `coverage-baseline-packages.txt` —
22 lines of `<package> <pct>`, seeded at current values, sorted for a stable diff. Classification
lives in `compare-package-coverage.py`, which emits `REGRESSED`/`ADDED`/`DEPARTED`/`RAISED` lines
so the shell has no prose to parse.

Tolerances: **0.05%** aggregate (unchanged), **0.50%** per package. The looser per-package figure
is not slack — a 400-instruction package moves 0.25% per instruction, so the same absolute jitter
is a much larger percentage there.

### The fourth criterion was wrong, and that is the finding

The task (and the CLAUDE.md line it was written from) claimed the aggregate tolerance "permits
−0.05% per run cumulatively because the baseline is not a high-water mark". **It is a high-water
mark.** The no-regression branch deliberately does not rewrite the baseline file, so:

```
baseline 31.74, coverage 31.70  -> OK    (baseline stays 31.74)
baseline 31.74, coverage 31.66  -> FAIL  (measured against 31.74, not 31.70)
```

Simulated over a sequence of small drops before touching anything: the *second* consecutive dip
fails. Drops cannot accumulate, and there was nothing to fix. What was actually wrong was the
comment above the check — it claimed to absorb "sub-0.01% jitter" while the code allowed 0.05%, a
5× mismatch, and that mismatch is what made the behaviour look broken to a reader. The comment is
gone, the tolerance is now a named constant, and CLAUDE.md states the real semantics for both
gates, including a note that the earlier claim was false so the next reader does not re-file this.

### What the new gate refuses to do quietly

Three behaviours chosen because the alternative is a silent hole:

- **A new package is seeded *and announced***, never ignored. Silently admitting an untested new
  package is precisely the blindness this gate closes.
- **A departed package is pruned and announced**, so the file cannot rot into stale floors.
- **A malformed or empty baseline is fatal**, including a duplicated package line — a duplicate
  would keep only the last value, lowering a floor without showing as a change in the diff.

### Sabotage verification

Criterion 3 exactly: baseline aggregate lowered to 31.00 (so the aggregate reads as a **rise**,
+0.74%, and is ratcheted up) while one package floor was raised 5 points. Output:

```
coverage-ratchet: coverage rose 31.00% -> 31.74% (+0.74%); baseline ratcheted up.

  PER-PACKAGE COVERAGE REGRESSION
  io/github/mattpvaughn/chronicle/features/download   15.68    10.68
```

Exit 1. The aggregate rose and the build still failed — which the old gate could not do. Also
verified by sabotage: new-package seeding, departed-package pruning, and three malformed-baseline
shapes (extra fields, non-numeric, empty), each failing closed with a line-numbered message.

**The gate's own classifier is self-tested on every run.** `compare-package-coverage.py
--self-test` covers seven behaviours (regression, within-tolerance dip keeping the higher floor,
rise ratcheting up, add, depart, and both sides of the tolerance edge) and `coverage-ratchet.sh`
invokes it before trusting the comparison. Proven able to fail: disabling the regression branch
produced `self-test FAILED: a package drop is REGRESSED: []`, exit 1.

### Two defects found in my own work by self-review

- **A `BrokenPipeError` masked the real error.** Bailing on a malformed baseline before draining
  stdin left the JaCoCo producer writing into a closed pipe, printing exception noise over the
  line-numbered message. Stdin is now read first.
- **The `.next` temp file leaked on unhandled exit paths.** Now cleaned by a `trap ... EXIT`
  rather than by an `rm` on each of the two paths that happened to be handled.

### Verification

`./verify.sh` green, all 6 stages, both coverage gates active. Aggregate 31.74%, all 22 packages
at or above floor.

### Follow-up, deliberately not done

The floors are seeded where coverage *is*, including four packages at 0.00%. That is the ratchet
being a ratchet, not an endorsement — a 0% floor still catches nothing, because there is nothing
below it. Raising the risky packages off the floor is a coverage-writing task, separate from this
gate-shaping one, and is not filed: the review's point was that the *gate* was blind, and it no
longer is.

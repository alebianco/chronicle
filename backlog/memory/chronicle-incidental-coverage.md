---
name: chronicle-incidental-coverage
description: Coverage that comes from another component's test disappears when that component is deleted; measure the clean tree before believing a per-package drop
metadata:
  type: project
---

Deleting `GroupedSearchAdapter` in cu-202 took `bindImageRounded` from 77 covered
instructions to **zero** without touching the function. Its only coverage came from
that adapter's test inflating rows. Incidental coverage is not coverage: it vanishes
for reasons unrelated to the code, and it never asserted the behaviour anyway.

A per-package drop has three quite different causes, and they need different responses:

- **A real gap** — code that was only ever executed, never asserted. Write the test
  (`bindImageRounded` now has one for each cu-110 guard, sabotage-verified).
- **Structural** — covered code moved to a new package. `views` fell 35.3 → 29.3
  while `views/compose` seeded at 87.7. Lower the baseline and say why.
- **Not yours at all** — the committed number was never reproducible. Stash everything
  and measure the clean tree; that is how cu-201 found `settings/compose 84.84` had
  come from a stale JaCoCo report.

**How to apply:** never lower a baseline before measuring HEAD with the work stashed
and `--rerun-tasks`. The gate will not tell you which case you are in — and note the
ratchet consumes whatever JaCoCo XML is on disk, which is often `UP-TO-DATE` and so
can predate the test run. See [[chronicle-coverage-gate-accumulates]].

---
name: chronicle-coverage-gate-accumulates
description: CORRECTED 2026-09-06 — coverage dips do NOT accumulate in either gate; both keep the higher floor. The real trap is a stale UP-TO-DATE JaCoCo report.
metadata: 
  node_type: memory
  type: project
  originSessionId: 426ecf77-5c86-4c93-89f8-cdcec1633fb4
  modified: 2026-09-06T18:06:19.106Z
---

**This memory previously claimed that within-tolerance package dips accumulate silently.
That is false, and it was verified false against the code on 2026-09-06.**

- `compare-package-coverage.py` line 93 writes
  `ratcheted = {n: max(v, baseline.get(n, v)) for n, v in current.items()}` — every package
  keeps **the higher of baseline and current**, so a dip inside the 0.50% tolerance never
  lowers the floor.
- The aggregate gate's `OK` branch does not write the baseline file at all; only `RAISE` does.
- `compare-package-coverage.py` carries a **self-test that runs on every ratchet invocation**
  and asserts exactly this: *"a dip inside tolerance passes"* **and** *"...and keeps the higher
  floor"* (`written == {"a": 50.0}` after measuring 49.7). It reports
  `self-test passed (7 behaviours)` on each run.

So `backlog/docs/reference/11-verify-loop.md` is right — drops cannot accumulate — and **cu-204
was filed on a false premise**; it should be closed as a non-issue rather than implemented.

This is the *second* time this exact claim has been asserted and then disproved: cu-135 was
filed for the same reason and found "the walk does not exist — the comment in the script was
describing a 0.01% tolerance the code never had."

## What was real in the original observation

The cu-201 symptom was genuine; the diagnosis was not. `settings/compose 84.84` was not
reproducible because **the JaCoCo report was UP-TO-DATE and predated the test run** — the same
class of trap as [[chronicle-sabotage-rerun-tasks]], not a ratchet defect.

**How to apply:** when a coverage number looks wrong, suspect a stale report first. Stash to
measure the clean tree and force `--rerun-tasks`. Do not infer gate *logic* from an observed
number — read the code and run
`python3 compare-package-coverage.py --self-test`, which answers this question directly.

**The wider lesson:** a confident, well-written memory built from one session's observation
can be flatly wrong about mechanism. Verify a claim about how a gate behaves against the gate,
not against the symptom that prompted it. See [[chronicle-verify-research-claims]].

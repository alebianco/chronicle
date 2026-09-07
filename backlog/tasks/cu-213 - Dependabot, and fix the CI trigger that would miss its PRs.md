---
id: cu-213
title: "Dependabot, and fix the CI trigger that would miss its PRs"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - trust
milestone: m-3
dependencies: 
  - cu-210
priority: medium
---

## Description

No dependency-update automation exists. The programme in cu-210 is largely version bumps, and the
fact that **Room's KMP support landed in 2.8.3 while this project sits on 2.8.1** was found by
searching the release notes by hand — exactly the kind of "a version you did not know mattered" that
Dependabot surfaces for free.

## Two things to get right

**1. It must target `feature/agentic-dev`.** decision-23 puts all task work on that branch until a
release is cut, because `develop` has neither the backlog nor `verify.sh`. Dependabot defaults to the
repository's default branch, so it needs `target-branch` set explicitly — **and `ci.yml`'s triggers
must include `feature/agentic-dev`, which today they do not.** Without that second half, Dependabot's
PRs would open against a branch CI does not test, which is worse than no automation.

**2. Ignore the versions that are pinned on purpose, and cite why.** Several pins here are recorded
decisions, and a weekly PR against each is noise that trains everyone to ignore the bot:

| Pin | Reason | Remove the ignore when |
|---|---|---|
| Kotlin, KSP | no KSP release for Kotlin 2.4 | KSP ships for 2.4 |
| Ktorfit ≥ 2.7 | needs kotlin-stdlib 2.4.0 | Kotlin moves (cu-217) |
| Compose BOM > 2026.06.x | needs compileSdk 37 (decision-22) | cu-218 lands |
| `lifecycle-*` > 2.10.0 | needs compileSdk 37 **and** AGP 9.1 | cu-218 lands |
| Room 3.0 | breaking major, alpha, no consumer (cu-210) | cu-182 names a target |
| `hamcrest` 1.3 | 2.2 resolves the wrong version for Espresso (cu-54) | never, probably |

Each ignore carries the decision or task id in a comment, so it is auditable and gets removed when
the gate clears rather than outliving its reason.

## Why this is cheap here

CI already runs `./verify.sh` on pull requests, so **every Dependabot PR gets the full eight-stage
gate**: ktlint, the unit suite, the coverage ratchet, lint and the release compile. That is a far
stronger signal than a bump PR usually gets, and it means most updates can be judged from the PR
alone.

## Acceptance Criteria

- [ ] `.github/dependabot.yml` covers the `gradle` and `github-actions` ecosystems, **weekly**
- [ ] `target-branch: feature/agentic-dev`, and `feature/agentic-dev` added to `ci.yml`'s `push` and
      `pull_request` triggers
- [ ] Every pin in the table above is ignored with its reason and its unblocking condition in a
      comment
- [ ] Related updates are **grouped** — Kotlin with KSP, each AndroidX family together — so a
      toolchain move is one reviewable PR rather than ten
- [ ] Verified by observation: at least one Dependabot PR has opened against the right branch and
      been checked by CI. A config that has never produced a PR is not known to work
- [ ] `./verify.sh` green

## Notes

Pairs with cu-221 (Gradle Dependency Analysis): Dependabot reports what is **newer**, the analysis
plugin reports what is **unused**. cu-167 and cu-192 were both hand-audits of the second kind, so
between them the two tools automate work that has already been done manually twice.

Closing status **In Review** — the criterion about an observed PR needs a human to confirm.

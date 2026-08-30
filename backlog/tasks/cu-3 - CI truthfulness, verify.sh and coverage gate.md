---
id: cu-3
title: CI truthfulness, verify.sh and coverage gate
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, agentic]
dependencies: []
priority: high
milestone: m-0
---

## Description

Resolve the green-no-op: DebugAndroidTest tasks are force-disabled (app/build.gradle.kts:139) while ci.yml runs a full emulator matrix. Re-enable via Gradle Managed Devices (headless: ./gradlew pixel2api34DebugAndroidTest) or delete the emulator job and document instrumented tests as dead. Add verify.sh (fail-fast: ktlint, unit, assemble, lint; --quick flag) as the portable gate of record per D12 rule 6 — forge-level required checks are convenience, never source of truth. JaCoCo report as CI artifact, ratchet +2%/PR on touched files. See COMMERCIAL_VIABILITY_REPORT (docs/research/) §9.

## Implementation Notes

### The finding that shaped the task

The instrumented tests were not merely disabled — they were **rotted**. Removing the `enabled = false`
guard and running `compileDebugAndroidTestKotlin` fails with ~15 unresolved references:
`OnboardingActivityTest.kt` targets an `OnboardingActivity` and string resources (`plex_login_title`,
`username`, `password`, `login`) that ceased to exist when onboarding was refactored into Fragments
(`LoginFragment`/`ChooseServerFragment`/`ChooseUserFragment`/`ChooseLibraryFragment`) in `9e89270`.
The disable in `c5cfd46` was a build-unblocking measure that was never revisited.

So "re-enable via Gradle Managed Devices" was not a config change but a test-rewrite project. Took the
task's second option — delete the emulator job, document instrumented tests as dead — and split the
rewrite into **cu-54** (drafts). Fabricating instrumented coverage to close a checkbox would have
violated the principle this task exists to enforce.

### What changed

- **`verify.sh`** (new) — the gate of record. Fail-fast, five stages ordered cheapest-first:
  ktlintCheck → testDebugUnitTest → jacocoTestReport+ratchet → assembleDebug → lintDebug.
  Flags: `--quick` (agent inner loop: style+tests+coverage), `--format` (ktlintFormat first),
  `--no-coverage`. No forge-specific constructs; identical on a laptop and in CI.
- **`coverage-ratchet.sh`** + **`coverage-baseline.txt`** (new) — parses JaCoCo instruction coverage,
  fails on regression (0.05% tolerance for codegen jitter), auto-ratchets the baseline up on a rise.
  Baseline is a committed plain-text file so every movement is reviewable in a diff.
  No Codecov or any third-party coverage SaaS (D12 rule 7).
- **`app/build.gradle.kts`** — `jacoco` plugin, `enableUnitTestCoverage = true` on debug, and a
  `jacocoTestReport` task excluding generated code (databinding, Dagger factories/injectors, Room
  `_Impl`) which would otherwise dominate and render the number meaningless. The `DebugAndroidTest`
  disable block now carries a comment explaining *why*, pointing at cu-54.
- **`.github/workflows/ci.yml`** — three jobs (lint/build/test) plus the fake emulator matrix collapse
  into one `verify` job that runs `./verify.sh`. Uploads APK, test results, coverage HTML+XML; prints
  the baseline to the job summary. 180 lines removed.
- **`CLAUDE.md`** — verify-loop section now documents `verify.sh` and the ratchet; the instrumented-test
  note upgraded from "disabled" to "quarantined, do not claim coverage", pointing at cu-54.

### Verification performed

- `./verify.sh` green from a clean tree — all 5 stages (exit 0).
- **Gate proven to bite**: deliberately broke `TrackListStateManagerTest` → `verify.sh` exited 1 with
  `16 tests completed, 1 failed`. Test restored via `git checkout`. This is the direct proof of
  acceptance criterion 1 — CI can no longer pass while tests fail.
- Ratchet regression path: faked baseline 5.00% vs actual 0.99% → exit 1 with a clear message.
- Ratchet raise path: baseline 0.50% → auto-ratcheted to 0.99%, exit 0.
- Ratchet hardening (found in self-review): a missing `coverage-baseline.txt` originally **seeded
  silently and passed** — a green-no-op of exactly the kind this task removes. Now exits 1 when `CI` is
  set, and warns to commit the file locally. A malformed baseline exits 1 with a clear message instead
  of a Python traceback. Both paths tested.
- ktlint caught a style violation in my own build-file edit; fixed via `ktlintFormat`.

### Baseline and a deliberate deviation

Coverage baseline is **0.99% instruction coverage** (485/48,839) — the honest current number, and
precisely why cu-44 exists.

The task text asked for a "+2%/PR on touched files" ratchet. With two unit-test files, a mandated
per-PR increase would block unrelated PRs without improving anything. Implemented a **no-regression
floor with a visible number** instead; the +2% mandate becomes meaningful once cu-44 backfills a real
baseline, and should be revisited then. Flagged to the owner and accepted.

### Follow-ups

- **cu-54** (drafts) — rebuild the instrumented suite on Gradle Managed Devices, hermetic via cu-16.
- Revisit the per-PR coverage-increase mandate after cu-44.
- `develop` is 7 commits behind `feature/agentic-dev` and lacks the entire `backlog/` tree; worth
  deciding whether it should fast-forward before more task branches accumulate.

## Acceptance Criteria

- [x] CI can never pass while running zero tests
- [x] verify.sh exists and is referenced by CI (thin wrapper)
- [x] Coverage visible per PR
- [x] CI config portable to any forge

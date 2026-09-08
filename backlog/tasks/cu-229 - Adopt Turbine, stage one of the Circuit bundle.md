---
id: cu-229
title: "Adopt Turbine, stage one of the Circuit bundle"
status: To Do
assignee: []
created_date: '2026-09-08'
labels:
  - R3
  - testing
  - architecture
milestone: m-3
dependencies: []
priority: medium
---

## Why this is first

[[decision-26]] adopts Circuit, Molecule and Turbine as one bundle, staged so that a stall leaves
nothing half-migrated. Turbine goes first because it is **purely additive**: it changes no
architecture, touches no navigation, and is useful on its own. It also proves the dependency is
acceptable — licence, [[decision-19]] — at the smallest possible stake.

**Latest is `app.cash.turbine:turbine:1.2.1`** (checked 2026-09-08).

## What it does and does not replace

`util/FlowTestExt.kt` — `keepCollected`, `settledValue`, `settledValues` — is used by **4 test
suites** and was written for this codebase's specific traps. **It does not retire wholesale**, and
assuming it does is the main way this task could go wrong:

- **Turbine replaces stream assertions**: "these events arrived, in this order, and then nothing
  else". That is what `awaitItem` / `expectNoEvents` are for, and it is what the current helpers
  express awkwardly.
- **`settledValue` solves a different problem** — `StateFlow` conflation, where the question is
  "what value did this settle on", not "what sequence arrived". Turbine has no better answer for
  that, and rewriting those assertions in Turbine would make them longer and less clear.

So the deliverable is **Turbine used where the assertion is genuinely stream-shaped**, not a sweep.

## The two traps that must survive

Both are already in `FlowTestExt.kt`'s KDoc and cost real debugging time. They are properties of the
coroutine test machinery, not of the helpers, so **Turbine does not fix either** and both still apply
inside a `turbineScope`:

- **`advanceUntilIdle()` does not resume a `backgroundScope` collector of a `SharedFlow`.** Seven
  downloader tests once failed with zero requests reaching the engine — which reads like a broken
  downloader and was a broken harness. `yield()` works.
- **A collector on an endless flow inside `runBlocking` never completes**, so `runBlocking` never
  returns and the suite hangs until the collector is cancelled.

## Acceptance Criteria

- [ ] `app.cash.turbine:turbine` declared as a **test** dependency, version pinned in the catalogue
- [ ] At least one existing suite converted where the assertion is genuinely about a **stream** of
      events, and the conversion makes it shorter or clearer — with the before and after in the task
- [ ] **A test that was previously awkward or impossible is written**, not just conversions —
      the same standard cu-218 was held to for `FakeFileSystem`
- [ ] `FlowTestExt`'s KDoc updated to say **which helper to reach for when**: Turbine for sequences,
      `settledValue` for conflated `StateFlow` values. A reader must not have to guess
- [ ] The two traps above verified to still apply under Turbine, and the KDoc corrected if either
      turns out not to
- [ ] No production dependency added — test-only, confirmed in `buildHealth`'s output
- [ ] `./verify.sh` green
- [ ] Licence checked and compatible with GPLv3 (Apache 2.0 expected), and the licences page
      regenerates to include it (cu-216)

## Notes

**Do not convert all 4 suites for the sake of it.** A conversion that makes an assertion longer is
evidence the helper was the right tool, and recording that is a useful result — the same way cu-220's
measurement was useful even though its recommendation was overturned.

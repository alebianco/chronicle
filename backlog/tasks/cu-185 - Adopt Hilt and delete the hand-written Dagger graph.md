---
id: cu-185
title: Adopt Hilt and delete the hand-written Dagger graph
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - maintainability
milestone: m-2
dependencies:
  - cu-181
priority: medium
---

## Description

Hilt was assessed on 2026-09-06 and **declined**, on one argument: the real blocker was
`setSupportActionBar`, which Hilt does not touch, so the migration would buy tidiness while 9,000
Fragment instructions stayed unreachable.

**That argument has expired.** cu-180 removed every `setSupportActionBar` call and cu-178 removed
all twelve host casts; four scenario suites now run on the JVM and coverage went 40.47% → 50.92%.
The objection is gone, and what the assessment already conceded was a real gain is what remains.

## What it deletes

| | today |
|---|---:|
| `ViewModelProvider.Factory` inner classes | **360 lines** across 15 ViewModels |
| Hand-written components + modules | **985 lines** across 7 files |
| Field-injection calls in fragments | 12 sites (now `injectFromHost` / `injectFromAppGraph`) |

`@TestInstallIn` / `@BindValue` also replace the `testActivityComponent` / `testAppComponent` seams
cu-178 had to invent — **including the ordering trap between them**, which exists only because the
seams are hand-rolled. See cu-178's notes: written the same way as each other, one of them made a
whole scenario suite vacuous.

## Two hard gates

1. **KSP support must be verified against Dagger 2.57.2 first.** This project is deliberately
   KAPT-free (cu-8/cu-58) and reintroducing KAPT is a real regression — cu-8 measured the KSP
   incremental cost and accepted it deliberately. **If Hilt requires KAPT, stop and report; do not
   proceed.**
2. **Sequenced after Compose**, not before. cu-181's POC is done and decision-22 is **Accepted**,
   so the gate is open in principle — but the reasoning below now points at cu-187/cu-188 rather
   than the POC: migrate screens first, then the DI framework, or some of it moves twice. Hilt's ViewModel story and Compose's
   `hiltViewModel()` are designed together, and cu-181 may change how many of those 15 ViewModels
   survive in their present shape. DI first means migrating some of it twice — the same reasoning
   that puts Navigation Component after Compose.

## Acceptance Criteria

- [ ] KSP-with-Hilt verified against Dagger 2.57.2 **before any migration work**; result recorded
- [ ] `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel` replace the hand-written graph
- [ ] `ChronicleWorkerFactory` becomes `@HiltWorker` + `HiltWorkerFactory`, with the three workers
      still built (cu-179 + the cu-178 follow-up) and `ChronicleWorkerFactoryTest` still meaningful
- [ ] The `testActivityComponent` / `testAppComponent` seams removed in favour of `@BindValue`,
      and `ComponentHostTest` retired **only** once the scenario suites pass without it
- [ ] All four scenario suites still green, and still **sabotage-verified** — a Hilt migration that
      makes them vacuous again is the exact failure this task must not reproduce
- [ ] `ServiceLocatorUsageTest` still passes, or its exemptions revised with reasoning
- [ ] No coverage regression; `./verify.sh` green
- [ ] If cu-186 has run, its suites are green **and still sabotage-verified** afterwards

## Notes

Closing status is **Done**, not In Review, if nothing user-visible changes — this is a pure
refactor whose proof is automated. If any screen's behaviour shifts, it becomes In Review.

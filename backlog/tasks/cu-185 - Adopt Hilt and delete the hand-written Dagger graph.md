---
id: cu-185
title: Adopt Hilt and delete the hand-written Dagger graph
status: Done
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
ordinal: 66000
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

- [x] KSP-with-Hilt verified against Dagger 2.57.2 **before any migration work**; result recorded
- [x] `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel` replace the hand-written graph
- [x] `ChronicleWorkerFactory` becomes `@HiltWorker` + `HiltWorkerFactory`, with the three workers
      still built and `ChronicleWorkerFactoryTest` still meaningful
- [x] The `testActivityComponent` / `testAppComponent` seams removed in favour of `@BindValue`,
      and `ComponentHostTest` retired **only** once the scenario suites pass without it
- [x] All four scenario suites still green, and still **sabotage-verified**
- [x] `ServiceLocatorUsageTest` still passes, its exemptions revised with reasoning
- [x] No coverage regression; `./verify.sh` green
- [ ] cu-186 has not run, so there is nothing of its to re-verify

## Gate 1 result

**KSP works.** Hilt runs through KSP against Dagger 2.57.2 with no `kotlin-kapt` plugin present —
the processor identifies itself as `[ksp] [Hilt]`. The hard stop did not trigger and KAPT was not
reintroduced.

## Implementation Notes

See the commit body for the full account. The parts worth carrying forward:

**Four runtime crashes, none of which any test caught.** All the same root cause: the old graph was
built by hand inside `onCreate`, so `super.onCreate()` came *last* — and Hilt injects members
*inside* super. `ChronicleApplication`, `MainActivity`, `MediaPlayerService`'s session token, and
`CurrentlyPlayingFragment`'s host interface each broke on it. A DI migration that compiles and
passes 1,632 tests can still fail before the first frame; only launching the app finds it.

**Regex sweeps over 13 Fragments were the wrong tool** and cost more than they saved: they ate
closing braces in two files (restored from HEAD and converted by hand), removed a *non*-DI line
from an `onAttach`, and missed four Fragments and two ViewModels with multi-line class headers. The
audit meant to catch that missed them too, because `grep ": Fragment()"` does not match a header
split across lines.

**JaCoCo was reading pre-transform bytecode.** Hilt rewrites `@AndroidEntryPoint` classes via ASM;
the report pointed at `tmp/kotlin-classes/debug`, so JaCoCo could not match execution data and
**silently discarded** those classes' coverage. It presented as a 2.24% regression. This is the
third time in this milestone a coverage number has been wrong for a mechanical reason rather than a
real one — see cu-204.

**Three ViewModels held mutable factory fields** (`inputAudiobook`, `kind`/`value`, `collectionId`)
that the Fragment set before `create`. Those never survived process death:
`CollectionDetailsViewModel`'s `collectionId!!` would throw on a restored screen. They are
`SavedStateHandle` reads now, which is a real fix rather than a port.

## Follow-ups

- **cu-206** (Navigation Compose) is now unblocked: `hiltViewModel()` is available, and a
  composable no longer needs a Fragment to reach the graph.
- `androidx.hilt:hilt-navigation-compose` is already declared for that.

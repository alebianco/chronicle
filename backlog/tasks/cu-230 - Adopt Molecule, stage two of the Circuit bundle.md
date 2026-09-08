---
id: cu-230
title: "Adopt Molecule, stage two of the Circuit bundle"
status: To Do
assignee: []
created_date: '2026-09-08'
labels:
  - R3
  - architecture
milestone: m-3
dependencies:
  - cu-229
priority: medium
---

## Why this is second

[[decision-26]]'s staging. Molecule is **additive like Turbine**: it turns a `@Composable` into a
`StateFlow`, inside an existing ViewModel, with **no navigation change**. It can be adopted on one
ViewModel and left there if it does not earn its place — which is why it precedes Circuit, the only
stage that is hard to reverse.

**Latest is `app.cash.molecule:molecule-runtime:2.2.0`** (checked 2026-09-08).

## What it is competing with

`util/FlowCombinators.kt` — `combineDistinct` and friends, **8 declarations across 65 call sites**.
That is the incumbent, and it works. cu-194's test for Molecule was specific and still stands:

> a ViewModel whose derived state is painful enough to want `@Composable` derivation

So this task must **find that ViewModel first** and convert it, rather than converting the easiest
one. If no ViewModel meets the bar, saying so is the correct result — and per [[decision-26]] the
bundle is still adopted, because Circuit is the part the owner asked for.

## The trap this must not walk into

Molecule runs a real Compose recomposition loop on a `RecompositionMode`. Two consequences that will
not show up in a unit test that only asserts final values:

- **`RecompositionMode.Immediate` versus `ContextClock` changes when the frame runs**, and the wrong
  one either spins or never emits. Pick deliberately and record why.
- **A `@Composable` that reads a `StateFlow` via `collectAsState` inside Molecule is a
  recomposition-per-emission**, which is the cost the `stateIn` sharing rules exist to bound here.
  Measure it on the ViewModel that drives playback before assuming it is free — playback main-thread
  cost is already 37.7% layout/draw, and the profiling rule applies: profile, do not read.

## Acceptance Criteria

- [ ] `app.cash.molecule:molecule-runtime` declared, version pinned in the catalogue
- [ ] **The ViewModel with the most painful derived state is identified by looking**, not assumed —
      name it and say what makes it painful, then convert that one
- [ ] `RecompositionMode` chosen deliberately, with the reason recorded
- [ ] The converted ViewModel's tests pass **unchanged where possible** — a conversion that requires
      rewriting the assertions has changed behaviour, not just derivation
- [ ] **Recomposition cost measured** on the converted ViewModel if it is on the playback path, per
      the profile-first rule. A measurement, not an expectation
- [ ] `combineDistinct` and its 65 call sites: state plainly which stay and which move. **They do not
      all have to move**, and a mixed codebase is an acceptable outcome if recorded
- [ ] Device-verified: the converted screen behaves identically, both orientations
- [ ] `./verify.sh` green
- [ ] Licence checked (Apache 2.0 expected) and the licences page regenerated

## Notes

**If Molecule does not earn its place on the hardest ViewModel, it does not earn it anywhere.**
Recording that outcome and moving to cu-231 is a success, not a failure — Circuit is the part of the
bundle the veto was actually about, and [[decision-26]] keeps them bundled for coherence, not because
each must be used everywhere.

---
id: cu-220
title: "Circuit, Molecule and Turbine as one decision, or not at all"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - architecture
  - ui
milestone: m-3
dependencies: 
  - cu-210
  - cu-214
priority: low
---

## Description

The owner's read is right: **Circuit is Redux/Elm-shaped** — a screen is `state + events`, the UI is a
pure function of state, events flow one way — and **Molecule is closer to Vue's computed properties**,
turning a `@Composable` into a `StateFlow` so derived state is written declaratively. **Turbine is the
natural fit once those land**, because the assertions become about event and state *streams* rather
than settled values.

They are a coherent bundle, and cu-194 treats them as three separate questions. They should be one:
adopting Circuit without Molecule leaves state derivation as-is, and adopting either without Turbine
leaves the tests awkward. So this task decides all three together, and the owner has said another
migration is acceptable if it genuinely reduces boilerplate.

## What the current pattern already gives

Worth stating plainly, because the honest comparison is not "Circuit versus nothing":

- Convention 2 already requires `*Screen` (pure, state in) + `*Destination` (wires a ViewModel),
  which is most of Circuit's discipline;
- `StateFlow` + `collectWhileStarted` approximates its state model;
- `combineDistinct` and the `stateIn` sharing rules cover what Molecule would derive;
- `util/FlowTestExt.kt` — `keepCollected`, `settledValue`, `settledValues` — was built for the exact
  flow-testing traps this codebase hits, which is Turbine's territory.

So the boilerplate Circuit removes is **the `*Destination` layer**, and that is the measurement this
task needs: count it before deciding.

## The blocker

**Circuit brings its own navigation.** decision-22 chose Navigation Compose, cu-188 finished
migrating every screen onto it, and cu-202/cu-203 closed out the last of it. Adopting Circuit means
migrating navigation a **third** time. That is the whole reason this is sequenced last and marked
low.

## What would justify it

cu-194's own rule for §1 is the right test: *"Adoption needs a concrete defect it fixes."* Candidates
that would count:

- a state bug the current pattern cannot prevent structurally;
- `*Destination` boilerplate measured as a real maintenance cost across the thirteen destinations;
- a second UI target (cu-182's Wear case) where Circuit's presenter model shares more than
  Navigation Compose does.

Absent one of those, **the recorded answer should be no** — and cu-194 says explicitly that a recorded
"no" is a successful result.

## Two flow-testing traps worth capturing either way

Found the hard way during the Ktor work, and they belong in `FlowTestExt.kt`'s KDoc **whatever this
task decides** — they are the strongest concrete evidence for Turbine, and useful without it:

- **`advanceUntilIdle()` does not resume a `backgroundScope` collector of a `SharedFlow`.** Seven
  downloader tests failed with zero requests reaching the engine, which reads like a broken
  downloader and was a broken harness. `yield()` works.
- **A collector on an endless flow inside `runBlocking` never completes**, so `runBlocking` never
  returns and the suite hangs until the collector is cancelled.

## Acceptance Criteria

- [ ] The `*Destination` boilerplate is **measured**, not estimated — lines across all thirteen, and
      how much of it Circuit would actually remove
- [ ] A single adopt/decline covering **all three** libraries, with reasoning
- [ ] If adopting: the navigation question is answered first, since Circuit's router and Navigation
      Compose cannot both own routing — and that answer amends decision-22
- [ ] If declining: what would change the answer is written down, and the two flow traps above are
      added to `FlowTestExt.kt` regardless
- [ ] No partial adoption. One of the three alone is the outcome this task exists to prevent
- [ ] `./verify.sh` green

## Notes

Closing status **In Review** either way: adopting amends decision-22, and declining is a product-shaped
judgement about how much churn is worth it.

**Sequenced last on purpose.** Everything in cu-210 before it either reduces risk or is reversible;
this one is a re-architecture of every screen. If the programme stalls earlier, nothing is
half-migrated.

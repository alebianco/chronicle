---
id: cu-220
title: 'Circuit, Molecule and Turbine as one decision, or not at all'
status: Done
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-210
  - cu-214
priority: low
ordinal: 102000
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

- [x] The `*Destination` boilerplate is **measured**, not estimated — lines across all thirteen, and
      how much of it Circuit would actually remove
- [x] A single adopt/decline covering **all three** libraries, with reasoning
- [x] If adopting: the navigation question is answered first, since Circuit's router and Navigation
      Compose cannot both own routing — and that answer amends decision-22
      *(n/a — declined, so decision-22 stands unamended)*
- [x] If declining: what would change the answer is written down, and the two flow traps above are
      added to `FlowTestExt.kt` regardless
- [x] No partial adoption. One of the three alone is the outcome this task exists to prevent
- [x] `./verify.sh` green

## Notes

Closing status **In Review** either way: adopting amends decision-22, and declining is a product-shaped
judgement about how much churn is worth it.

**Sequenced last on purpose.** Everything in cu-210 before it either reduces risk or is reversible;
this one is a re-architecture of every screen. If the programme stalls earlier, nothing is
half-migrated.

## Outcome (2026-09-07)

**Declined, all three, recorded as [[decision-25]].**

The measurement is what decided it. The `*Destination` layer is **1,421 lines across thirteen
files**, but only **94 of them (6.6%) are the mechanical wiring Circuit removes** — 80
`collectAsStateWithLifecycle` calls and 14 `hiltViewModel()` defaults. The remaining 1,327 are
imports (302), KDoc and comments (227), blank lines (92), `viewModel::` method references (46),
`ToastEffect`s (22) and the Scaffold / `PullToRefreshBox` / `SearchOverlay` layout — all of which
**relocate into a Circuit `Ui` rather than disappearing**. `BrowseDestination` and
`SeriesIndexTesterDestination` are each one collect, one `ChronicleScaffold` and one `*Screen` call;
there is no ceremony left in them to delete.

A `viewModel::showFacet` becoming `{ eventSink(BrowseEvent.ShowFacet(it)) }` is a sealed event class
plus a `when` plus the dispatch — Circuit trades a method reference for a named event type. Real
benefits (exhaustiveness, testable event streams), but not fewer lines.

Against that, adopting Circuit means migrating navigation a **third** time (375 lines of
`ChronicleNavHost.kt` + `Destination.kt`, settled by cu-188/cu-202/cu-203). cu-194's rule —
*"adoption needs a concrete defect it fixes"* — is not met: decision-22's bug table is four
View-system layout bugs that Compose itself already fixed, none of them a state-management defect
Circuit's shape would have prevented. cu-182's Wear case is not built and not scheduled.

Turbine falls with the bundle: without Circuit's event streams the assertions here stay
settled-value-shaped, which `FlowTestExt` already serves by 7 suites.

**What would reopen it** is written into decision-25: a structural state bug, the wiring share
growing past roughly a third (today 6.6%), a genuine second UI target, or Navigation Compose
becoming a maintenance problem in its own right.

**Both flow traps are now in `FlowTestExt.kt`'s KDoc**, as the acceptance criteria required
regardless of the decision — with the distinction that made them confusing: `advanceUntilIdle` is
correct for the `StateFlow` helpers in that file and useless for a `SharedFlow` collector, which is
why the same call works in one place and silently produces vacuous assertions in the other.

## Overturned by the owner (2026-09-08)

**The owner vetoed the decline and directed adoption of all three.** Recorded as [[decision-26]],
which supersedes [[decision-25]].

This task stays **Done**: its job was to measure the boilerplate and force a single decision covering
all three libraries rather than three separate deferrals, and it did both. The decision it recommended
was overturned, which is the system working — a recommendation made from the record, surfaced with its
reasoning, and reversed by the person whose call it is.

**What the measurement got right, and what it weighed wrong.** The 6.6% figure is accurate and
reproducible. What it could not capture is that the case for Circuit was never a line count: an
exhaustive `when` over a sealed event type makes an unhandled interaction a *compile error*, where
the current `viewModel::method` pattern makes it a method nobody calls. This codebase has shipped
that failure twice — `download_all` (implemented, tested, unreachable, cu-208) and
`MockPlexMode.disable()` (dead code called from nowhere). Neither is a *state* bug, which is the
evidence this task demanded, so the test as written could not see the defect class the codebase
actually has.

The staged adoption is cu-229 (Turbine), cu-230 (Molecule) and cu-231 (Circuit), sequenced so a stall
leaves nothing half-migrated.

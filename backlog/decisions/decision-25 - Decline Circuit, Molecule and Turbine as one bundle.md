---
id: decision-25
title: "Decline Circuit, Molecule and Turbine as one bundle"
status: accepted
created_date: '2026-09-07'
---

## Context

cu-194 surveyed Circuit and Molecule as two separate library questions and deferred both. cu-220
argued they are not separable: Circuit is Redux/Elm-shaped (a screen is `state + events`, the UI is a
pure function of state), Molecule is closer to Vue's computed properties (a `@Composable` becomes a
`StateFlow`, so derived state is declarative), and Turbine is the natural test library once either
lands, because assertions become about event and state *streams* rather than settled values.

Adopting Circuit without Molecule leaves state derivation exactly as it is; adopting either without
Turbine leaves the tests awkward. So this is one decision covering all three.

The honest comparison is **not "Circuit versus nothing"**. The current pattern already supplies most
of what Circuit enforces:

- convention 2 already requires `*Screen` (pure, state in) + `*Destination` (wires a ViewModel),
  which is most of Circuit's discipline;
- `StateFlow` + `collectAsStateWithLifecycle` approximates its state model;
- `combineDistinct` (`util/FlowCombinators.kt`, 107 lines, 65 call sites) plus the `stateIn`
  sharing-policy rules cover what Molecule would derive;
- `util/FlowTestExt.kt` — `keepCollected`, `settledValue`, `settledValues` — was built for the exact
  flow-testing traps this codebase hits, and is used by 7 suites.

So the boilerplate Circuit removes is **the `*Destination` layer**, and cu-220's rule was to measure
that before deciding rather than estimate it.

## The measurement

Thirteen `*Destination` files (`navigation/Destination.kt` is the route list, not a destination):

| file | lines |
|---|---|
| `PlayerDestination.kt` | 192 |
| `LibraryDestination.kt` | 173 |
| `ChooseUserDestination.kt` | 171 |
| `DetailsDestination.kt` | 169 |
| `SettingsDestination.kt` | 132 |
| `LoginDestination.kt` | 128 |
| `HomeDestination.kt` | 105 |
| `CollectionsDestination.kt` | 83 |
| `PickerDestinations.kt` | 77 |
| `CollectionDetailsDestination.kt` | 54 |
| `FacetBooksDestination.kt` | 49 |
| `BrowseDestination.kt` | 44 |
| `SeriesIndexTesterDestination.kt` | 44 |
| **total** | **1,421** |

**1,421 lines is the gross number, and it is the wrong one.** Broken down:

| category | lines | what Circuit does to it |
|---|---|---|
| `collectAsStateWithLifecycle` calls | 80 | **removed** — folded into the presenter |
| `viewModel: X = hiltViewModel()` defaults | 14 | **removed** — Circuit injects the presenter |
| imports | 302 | mostly **relocated**; the layout imports follow the layout |
| KDoc and comments | 227 | **relocated** — the reasoning is about the screen, not the wiring |
| blank lines | 92 | — |
| `viewModel::` method references | 46 | **relocated** — become event-sink dispatches, roughly 1:1 |
| `ToastEffect` / `ToastResEffect` | 22 | **relocated** — still a Compose effect |
| Scaffold / `PullToRefreshBox` / `Box` / `SearchOverlay` / `SearchTopBar` / arguments / navigation lambdas | remainder | **relocated** into the Circuit `Ui` |

**Circuit removes 94 of 1,421 lines — 6.6%.** Everything else moves house. The two smallest
destinations, `BrowseDestination` and `SeriesIndexTesterDestination`, are each a single
`collectAsStateWithLifecycle`, a `ChronicleScaffold` and a `*Screen` call: there is no ceremony left
in them to delete. The largest, `PlayerDestination` at 192 lines, is large because the player screen
is genuinely complicated, and that complexity is layout and effects, which Circuit keeps.

A `viewModel::showFacet` becoming `{ eventSink(BrowseEvent.ShowFacet(it)) }` is not less code. It is
a sealed event class *plus* a `when` in the presenter *plus* the dispatch — Circuit trades a method
reference for a named event type. That trade buys exhaustiveness and testable event streams; it does
not buy fewer lines, and cu-220's measurement was specifically asked for so this would not be
guessed.

## The blocker

**Circuit brings its own navigation.** decision-22 chose Navigation Compose, cu-188 migrated every
screen onto it, and cu-202/cu-203 closed out the last of it. Circuit's router and Navigation Compose
cannot both own routing, so adopting Circuit means migrating navigation a **third** time — 375 lines
of `ChronicleNavHost.kt` + `Destination.kt`, plus every destination's argument plumbing, all of it
recently settled and working.

## Decision

**Decline all three. No partial adoption.**

cu-194's standing rule for §1 is *"Adoption needs a concrete defect it fixes."* None of the three
candidate justifications holds today:

1. **A state bug the current pattern cannot prevent structurally** — there is no such bug on record.
   decision-22's bug table is four View-system layout bugs (`isShown` on a collapsed sheet, peek
   height, a `values-land` GONE view, Kotlin-driven visibility), all of which Compose itself already
   fixed. None is a state-management defect Circuit's shape would have prevented.
2. **`*Destination` boilerplate as a real maintenance cost** — measured above at 94 removable lines
   across thirteen files. That is not a maintenance cost worth a third navigation migration.
3. **A second UI target where the presenter model shares more** — cu-182's Wear case is not built and
   is not scheduled. If it is ever taken up, this is the criterion to re-measure against.

Turbine falls with them: it is a good library, but its value here is proportional to how
stream-shaped the assertions are, and without Circuit's event streams the codebase's assertions stay
settled-value-shaped, which `FlowTestExt` already serves. Adopting Turbine alone would add a
dependency that duplicates working local helpers.

Recording a "no" is the successful outcome cu-194 anticipated. This closes the loop so the question
is not re-litigated from silence.

## What would change this answer

Any **one** of these reopens the bundle as a whole — never one library on its own:

- a **state bug** that the `*Screen` + `*Destination` + `StateFlow` pattern cannot prevent
  structurally, where Circuit's unidirectional event flow would have made it unrepresentable;
- the `*Destination` layer growing past roughly **a third** of its lines being mechanical wiring
  (today: 6.6%) — most plausibly if destination count grows well beyond thirteen with the same shape;
- **a second UI target** (cu-182's Wear case, or Android Auto growing a Compose surface) where a
  shared presenter would genuinely serve two UIs and Navigation Compose would not;
- Navigation Compose becoming a maintenance problem in its own right, which would make "migrate
  navigation a third time" a cost being paid anyway rather than a cost this adoption creates.

Molecule specifically would be reopened by a ViewModel whose derived state is painful enough to want
`@Composable` derivation — the test cu-194 set. `combineDistinct`'s 65 call sites are currently the
evidence that the flow-based approach scales here.

## Consequences

- decision-22 stands unamended. Navigation Compose keeps routing.
- Convention 2 (`*Screen` + `*Destination`) stands as the screen pattern.
- The two flow-testing traps cu-220 surfaced are documented in `util/FlowTestExt.kt` regardless of
  this decision — they are real, they cost debugging time, and they are useful without Turbine.

---
id: decision-26
title: "Adopt Circuit, Molecule and Turbine"
status: accepted
created_date: '2026-09-08'
supersedes: decision-25
---

## Context

[[decision-25]] declined all three as one bundle on 2026-09-07, on a measurement: Circuit removes 94
of the 1,421 `*Destination` lines — **6.6%** — and relocates the rest into a Circuit `Ui`. Against
that, adopting Circuit means migrating navigation a **third** time, after cu-188/cu-202/cu-203
settled Navigation Compose.

**The owner overturned that decline on 2026-09-08** and directed adoption of all three.

## Decision

**Adopt Circuit, Molecule and Turbine.** As with the decline, this is one decision covering all
three — [[decision-25]]'s reasoning that they are not separable is unchanged and still correct:
Circuit without Molecule leaves state derivation as it is, and either without Turbine leaves the
tests shaped for settled values rather than streams.

## Why the measurement did not settle it

The 6.6% figure is accurate and is **not** the reason to adopt. It measured lines, and lines were
the wrong yardstick. What survives is the benefit [[decision-25]] itself recorded and then
discounted: a sealed event hierarchy with an exhaustive `when` makes an unhandled screen interaction
a **compile error**, where the current `viewModel::method` pattern makes it a method nobody calls.

This codebase has shipped that failure twice:

- **`download_all`** — fully implemented, tested, with a confirmation prompt, and **unreachable**,
  because the menu item was `android:visible="false"` and nothing set it visible (cu-208).
- **`MockPlexMode.disable()`** — dead code called from nowhere, which is why leaving mock mode needs
  `plex-session.sh` rather than the function written for it.

Neither is a *state* bug, which is the evidence [[decision-25]] demanded. That test was applied too
narrowly: the unreachable-handler class is real here, and an event sink makes it unrepresentable.

## The costs, unchanged and accepted

- **Navigation migrates a third time.** 375 lines of `ChronicleNavHost.kt` + `Destination.kt`,
  settled twice already. Circuit's router and Navigation Compose cannot both own routing. This is
  the largest risk in the adoption and the reason it is sequenced and staged rather than swept.
- **1,327 of the 1,421 `*Destination` lines relocate rather than disappear.** Anyone expecting a
  smaller codebase will not get one. The gain is in shape, not size.
- **Three new dependencies**, against `combineDistinct` (65 call sites) and `FlowTestExt` (7 suites)
  which currently do this work. Those helpers do not all retire — see the plan.

## How it is staged

Sequenced so that a stall leaves nothing half-migrated, which is the same rule cu-210 applied:

1. **Turbine first, on its own.** It is additive, has no architectural blast radius, and is useful
   before either other library lands. It also proves the dependency is acceptable (licence,
   [[decision-19]]) at the smallest possible stake.
2. **Molecule second.** Also additive — it turns a `@Composable` into a `StateFlow` inside an
   existing ViewModel and needs no navigation change. `combineDistinct`'s 65 call sites are the
   measure of whether it earns its place; they do not all have to move.
3. **Circuit last, and the navigation question answered before the first screen moves.** This is the
   step that amends [[decision-22]]'s choice of Navigation Compose, and it must be answered as a
   decision rather than discovered mid-migration.

**Screen-by-screen, each independently shippable and device-verified**, exactly as the Compose
migration ran (decision-22). Not a big-bang rewrite of thirteen destinations.

## Consequences

- **[[decision-22]] will need amending at stage 3**, when Circuit's router replaces Navigation
  Compose. It is not amended yet, because stages 1 and 2 do not touch routing.
- **Convention 2 (`*Screen` + `*Destination`) changes at stage 3** — `*Destination` becomes a Circuit
  `Ui` + presenter. `*Screen` staying pure is unaffected and remains the rule.
- The two flow-testing traps from cu-220 stay documented in `util/FlowTestExt.kt` regardless; they
  are real and independent of Turbine.
- **`FlowTestExt` does not retire wholesale.** Turbine replaces stream assertions, not the
  `settledValue` helpers that exist for `StateFlow` conflation — those solve a different problem.

## Outcome of stage 2 — recorded 2026-09-08, ruling unchanged

**Molecule was implemented, measured, and declined** (cu-230). This note records what was found; it
does not amend the decision, which is the owner's.

`launchMolecule` runs the initial composition **synchronously in the constructor**, and Compose's
`Recomposer` reports composition errors through `android.util.Log.e`. In a plain JVM unit test that
call throws, so a ViewModel using Molecule cannot be constructed there at all — reproduced with a
minimal `launchMolecule(ContextClock) { 42 }` and no app code. **177 of this project's 244 test
classes are plain JVM**, and moving them to Robolectric runs against cu-213, which found Pitest
already straining under the 62 Robolectric classes the Compose migration added.

Nothing else was the obstacle: on the tablet the converted player ticked correctly, and main-thread
cost over 12 s was 835 jiffies before against 699–900 after — no measurable regression.

This does not weaken the bundle's rationale. The decision's own framing is that the three are
adopted together for coherence, *"not because each must be used everywhere"*, and cu-230's Notes
anticipated exactly this: **"if Molecule does not earn its place on the hardest ViewModel, it does
not earn it anywhere"**. Circuit is the part the veto was about, and it is unaffected — its
presenters are `@Composable` but they run inside Circuit's own composition, not a `launchMolecule`
in a constructor.

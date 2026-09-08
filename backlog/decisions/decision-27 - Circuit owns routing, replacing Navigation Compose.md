---
id: decision-27
title: "Circuit owns routing, replacing Navigation Compose"
date: '2026-09-09'
status: accepted
supersedes: []
amends:
  - decision-22
---

## Decision

**Circuit owns both routing and presentation.** Its `Navigator` and backstack replace
`ChronicleNavHost`, and `*Destination` composables become Circuit `Ui` + presenter pairs.

This is option 1 of the three cu-231 put to the owner, chosen explicitly over option 2 (Circuit
presenters inside Navigation Compose routes) and option 3 (two routers during migration).

**This amends [[decision-22]]**, which chose Navigation Compose and whose migration finished in
cu-188, cu-202 and cu-203. That work is not wasted — it removed the Fragments and made every screen
a pure composable, which is the precondition for this. But it does mean navigation is migrated a
**third** time, and that cost is accepted here rather than discovered halfway.

## Why option 1 rather than option 2

Option 2 — presenters without the router — was the recommendation on the table, and it delivers the
property the veto was actually about: a sealed event hierarchy with an exhaustive `when`, so an
unhandled interaction is a compile error. It leaves `Destination.kt` and `ChronicleNavHost.kt`
untouched.

The owner chose option 1 anyway. The reasoning that stands behind it:

- **Two navigation models is the state to avoid.** Option 2 leaves Navigation Compose owning routes
  while Circuit owns everything inside them — which is coherent, but it is also permanent. Every
  future screen has to decide which half it belongs to, and that decision has no obvious answer.
  [[decision-22]] took care to avoid exactly this shape with two UI toolkits.
- **The end state is simpler**, not just different: one model for a screen's route, state, events
  and UI, instead of a seam between two.
- **The cost is bounded and known.** 388 lines: `Destination.kt` (212) and `ChronicleNavHost.kt`
  (176). It is a large single change, not an open-ended one.

## What this does not decide

- **`Destination.kt`'s framework-free route list is a goal, not a casualty.** Circuit screens are
  `Parcelable` keys, which is a different shape from the current sealed route objects. Whether the
  route list survives in some form is a design question for cu-231, not settled here.
- **Convention 2 changes**: `*Destination` becomes `Ui` + presenter. `*Screen` staying pure is
  unaffected and remains the rule. `reference/00-constitution.md` is updated as part of cu-231, not
  in advance of it.

## Constraints this inherits

- **Screen by screen, each independently shippable and device-verified** — the same rule the Compose
  migration ran under. Not a big-bang conversion of thirteen destinations.
- **Start small**: `BrowseDestination` (44 lines) or `SeriesIndexTesterDestination` (44 lines).
  [[decision-25]] measured that neither has mechanical wiring left to remove, so they are the honest
  test of what Circuit costs on a simple screen. **Not** `PlayerDestination` (192 lines, playback
  path).
- **Never stop mid-migration with two routers live.** If the work stalls, it stops on a screen
  boundary with `verify.sh` green.

## What made this possible, and what it rests on

Circuit was blocked twice until 2026-09-08, and both blockers are now cleared:

- **AGP 9.1.0+**, which Circuit 0.38.0 requires via lifecycle 2.11.0. cu-214 landed **AGP 9.4.0**.
- **Presenters were thought untestable off-device.** They are not: with
  `unitTests.returnDefaultValues = true` and Molecule's documented
  `moleculeFlow(RecompositionMode.Immediate).test { }` recipe, a Circuit presenter with a sealed
  event hierarchy runs in a **plain JVM test**. Verified at 0.38.0.

Two standing risks, recorded so they are not rediscovered:

- **Circuit is `0.x`** and its API has moved between minors. This is the least settled dependency in
  the tree, and a minor bump is a real event rather than routine.
- **AGP 9 is held by four compatibility workarounds** ([[cu-234]]), which exist for the pitest
  plugin and are removed in AGP 10. Circuit's requirement is AGP 9.1.0+, so it is not itself at
  risk — but the toolchain underneath it is not settled either.

---
id: cu-230
title: "Adopt Molecule, stage two of the Circuit bundle"
status: In Review
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

## CORRECTION, 2026-09-08 — the decline below was **wrong**

The owner asked whether the README's testing note had been read. It had not, and it answers the
blocker directly. Two mistakes, both mine:

1. **`unitTests.returnDefaultValues = true`** is documented in Molecule's README precisely for JVM
   unit tests in an Android module. It makes `android.util.Log.e` return a default instead of
   throwing, which is the entire crash this task was declined over.
2. **The README's recipe is `moleculeFlow(RecompositionMode.Immediate).test { }`, not
   `launchMolecule`.** `moleculeFlow` is a *cold* flow — no composition in a constructor, nothing to
   throw at construction. Every probe below used `launchMolecule`, the hot variant, which is why the
   constructor was implicated.

Measured after the correction, plain JVM, no Robolectric:

| probe | result |
|---|---|
| `moleculeFlow(Immediate)` counter, README shape | **passes** |
| `moleculeFlow(Immediate)` with a `StateFlow` source, asserting a change propagates | **passes** |
| the same, without `returnDefaultValues` | fails, as before |

So the finding recorded below — *"`RecompositionMode.Immediate` does not propagate a source
change"* — is also wrong. It does. The earlier probe read `.value` off a hot `launchMolecule`
instead of collecting a cold `moleculeFlow`, and never gave the loop a reason to emit.

**What stands from the work below:** the target selection (`CurrentlyPlayingViewModel`, chosen by
measurement), the `Triple<..., Pair<...>>` as a real pain point, and the device profiling showing no
regression — 835 jiffies baseline against 699–900 with Molecule.

**What does not stand:** the decline itself, and the claim that 177 plain-JVM test classes force
Robolectric. They do not; one line of `testOptions` handles it.

This task should be **re-opened and redone** against the README's recipe. It is left `In Review`
rather than silently reverted so the wrong reasoning stays visible next to the correction — the
programme's own standard, per cu-229's note about recording a test that was wrong first.

---

## Outcome: **declined**, and the declaration removed

Molecule was implemented end to end, ran correctly on the tablet, and is **not adopted**. The
blocker is not the conversion — it is that a ViewModel using Molecule **cannot be constructed in a
plain JVM unit test**, and 177 of this project's 244 test classes are plain JVM.

Per this task's own Notes, recording that outcome and moving to cu-231 is a success rather than a
failure. The declaration is removed rather than left in place: an unused production dependency is
the dead weight cu-228 was about.

## What was measured

**The ViewModel was chosen by looking**, as required. `CurrentlyPlayingViewModel` leads on both
counts — 17 `combineDistinct` call sites against `AudiobookDetailsViewModel`'s 16, and 2 nested
combinators against 1. Its worst derived state is the honest case cu-194 described:

```kotlin
private val transportUtilityState:
  StateFlow<Triple<TransportState, UtilityState, Pair<Boolean, Boolean>>> = ...
// unpacked as: transportUtility.third.first, transportUtility.third.second
```

That intermediate exists *only* because "four sources is the combinator's ceiling" — its own comment
says so — and two booleans are reached through a pair inside a triple with nothing but position to
say which is which. Molecule removes it: a `@Composable` reads eight sources by name and the
`Triple` disappears rather than being renamed. The conversion was written, compiles, and is clean.

**On device it works.** With the player open during playback the readout ticked correctly —
`0:41 left in chapter` / 34% → `0:17` / 38% over 8 s, slider advancing, chapter list live.

**Recomposition cost, per the profile-first rule.** Main-thread jiffies over 12 s with the player
open during playback:

| build | jiffies / 12 s |
|---|---|
| baseline | 835 |
| Molecule | 900, 812, 761, 699 |

The baseline sits **inside** Molecule's run-to-run spread, so there is no measurable regression on a
path where layout and draw already dominate at 37.7%. Performance was not the reason to decline.

## Why it is declined

**`launchMolecule` runs `composeInitial` synchronously in the constructor**, and Compose's
`Recomposer` reports any composition error through `android.util.Log.e`. In a plain JVM unit test
that call is not mocked and throws, so the ViewModel cannot be built at all. Every one of
`CurrentlyPlayingViewModelTest`'s 13 tests failed with `Method myLooper in android.os.Looper not
mocked`, then `Method e in android.util.Log not mocked`.

**It is not the conversion, and not the frame clock.** Reduced to a minimal probe —
`launchMolecule(ContextClock) { 42 }` with a `BroadcastFrameClock`, no app code involved — a plain
JVM test throws the same way. Robolectric fixes it, but that is the cost: **177 of 244 test classes
are plain JVM**, and moving any of them is the opposite direction from cu-213, which found Pitest
already struggling with the 62 Robolectric classes the Compose migration added.

Two further measurements taken on the way, both worth keeping:

- **`RecompositionMode.Immediate` does not propagate a source change** — measured under Robolectric,
  a `MutableStateFlow` write left the output at its initial value after `advanceUntilIdle` *and*
  `yield`. So `Immediate` is not the escape hatch it looks like; `ContextClock` is the only viable
  mode here, and it needs a real choreographer.
- **`ContextClock` does not tick under Robolectric either.** It works on a device and only there,
  so a converted ViewModel's derived state is untestable off-device by any route.

The plumbing cost was also real and is worth recording against any future attempt:
`CurrentlyPlayingViewModel` has no `DispatcherProvider`, so injecting a frame clock the convention-4
way meant a constructor change rippling to Hilt, two test factories and `KtorDownloaderTest`'s own
`DispatcherProvider` implementation — for a stage this programme calls *additive*.

## What would change the answer

- Molecule offering a mode that composes lazily rather than in the constructor, or not routing
  composition errors through `android.util.Log`.
- This project moving to Robolectric by default — which cu-213 argues against.
- A ViewModel whose tests are already Robolectric and whose derived state is painful. None of the
  three worst offenders qualifies today.

## `combineDistinct` stays, all 65 call sites

Unchanged, and now with a reason rather than by default: the alternative is not testable in this
project's default test environment. The `Triple<..., Pair<...>>` in `CurrentlyPlayingViewModel`
remains the honest example of what that costs.

## Acceptance Criteria

- [x] `app.cash.molecule:molecule-runtime` declared, version pinned — **and then removed**, since it
      is not adopted. Verified 2.2.0 latest against Maven Central; Apache 2.0; wants Compose runtime
      1.9.1 against the BOM's 1.11.4, so no constraint conflict
- [x] **The ViewModel with the most painful derived state identified by looking** —
      `CurrentlyPlayingViewModel`, on measured counts, and converted
- [x] `RecompositionMode` chosen deliberately and the reason recorded — `ContextClock`, with
      `Immediate` ruled out by measurement rather than by argument
- [x] The converted ViewModel's tests pass unchanged — **they do not, and that is the finding.** All
      13 failed at construction
- [x] **Recomposition cost measured** on the playback path — no regression, table above
- [x] `combineDistinct` and its 65 call sites: all stay, with the reason recorded
- [x] Device-verified — the conversion ticks correctly on the tablet; not re-verified in both
      orientations, since it is not being kept
- [x] `./verify.sh` green, 10 stages, with the conversion reverted
- [x] Licence checked (Apache 2.0). Licences page not regenerated — nothing was added to ship

## Notes

**If Molecule does not earn its place on the hardest ViewModel, it does not earn it anywhere.**
Recording that outcome and moving to cu-231 is a success, not a failure — Circuit is the part of the
bundle the veto was actually about, and [[decision-26]] keeps them bundled for coherence, not because
each must be used everywhere.

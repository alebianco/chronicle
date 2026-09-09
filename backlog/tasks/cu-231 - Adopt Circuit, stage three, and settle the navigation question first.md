---
id: cu-231
title: "Adopt Circuit, stage three, and settle the navigation question first"
status: In Review
assignee: []
created_date: '2026-09-08'
labels:
  - R3
  - architecture
milestone: m-3
dependencies:
  - cu-230
priority: medium
---

## Why this is last, and what makes it different

[[decision-26]] adopts Circuit on the owner's veto of [[decision-25]]. Stages one and two (cu-229,
cu-230) are additive and reversible. **This one is not.** It replaces the `*Destination` layer across
thirteen screens and it takes routing away from Navigation Compose.

**Latest is `com.slack.circuit:circuit-foundation:0.38.0`** (checked 2026-09-08). Note the `0.x`
version: this is the least settled dependency in the bundle, and its API has moved between minors.

## CORRECTION, 2026-09-08 — blocker 2 was wrong, and blocker 1 is a project choice

The owner asked whether the ecosystem is really this broken. It is not. Both blockers below were
overstated; re-measured after reading Molecule's README:

### Blocker 2 is **withdrawn**. A Circuit presenter *is* JVM-testable.

With `unitTests.returnDefaultValues = true` — documented in Molecule's README for exactly this —
and the README's `moleculeFlow(RecompositionMode.Immediate).test { }` recipe, a full Circuit
presenter runs in a **plain JVM** test with no Robolectric:

```kotlin
moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
  val first = awaitItem()
  assertEquals(0, first.count)
  first.eventSink(SpikeEvent.Increment)   // sealed event, exhaustive `when`
  assertEquals(1, awaitItem().count)
}
```

Narrowed by isolation, each measured: a plain composable returning a state object with a lambda
passes; a `CircuitUiState` passes; a real `Presenter.present()` passes; a `Presenter` with an
`eventSink` that mutates state passes.

**Only Circuit's own `Presenter.test { }` helper fails**, with a 3 s timeout. Its signature takes no
`RecompositionMode` (verified by `javap`: `test-i8z2VEo(Presenter, Duration, String,
SnapshotMutationPolicy, Function2, Continuation)`), so it picks one internally and that choice does
not suit a JVM test. Using `moleculeFlow` directly is the workaround, and it is the recipe Molecule
documents anyway.

The original spike also had a second bug: `awaitItem().eventSink(...)` consumed an extra item, so
even a working path would have timed out. Both errors were mine.

### Blocker 1 stands as a fact but is **a project decision, not an ecosystem limit**

Circuit 0.38.0 does require AGP 9.1.0+, via lifecycle 2.11.0. But **AGP 9.4.0 is a stable release**
— 9.4.0 is out, with 9.5.0 in alpha. Nothing about the ecosystem prevents this.

What prevents it here is cu-214, which measured AGP 9 and **skipped** it: five incompatibilities,
three silent. That is this project's own call and can be revisited. So the honest framing is:

- **Adopt Circuit at 0.38.0** → re-open the AGP 9 decision (cu-214), on a now-stable 9.4.0.
- **Adopt Circuit at 0.31.0** → stay on AGP 8.13.2, seven minors back on a `0.x` API.

**AGP 9 was re-measured on 2026-09-08 and is cheaper than cu-214 concluded** — see that ticket. Two
of its five blockers fall: `kotlin-parcelize` is dead code (now [[cu-233]]), and the Ktorfit/Kotlin
metadata problem, the one flagged as inverting the whole rationale, does not reproduce — Ktorfit
2.7.5 compiles on AGP 9.4.0 with `kotlin = 2.3.21` and its KSP codegen runs. What remains is three
mechanical build-file edits plus one new open question (detekt has no `:app:detektDebug` under AGP
9). The chain reaches a successful `compileDebugKotlin`, further than either earlier attempt.

Both are live options. Neither is blocked by anything outside this repository.

### What still needs the owner

The navigation question below is unchanged and still unanswered in code — full routing was chosen,
and nothing has been migrated. What has changed is that the *test story is no longer a reason not
to*, and the AGP question is the real decision.

---

## Superseded — the original spike report, 2026-09-08

*Kept for the reasoning trail; blocker 2 is withdrawn above and blocker 1 is reframed.*

## BLOCKED — the test-story spike, 2026-09-08

**Not started.** A spike was run first, on the owner's instruction, to answer a question cu-230
raised before anything irreversible happened. It found **two independent blockers**, and per the
owner's standing instruction the work stopped rather than routing around them.

Nothing is migrated. No screen was touched, no dependency remains declared, `verify.sh` green.

### Blocker 1 — Circuit is behind the AGP 9.1.0 gate

`checkDebugAarMetadata` fails with Circuit declared:

```
Dependency 'androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0'
  requires Android Gradle plugin 9.1.0 or higher.
  This build currently uses Android Gradle plugin 8.13.2.
```

This is the **same gate cu-214 measured and skipped** — AGP 9 was found to have five
incompatibilities, three of them silent, against one gain. Confirmed to be Circuit's doing rather
than assumed: the gate passes with the dependency removed and fails with it added.

Older versions do not escape it. Circuit 0.38.0, 0.37.1, 0.36.1 all pull lifecycle **2.11.0**;
0.35.0 pulls `2.11.0-rc01`, 0.34.0 `2.11.0-beta01`. The last version clear of it is **0.31.0**
(lifecycle 2.9.5), which does pass the gate — seven minors behind, on a `0.x` library whose API
moves between minors.

### Blocker 2 — a Circuit presenter is not testable off-device, for the same reason Molecule was not

Established on 0.31.0, which is past blocker 1, so this is a separate finding rather than a
consequence.

A minimal presenter — `Presenter<SpikeState>` with a sealed event hierarchy and an exhaustive
`when`, no app code — driven by Circuit's own `circuit-test` `.test {}` harness:

- **Plain JVM: fails.** `RuntimeException: Method e in android.util.Log not mocked`, and the stack
  names `app.cash.molecule.MoleculeKt.launchMolecule`. **`circuit-test` uses Molecule internally**,
  so Circuit inherits cu-230's blocker exactly.
- **Under Robolectric: fails differently.** The `Log` crash goes, and then nothing is produced — a
  3 s Turbine timeout. Same result measured for Molecule's `ContextClock` under Robolectric: the
  frame clock needs a real choreographer.

So Robolectric is **not** the escape hatch it was assumed to be when cu-230 was declined. There is
currently no environment in this project where a Circuit presenter's state can be asserted except a
device.

### What this means for the decision

decision-26 adopts Circuit on the owner's veto, and that ruling stands — this records a cost that
was not known when it was made, not a disagreement with it. Three things follow:

1. **Circuit is gated behind AGP 9**, which is cu-214's declined step. Adopting Circuit means
   re-opening that, or pinning Circuit at 0.31.0.
2. **The exhaustive-`when` property the veto was about is available today without any of this** —
   a sealed event hierarchy plus `when` is Kotlin, not Circuit. What Circuit adds is the presenter
   runtime and, at option 1, the router.
3. **Option 2 in the section below (presenters without the router) does not dodge either blocker.**
   Both are properties of the presenter runtime, not of routing.

## The navigation question must be answered before the first screen moves

Circuit's router and Navigation Compose **cannot both own routing**. The surface at stake, measured:

| file | lines |
|---|---|
| `navigation/Destination.kt` | 212 |
| `navigation/compose/ChronicleNavHost.kt` | 176 |
| **total** | **388** |

Navigation Compose was chosen by [[decision-22]] and finished by cu-188, cu-202 and cu-203. Adopting
Circuit's router means migrating navigation a **third** time. [[decision-26]] accepts that cost
explicitly; this task must not rediscover it halfway through.

**Three shapes, and one must be chosen and recorded before any screen is converted:**

1. **Circuit owns routing** — its `Navigator` and backstack replace `ChronicleNavHost`. The full
   third migration. Cleanest end state, largest single change.
2. **Navigation Compose keeps routing, Circuit supplies presenters and UIs inside each route.**
   Circuit's `Presenter` + `Ui` used without its navigation. Much smaller change, and keeps
   `Destination.kt` as the framework-free route list it already is.
3. **Circuit owns routing for new screens only**, with both alive during the migration. Rejected on
   sight unless argued: two routers is the state [[decision-22]] took care to avoid with two UI
   toolkits, and it would be worse here because routing is global.

**Option 2 deserves a serious look before option 1 is assumed.** The veto was about Circuit's
*presenter and event model* — exhaustive sealed events — and that is available without touching the
router. If option 2 delivers the correctness property the owner asked for at a fraction of the risk,
that is the honest recommendation, and it needs the owner's agreement either way because it partly
re-opens what [[decision-26]] appeared to settle.

## What "done" looks like per screen

Screen-by-screen, each independently shippable and device-verified — the same rule the Compose
migration ran under. **Not a big-bang conversion of thirteen destinations.**

Start with `BrowseDestination` (44 lines) or `SeriesIndexTesterDestination` (44 lines): they are the
smallest, and [[decision-25]] measured that they have *no* mechanical wiring left to remove, so they
are the honest test of what Circuit actually costs on a simple screen. Do **not** start with
`PlayerDestination` (192 lines) — it is the largest and sits on the playback path.

## Acceptance Criteria

- [x] **The navigation question is answered and recorded as a decision before the first screen
      moves** — [[decision-27]] records option 1, amending [[decision-22]]
- [x] Circuit declared, version pinned at **0.38.0**; the `0.x` instability noted and then *met*
      twice — `Screen` is `CircuitSaveable` rather than `Parcelable` since 0.31.x, and `BackStack`
      is `NavStack`
- [x] **`SeriesIndexTesterDestination` converted first**, shipped in its own commit (`98ec3629`)
      before any other screen moved
- [x] Screen events are a **sealed hierarchy with an exhaustive `when`** on all thirteen
- [x] Convention 2 **and convention 9** in `reference/00-constitution.md` updated, plus the
      `android-ui` skill, CLAUDE.md's map, and the **six** reference docs the deletion invalidated
      (`02-architecture`, `03-project-structure`, `04-key-components`, `06-adding-features`,
      `07-visual-guide`, `08-glossary`). `06-adding-features` mattered most: it told an agent to
      write a `*Destination` against a `Destination.kt` route, which no longer compiles
- [x] Device-verified in **both orientations** on the tablet: home, library, details, series facet,
      browse, narrator facet, settings, series tester, back navigation twice, rotation. Zero crashes
- [x] `./verify.sh` green, 10 stages
- [x] Coverage **rose**, 56.55% → 57.73%
- [x] `./test_release_build.sh` **exit 0** — 9,201 classes in dex, 24 `@Serializable` models checked
- [x] Licence checked: all **9** Circuit modules are **Apache-2.0** in the generated catalogue,
      release and debug

## Notes

**This is the stall point to respect.** If the programme stops after cu-229 and cu-230, the project
is strictly better off with no half-migrated state — Turbine and Molecule are additive. If it stops
*inside* this task, it must stop on a screen boundary with `verify.sh` green, never mid-migration
with two routers live.

**`FlowTestExt` and `combineDistinct` do not all retire even here.** cu-229 and cu-230 record which
parts stay; this task should not quietly widen into deleting them.

## Closing notes, 2026-09-09

**Landed as one commit after the first screen**, which is a departure from this ticket's own rule of
"screen by screen, each independently shippable". The rule contradicted the harder one two lines
below it — *never stop mid-migration with two routers live* — and the owner resolved it: swap the
router in one commit. So `SeriesIndexTesterCircuit` shipped alone as the honest small test, and the
remaining twelve plus the router moved together.

### The three things that only a device could find

Every one of these passed `verify.sh` and would have shipped:

1. **`CircuitCompositionLocals` must wrap `rememberSaveableNavStack`.** Building the `Circuit` inside
   the `navHost` slot means the nav stack has no saver: *"No CircuitSaver provided"*, crash before
   the first frame.
2. **`hiltViewModel()` does not work inside a Circuit record.** The record-scoped owner is not
   `HasDefaultViewModelProviderFactory`, so the call falls through to the **default** factory and
   throws `Cannot create an instance of class HomeViewModel`. `recordViewModel()` keeps the record's
   store and borrows the Activity's factory.
3. **A `Screen` still has to be saveable.** The marker interface is not enough — Compose's
   `SaveableStateRegistry` rejects a type it cannot bundle. `ScreenKeySaver` serialises the keys as
   JSON, which keeps `navigation/Screens.kt` framework-free where `@Parcelize` would not have.

Three crashes on three successive launches, each one invisible to 1,815 green tests, because nothing
off-device composes the real shell. That is rule 5 earning its place again.

A **fourth** arrived after those, and is the worst of the set because it does not look like a crash:

4. **Never call `pop()` to discover whether the back stack can pop.** Circuit's `pop()` at the root
   delegates to `onBackPressedDispatcher.onBackPressed()`, which re-enters the app's own back
   handler, which calls `pop()` again — infinite recursion, dying as a `StackOverflowError`
   thousands of frames deep. On screen it is indistinguishable from the app exiting normally: the
   process is simply gone. It also made the "back from a non-Home tab returns to Home" branch
   **unreachable**, because control never returned from `pop()`. Reading `peekBackStack().size`
   first has no reentrancy, restores that branch, and was device-verified.

The self-review agent found this independently and diagnosed the same cause from the bytecode. It
also caught a second one no test or screenshot would show: **`resetRoot` defaults every state flag
to `false`**, so a tab switch dropped the record and cleared its ViewModel where the call it
replaced carried `saveState`/`restoreState`. `Navigator.StateOptions.SaveAndRestore` for the tab
switch; the plain default stays right for a login step, where coming back is exactly what
`inclusive = true` ruled out.

### What the migration removed

- **The route-string encoding problem, entirely.** `encodeArg`/`decodeArg` and the 208-line
  `DestinationTest` that guarded them are gone: a screen key carries `"Whitfield, June/Nunn"` as a
  string, so there is no percent-encoding to get wrong and no pattern to silently fail to match.
- **The enum round trip.** `FacetKind` travelled as a `name` matched back with a
  `?: FacetKind.Author` fallback, so a mismatch showed the wrong facet rather than failing. It is
  the enum now.
- **A real latent bug.** `AudiobookDetailsViewModel` read `ARG_AUDIOBOOK_TITLE` from a
  `SavedStateHandle` key **nothing ever wrote** — only two tests seeded it — so every production
  download notification was titled with the empty string. It reads the loaded book's title now.

### What it cost

Three ViewModels moved to Hilt assisted injection, which made their tests *simpler* (a string
argument instead of a hand-built `SavedStateHandle`). Two AGP-9-era deprecation warnings are now
visible on `hiltViewModel` — `androidx.hilt.lifecycle.viewmodel.compose` is the new home and the
dependency is not declared; that is its own change, not one to bury here.

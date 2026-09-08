---
id: cu-231
title: "Adopt Circuit, stage three, and settle the navigation question first"
status: To Do
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

- [ ] **The navigation question is answered and recorded as a decision before the first screen
      moves** — options 1/2/3 above, with the reasoning. This amends [[decision-22]] if option 1
- [ ] Circuit declared, version pinned; the `0.x` API instability noted with what would break
- [ ] **One small screen converted first** (`BrowseDestination` or `SeriesIndexTesterDestination`),
      shipped and device-verified on its own before a second is started
- [ ] Screen events are a **sealed hierarchy with an exhaustive `when`** — this is the property the
      adoption is *for*; a conversion that keeps method references has not delivered it
- [ ] Convention 2 in `reference/00-constitution.md` updated: `*Destination` becomes `Ui` +
      presenter, `*Screen` staying pure is unchanged
- [ ] Each converted screen device-verified in **both orientations**, per rule 5
- [ ] `./verify.sh` green after **each** screen, not only at the end
- [ ] Coverage does not regress — the `*Destination` files currently carry real coverage
- [ ] `./test_release_build.sh` passes: Circuit uses reflection-adjacent code generation and this is
      R8-sensitive
- [ ] Licence checked (Apache 2.0 expected) and the licences page regenerated

## Notes

**This is the stall point to respect.** If the programme stops after cu-229 and cu-230, the project
is strictly better off with no half-migrated state — Turbine and Molecule are additive. If it stops
*inside* this task, it must stop on a screen boundary with `verify.sh` green, never mid-migration
with two routers live.

**`FlowTestExt` and `combineDistinct` do not all retire even here.** cu-229 and cu-230 record which
parts stay; this task should not quietly widen into deleting them.

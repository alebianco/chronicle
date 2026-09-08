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

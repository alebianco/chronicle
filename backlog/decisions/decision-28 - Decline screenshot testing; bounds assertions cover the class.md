---
id: decision-28
title: "Decline screenshot testing; bounds assertions cover the class"
date: '2026-09-09'
status: accepted
supersedes: []
amends: []
---

## Decision

**No screenshot-testing tool is adopted.** The layout-defect class that motivated the question is
covered by **bounds assertions** in the existing Robolectric suite — `getUnclippedBoundsInRoot`
against a composed screen — which need no golden images, no new plugin and no new failure mode.

**Rule 5 is unchanged.** Device verification in both orientations still stands, and nothing here
weakens it.

This closes cu-232, the one cu-194 question the cu-210 programme left open.

## What the six bugs actually show

cu-232 required assessing this against the six known bugs of the class rather than in general. That
assessment is what decided it:

| Bug | What it was | Would a golden diff catch it? |
|---|---|---|
| cu-19 | Chapter-aware progress text | Yes — but so does a pure formatter test |
| cu-68 | ViewBinding visibility flashes, ~13 XML views | **Moot** — DataBinding/ViewBinding era |
| cu-141 | `values-land/integers.xml` set the progress line GONE | **Moot** — no `values-land`, no layouts |
| cu-142 | A `ConstraintLayout` measured to zero height in landscape | **Moot** — no ConstraintLayout |
| cu-226 | Account notice painted over every `TopAppBar` | Yes — **and a bounds assertion already does** |
| cu-191 | `00:00/9:26:42 0%` where a formatted duration belonged | Yes — **and a formatter test already does** |

**Three of the six cannot recur.** They are XML/View-era defects — a `values-land` integer, a
`ConstraintLayout` measuring zero, ViewBinding's lost DataBinding evaluation. Verified 2026-09-09:
`app/src/main/res/` contains **no `layout*` directory and no `-land` qualifier at all**. The
mechanism is gone, not merely unused.

That matters because decision-22's argument for rule 5 rests on those four landscape bugs, and cu-232
inherited the argument wholesale. Two of the four are now unreachable by construction.

**The remaining three are already guarded**, and by cheaper tests than a golden image:

- **cu-226** by `AccountNoticePlacementTest`, which asserts on `getUnclippedBoundsInRoot`. Its own
  KDoc states the mechanism precisely: *"The semantics tree was correct throughout… Only the pixels
  were wrong."* A bounds assertion is the narrowest thing that fails on that, and it is already
  written.
- **cu-191** and **cu-19** by `DetailsProgressTextTest` and the player's formatter tests. Both were
  *wrong text from a pure function* — a golden image would catch them incidentally while also
  catching every unrelated pixel change.

## Why bounds assertions win here, specifically

cu-232 required arguing screenshot testing against `AccountNoticePlacementTest` rather than against
having no test. Doing so:

- **It is more precise.** It fails on the one property that was wrong — position — and on nothing
  else. A golden image fails on a font metric change, a theme tweak, a Compose version bump.
- **It states its intent in the diff.** `assertTrue(noticeTop > toolbarBottom)` says what must hold.
  A `.png` says only "this is what it looked like when someone last approved it".
- **It has no regeneration ritual.** The reason golden gates get disabled is that regenerating is
  expensive and reviewing a binary diff is not really possible. There is no such cost here.
- **It costs nothing new.** Robolectric is already in the tree for 62 test classes.

## What was measured, not assumed

Two things were checked rather than taken from documentation:

- **Roborazzi 1.74.0 (2026-09-08) does apply under this project's `android.newDsl=false`.** That
  combination was the one thing research could not confirm — Roborazzi is written against the *new*
  variant API while that flag restores the old one. Probed by declaring the plugin: all six tasks
  (`record`/`compare`/`verify` × debug/release) register and `BUILD SUCCESSFUL`. So the decline is
  **not** for lack of a working option, which is the honest framing.
- **Paparazzi is ruled out independently**, on three counts: Google publishes a named known issue
  that it is not Gradle 9 compatible; its AGP 9 tracking issue is **open** with a commenter blocked
  on exactly this; and alpha04 made **Java 21 mandatory**, which at the time of this decision this
  project could not meet.

  *Note, 2026-09-09:* the project's build JVM has since moved to **Java 21**, so that third count no
  longer applies. The decline stands on the two that remain — the named Gradle 9 incompatibility and
  the open AGP 9 issue — and is unchanged; only the stale premise is corrected here.

AGP's own `com.android.compose.screenshot` (0.0.1-alpha16) is AGP 9-aware and JDK 17+, but renders
only `@Preview` composables statically — no state driving, no interaction. It cannot reach a
presenter-driven screen, which after decision-27 is every screen.

## What would change this answer

A decline should say what would overturn it. Any of:

- **A layout defect that a bounds assertion cannot express** — an overlap between two views neither
  of which has a stable identity, a wrong colour, a clipped glyph. If one is found in the wild,
  reopen this with that bug as the case.
- **The bounds-assertion approach failing to scale** — if guarding a screen needs a dozen pairwise
  bounds comparisons, a golden image is the cheaper statement and this should be revisited.
- **Rule 5 becoming the bottleneck.** The argument for tooling gets much stronger if manual
  two-orientation verification is what is actually slowing delivery. It is not today.

## What this does not decide

- **Rule 5 stays exactly as it is.** A JVM render is not a device, and the landscape bugs were found
  on a real tablet. Nothing here is a reason to screenshot less by hand.
- **Bounds assertions are not mandated.** `AccountNoticePlacementTest` is the pattern to reach for
  when a defect is *positional*; it is not a new gate every screen must satisfy.

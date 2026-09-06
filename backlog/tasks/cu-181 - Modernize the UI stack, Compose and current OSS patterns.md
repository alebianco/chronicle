---
id: cu-181
title: "Modernize the UI stack: Compose and current OSS patterns"
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - maintainability
  - ui
milestone: m-2
dependencies: []
priority: high
---

## Description

The UI is **43 XML layouts / 3,808 lines** driven imperatively from **14 Fragments / ~3,100 Kotlin
lines**. That stack is in maintenance mode upstream — Google develops Compose and merely maintains
Views — and it is where this project's worst bugs come from.

**The evidence is our own backlog.** Four of the hardest-won recent fixes are all the same failure
class: state pushed imperatively into a mutable view tree, with visibility conditional on resource
qualifiers.

| task | bug | root cause |
|---|---|---|
| cu-141 | landscape player hid book progress | `isShown` on a collapsed sheet |
| cu-142 | speed popover collapsed to its title bar | bottom-sheet peek height |
| cu-19 | whole text block blank in landscape | guard anchored on a `values-land` GONE view |
| cu-68 | first-frame flashes; two views permanently invisible | Kotlin-driven visibility with no XML default |

None of these has an analogue in a declarative UI: there is no mutable view whose visibility can
disagree with its state, and no XML default to forget. cu-68 exists *only* to guard a View-system
hazard — `FirstFrameFlashTest` would become unnecessary rather than merely passing.

The second cost is testability. cu-178 and cu-180 spent a session inverting DI and toolbar
ownership purely so `FragmentScenario` could host a screen at all. Compose UI is testable without
a host, a manifest entry, or Robolectric.

## Scope: an incremental migration, not a rewrite

`ComposeView` inside an existing Fragment means screen-by-screen, each independently shippable.
**This task is the POC and the decision record, not the whole migration** — the remaining screens
should be separate tasks once the POC has produced real numbers.

Ordering note: this should precede any Fragment-based **Navigation Component** work. Navigation for
Fragments and Navigation Compose are different APIs; doing the Fragment one first means migrating
navigation twice.

## Also assess, with the POC as evidence

- **Compose + Media3** — `PlayerView` interop is the known-awkward seam, and the player is both our
  most bug-dense screen (`CurrentlyPlayingFragment`, 496 Kotlin + 385 XML lines) and the one with a
  `SurfaceView`-adjacent component. **This is the risk that decides the programme**, so the POC must
  cover it rather than defer it.
- **Circuit** (slackhq) — Compose-only, so it is a non-starter today and a genuine option once
  screens are Compose. Its "screen = state + events, UI is a pure function of state" is the model
  `StateFlow` + `collectWhileStarted` already approximates here.
- **Hilt** — already assessed and declined once (see `backlog/docs/analysis/`), but its
  `@HiltViewModel` + Compose integration changes the calculus. Re-assess *after* the POC, not before.
- **Molecule, Coil-Compose, Material3 adaptive** — evaluate only if the POC succeeds.

## Acceptance Criteria

- [x] Compose added to the build (BOM-pinned), licence checked (Apache-2.0, GPLv3-compatible)
- [x] **One** screen migrated — `CollectionsScreen`, rendered on the tablet
- [x] A Compose UI test for that screen, running on the JVM and counting toward the ratchet
- [x] **Media3 interop probed** — see below; the risk does not exist
- [x] APK size delta measured (debug); build-time delta **not** measured
- [x] An ADR recording go / no-go — `decision-22`, status **Proposed**
- [ ] Follow-up tasks per remaining screen — **deferred to the owner's go/no-go**
- [x] `./verify.sh` green

## Implementation Notes

### The programme-deciding risk does not exist

This task named Media3/`PlayerView` interop as the thing that would decide feasibility. Measured:
**`PlayerView` appears nowhere in the app, and `androidx.media3.ui` is imported by no Kotlin file.**
The player screen is TextViews, ImageViews, a Slider and a RecyclerView — an audiobook player has
no video surface. `media3-ui` is a declared dependency the code does not use.

### What was built

- `ChronicleTheme` — the XML palette for Compose, with `ChronicleThemeTest` pinning every value
  against `colors.xml` (sabotage-verified by changing one colour by a single digit).
- `CollectionsScreen` — stateless, `CollectionsContent` sealed as Loaded / Empty / OfflineEmpty so
  the screen cannot render a contradiction. 8 tests asserting on-screen content, no DI, no
  `FragmentScenario`, no mocked component. Sabotage-verified.
- `ComposePreviewActivity` — debug-only, renders the screen on hardware with no server or login.

### Two bugs the tests could not catch

Both suites green, semantics tree correct, screen visibly wrong:

1. `MaterialTheme` **defines** `colorScheme.background` but nothing paints it — that is `Surface`'s
   job. A bare `Box` let the window colour through as `#121212`, text near-invisible.
2. `GridCells.Fixed(3)` divides the *available* width: 640px cells on a 1920px tablet, one cover
   filling the screen. `GridCells.Adaptive(minSize)` is both the fix and Android's adaptive-layout
   guidance.

### Numbers

| | |
|---|---|
| coverage | 50.92% → 51.59% (baseline lowered from 51.75%, see below) |
| `features/collections/compose` | **86.1%** |
| `ui/theme` | **85.1%** |
| Fragment layer, for comparison | ~40% |
| APK (debug) | 26.9 → 27.0 MB (+0.1) |

The baseline was lowered **deliberately**: `ComposePreviewActivity` is 286 instructions of
debug-only visual scaffolding that cannot be meaningfully unit-tested. It was **not** added to
`coverageExclusions`, which is for *generated* code — it stays in the denominator rather than
hidden.

### Toolchain constraints, found by building

- **Compose BOM held at 2026.06.x** — 2026.08.00 needs compileSdk 37; we are on 36.
- **`lifecycle-*-compose` reuse the existing 2.10.0** — 2.11.0 needs compileSdk 37 *and* AGP 9.1.0.
- **`activity` 1.8.2 → 1.13.0**, forced by `activity-compose`; `onNewIntent` became non-null.
- **ktlint** told about `@Composable` naming rather than having the rule disabled.

### What is still owed before this is Accepted

- **Release APK size** — only the debug delta was measured, and debug is not R8-shrunk.
- **Build-time delta** — not measured.
- **The owner has not seen it.** Launch it with:
  `adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.debug.compose.ComposePreviewActivity`
  and `--es state empty|offline|loaded`.

### What needs the owner's eye

Whether to proceed at all — `decision-22` is **Proposed**, not Accepted. If yes, the per-screen
follow-ups get filed and cu-185 (Hilt) unblocks. The screen is not wired into production, so
nothing user-visible changed yet.

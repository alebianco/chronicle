---
id: cu-181
title: "Modernize the UI stack: Compose and current OSS patterns"
status: To Do
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

- [ ] Compose added to the build (BOM-pinned) with the licence checked for GPLv3 compatibility (D3/principle 3)
- [ ] **One** screen migrated to `ComposeView` inside its existing Fragment, shipping and working on the tablet
- [ ] A Compose UI test for that screen, running on the JVM and counting toward the ratchet
- [ ] **Media3 interop probed on the player** — either demonstrated working or the blocker recorded precisely
- [ ] Measured: APK size delta, build-time delta, and the coverage change for the migrated package
- [ ] An ADR in `backlog/decisions/` recording go / no-go with those numbers, and the ordering
      constraint vs Navigation Component
- [ ] Follow-up tasks filed per remaining screen **only if** the POC says go
- [ ] `./verify.sh` green

## Notes

Candidate POC screens, with the trade-off:

- `fragment_collections.xml` (118 lines) — smallest real list screen, most representative, lowest risk.
- `modal_bottom_sheet_speed_chooser.xml` (174 lines) — self-contained and the site of cu-142, so a
  successful migration retires a known bug class rather than only proving mechanics.

**Owner decision needed on which**, since it is a product-visible screen either way.

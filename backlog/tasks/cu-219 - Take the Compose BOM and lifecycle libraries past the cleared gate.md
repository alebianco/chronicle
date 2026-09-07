---
id: cu-219
title: "Take the Compose BOM and lifecycle libraries past the cleared gate"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - ui
  - debt
milestone: m-3
dependencies: 
  - cu-210
  - cu-218
priority: medium
---

## Description

The payoff for cu-218. With `compileSdk 37` and AGP 9 in place, the two pins decision-22 recorded can
be released:

- **Compose BOM** past the 2026.06.x line — 2026.08.00 and later pull Compose 1.12.0, whose
  `material-ripple-android` needed compileSdk 37.
- **`lifecycle-*-compose`** from 2.10.0 to 2.11.0, which wanted compileSdk 37 *and* AGP 9.1.

## The thing to get right

**A Compose BOM bump is a UI change, and this codebase has learned that the hard way.** The migration
recorded four defect classes that only a device showed, every one invisible to a green Compose suite:

- a `_white` drawable carrying a black fill needs an explicit `tint`;
- a `ComposeView` clipped by a View parent that sized it wrong;
- `Icon` flattening a two-colour drawable to a silhouette — a play button shipped as a bare circle;
- Material3's `labelLarge` not uppercasing, so `textAllCaps` section titles silently lost their
  casing, caught only by comparing against a screenshot taken *before* the change.

That last one is the pattern to repeat here: **screenshot before, screenshot after, compare.** A
Material3 minor can change type scales, ripple behaviour and default paddings without any test
noticing.

`ChronicleThemeTest` pins the palette against `colors.xml`, so a colour regression is guarded. Type
and spacing are not.

## Acceptance Criteria

- [ ] Compose BOM and `lifecycle-*` moved, with the versions recorded
- [ ] **Before-and-after screenshots** of the player, library, home, details and settings, in **both
      orientations**, compared rather than merely collected
- [ ] Ripple, type scale and section-title casing specifically checked — the recorded failure modes
- [ ] `ChronicleThemeTest` and the Compose screen suites green
- [ ] `./verify.sh` green; `./test_release_build.sh` passes
- [ ] decision-22's two "held" notes updated to say what actually shipped

## Notes

Closing status **In Review**: every criterion here is a judgement about how a screen looks, which is
the owner's to make.

Worth considering alongside cu-194's screenshot-testing candidate (Roborazzi). This task is exactly
the work a screenshot suite would automate, and doing it by hand once is a reasonable way to learn
what the goldens would need to cover.

---
id: cu-63
title: Edge-to-edge insets for SDK 36
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R2, ui]
dependencies: []
priority: high
---

## Description

Found while verifying cu-6. With `targetSdk = 36`, Android enforces edge-to-edge and **stops**
insetting app content for the system bars. The app does not consume those insets, so content now draws
underneath them.

Confirmed on screen: the Home toolbar renders under the status bar, with the "Home" title overlapping
the clock. Every screen with a top app bar or bottom navigation is affected.

This is a real visual regression shipped by the cu-6 toolchain bump, not a pre-existing issue. It was
accepted knowingly there because reverting `targetSdk` to dodge it would forfeit the whole point of the
bump — but it needs fixing before any release.

### Direction

Apply `ViewCompat.setOnApplyWindowInsetsListener` at each screen root (or once in `MainActivity` around
the fragment container plus the bottom nav), consuming `systemBars()` as padding. `activity_main.xml`
is the natural single place for the toolbar/bottom-nav pair, since cu-58 moved all view wiring into
Kotlin and the roots now carry ids.

Watch specifically:
- top app bars on Home / Library / Settings / book details
- the bottom navigation bar
- the collapsed and expanded player, which sits above the bottom nav
- landscape and the tablet two-pane case, where cutouts differ

Verify with `./capture-screens.sh` against the cu-16 mock and compare to the pre-cu-6 baseline.

Note this overlaps the R3 redesign (cu-26/27/28), which will re-lay-out these surfaces anyway. Fix it
properly here rather than deferring — a visibly broken status bar is a Trust-tier problem, not a
Delight-tier one.

## Acceptance Criteria

- [ ] No content draws under the status bar or navigation bar on any screen
- [ ] Verified on Android 15 and Android 16 emulators, portrait and landscape
- [ ] Screenshots compared against the pre-cu-6 baseline

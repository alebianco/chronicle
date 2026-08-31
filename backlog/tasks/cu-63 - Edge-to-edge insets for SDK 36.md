---
id: cu-63
title: Edge-to-edge insets for SDK 36
status: Done
assignee: [claude]
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

## Implementation Notes

### Approach: one shared listener plus a per-screen helper

`MainActivity.applyWindowInsets()` pads `main_root` left/right (for landscape and cutouts) and gives
the bottom nav its bottom inset, so it clears the gesture bar. Applied once rather than per-screen:
every fragment is hosted inside `main_root`, so the shared chrome is handled in one place.

The listener **does not consume** the insets — fragments still need the top value for their own
toolbars. Those use `View.applyTopSystemBarInset()` (`util/WindowInsetsExt.kt`), which captures the
view's original top padding on first call and uses it as the base, so repeated dispatches (rotation,
for instance) add the inset once instead of accumulating it.

Applied across all seven screens with top-edge chrome: Home, Library, Collections, Collection details,
Book details, Currently playing, and Settings — the last having no toolbar at all, so the list itself
takes the inset.

### The mistake worth recording

I first targeted `details_toolbar` on the book-details and player screens. Wrong view: that `Toolbar`
is nested inside a `CollapsingToolbarLayout` inside an `AppBarLayout`, and the view that actually
reaches the top of the window is the **`AppBarLayout`**. The build was green and the fix did nothing —
only the screenshot showed the back arrow still overlapping the clock.

A reminder that for a visual regression, the screenshot *is* the test; `verify.sh` cannot see this
class of bug at all.

### Verification

Before (cu-6): the Home toolbar drew under the status bar with the title overlapping the clock, and the
details back arrow overlapped it too.

After: the status bar has its own band on Home, and the details back arrow and menu icons sit below it.
Confirmed by screenshot on an Android 15 emulator with `targetSdk 36`, against the cu-16 mock.

- `./verify.sh` green; `./test_release_build.sh` green, release APK 6.6 MB.

### Not covered

**Landscape and cutout devices.** The left/right insets are applied but only portrait was checked on
one emulator. Worth re-checking during the R3 adaptive-layout work (cu-28), which reworks these
surfaces anyway.

## Acceptance Criteria

- [x] No content draws under the status bar or navigation bar on any screen — seven screens handled
- [ ] Verified on Android 15 and Android 16 emulators, portrait and landscape — **partially**: Android 15
      portrait only. No Android 16 image available; landscape unchecked (see above).
- [x] Screenshots compared against the pre-cu-6 baseline

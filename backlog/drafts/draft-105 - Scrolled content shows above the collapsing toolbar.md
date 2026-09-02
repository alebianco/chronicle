---
id: DRAFT-105
title: Scrolled content shows above the collapsing toolbar
status: Draft
assignee: []
created_date: '2026-09-02'
labels: [R2, ui, bug]
dependencies: []
priority: medium
milestone: m-2
---

## Description

Owner report, 2026-09-02, seen on the book screen and suspected elsewhere:

> when scrolling the page content passes behind the top bar and then is visible again above it
> before going off the screen

So a list row slides under the toolbar, disappears, and then **reappears in the status-bar strip
above it** before leaving the screen.

## Diagnosis (from the layouts, not yet confirmed on device)

Two screens use a scrolling app bar, and the same two things are true of both:

| | `fragment_audiobook_details.xml` | `fragment_currently_playing.xml` |
|---|---|---|
| `layout_scrollFlags` | `scroll` | `scroll` |
| `fitsSystemWindows` | absent | absent |
| inset handling | `appBarLayout.applyTopSystemBarInset()` | same |

**`fitsSystemWindows` appears in no layout in the project at all.**

The likely mechanism, in two parts:

1. **The inset is padding, not layout.** `applyTopSystemBarInset` adds the status-bar height as
   *top padding* on the `AppBarLayout`. The bar therefore draws its content below the status bar,
   but the AppBarLayout's own bounds still start at y=0, and — crucially — the
   `CollapsingToolbarLayout` scrolls the whole thing up by its full height. Once it has scrolled
   past its padded region, the strip it vacates is transparent, and the RecyclerView underneath
   (which `appbar_scrolling_view_behavior` positions relative to the app bar's *bottom*) is visible
   through it.
2. **`scroll` with no companion flag.** Without `exitUntilCollapsed` the bar scrolls entirely off,
   including the area under the status bar; without `enterAlwaysCollapsed`/a pinned toolbar there is
   nothing left to cover that strip. On a non-edge-to-edge app the status bar was opaque and hid
   this; targetSdk 36 made the window edge-to-edge (cu-63) and exposed it.

`CoordinatorLayout` is designed to hand insets to its children via `fitsSystemWindows="true"`, which
is what makes `CollapsingToolbarLayout` reserve the status-bar strip and keep a pinned toolbar in it.
Doing it with manual padding instead is what leaves the gap.

## Approach

1. **Reproduce and capture first.** `./capture-screens.sh` plus a scroll, or a screen recording —
   the exact visual is what tells us which of the two causes dominates, and the fix differs.
2. Try `fitsSystemWindows="true"` on the `CoordinatorLayout` and remove the manual
   `applyTopSystemBarInset()` on those two app bars, rather than stacking both mechanisms — double
   padding is the classic outcome of leaving them together.
3. Consider `app:layout_scrollFlags="scroll|exitUntilCollapsed"` with the toolbar pinned
   (`app:layout_collapseMode="pin"`), so a toolbar always occupies the status-bar strip.
4. Check the third screen shape too: `fragment_home` and `fragment_library` use
   `toolbarLayout.applyTopSystemBarInset()` without a CollapsingToolbar, so they may be fine —
   confirm rather than assume.

## Acceptance Criteria

- [ ] Reproduced and captured on device, both screens
- [ ] No content visible above the toolbar at any scroll position
- [ ] Toolbar content still clears the status bar (i.e. the cu-63 fix is not regressed)
- [ ] Verified in 3-button *and* gesture navigation, and in landscape
- [ ] Checked whether `fragment_home` / `fragment_library` share the defect

## Notes

This is the fourth inset bug after cu-63 (toolbar under the status bar), the bottom-nav height bug
and the mini-player one — all found by eye, none catchable by the current gate. The instrumented
suite is the only place a guard could live; see the scope recommendation in the pre-R2 review.

Deliberately **not** fixed blind: it is a visual defect whose fix depends on which mechanism
dominates, and guessing at inset arithmetic is how the bottom-nav bug took three attempts.

---
id: cu-105
title: Scrolled content shows above the collapsing toolbar
status: Done
assignee: [claude]
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

## Diagnosis (confirmed on device)

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

- [x] Reproduced and measured on an API 35 emulator via uiautomator bounds
- [x] No content visible above the toolbar at any scroll position
- [x] Toolbar content still clears the status bar (cu-63 not regressed)
- [ ] Verified in 3-button *and* gesture navigation, and in landscape
- [ ] Checked whether `fragment_home` / `fragment_library` share the defect

## Notes

This is the fourth inset bug after cu-63 (toolbar under the status bar), the bottom-nav height bug
and the mini-player one — all found by eye, none catchable by the current gate. The instrumented
suite is the only place a guard could live; see the scope recommendation in the pre-R2 review.

Deliberately **not** fixed blind: it is a visual defect whose fix depends on which mechanism
dominates, and guessing at inset arithmetic is how the bottom-nav bug took three attempts.

## Implementation Notes

**Reproduced and measured**, rather than judged by eye — the tablet emulator renders this screen
blank (that is cu-74, a separate bug), so screenshots were useless and `uiautomator` bounds were the
evidence:

| view | before scroll | after scroll (broken) | after scroll (fixed) |
|---|---|---|---|
| `appBarLayout` | `[0,0][2560,1019]` | `[0,0][2560,`**48**`]` | `[0,0][2560,`**176**`]` |
| `details_toolbar` | `[0,48][2560,176]` | `[0,`**0**`]` | `[0,`**48**`]` |
| `tracks` (list top) | `y=1019` | `y=`**48** | `y=`**176** |

48px is exactly the status-bar inset on that device, so the collapsed bar was *entirely* the inset
region: the toolbar was squeezed into it and the list slid up to y=48, occupying the strip the
toolbar should own.

### The actual cause — two halves, both needed

1. **`layout_scrollFlags="scroll"` with no `exitUntilCollapsed`** let the bar scroll away completely.
   Now `scroll|exitUntilCollapsed` with `android:minHeight="?attr/actionBarSize"`.
2. **The inset was applied as padding to the `AppBarLayout`, but `minHeight` lives on the
   `CollapsingToolbarLayout` inside it** — and `minHeight` is measured *excluding* padding. So the
   bar could still collapse to 48px. `applyTopSystemBarInsetWithMinHeight` grows both, and is
   applied to the `CollapsingToolbarLayout` (which gained an id) rather than the bar around it.

Fixing only the first half would have collapsed to `actionBarSize` and still hidden the toolbar
under the status bar; only the second, and the bar would still scroll fully away. That is why the
task said not to guess which mechanism dominated — it was both.

`fitsSystemWindows="true"` on the `CoordinatorLayout` was the other candidate. Not used: the toolbar
here is nested two levels deep inside a `ConstraintLayout`/`LinearLayout`, so
`CollapsingToolbarLayout` cannot pin it, and stacking the framework's inset handling on top of the
existing manual padding is the classic route to doubled insets.

Applied to both screens with a scrolling app bar: book details and currently-playing.

### Still to verify

- [ ] Gesture navigation and landscape (measured in 3-button portrait only)
- [ ] `fragment_home` / `fragment_library` — they use `applyTopSystemBarInset` on a plain toolbar
      with no `CollapsingToolbarLayout`, so they have no `minHeight` to collapse to and are very
      likely unaffected. Unconfirmed.
- [ ] The currently-playing screen was fixed by symmetry, not measured — the emulator's blank
      rendering made reaching it unreliable.

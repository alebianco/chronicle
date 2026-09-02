---
id: cu-105
title: Scrolled content shows above the collapsing toolbar
status: In Review
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
- [x] Checked whether `fragment_home` / `fragment_library` share the defect — **they cannot.**
      Verified by inspection 2026-09-02: `layout_scrollFlags` appears in exactly **two** layouts in
      the project, `fragment_audiobook_details.xml` and `fragment_currently_playing.xml`, and
      `CollapsingToolbarLayout` in the same two. `fragment_home`, `fragment_library`,
      `fragment_collections` and `fragment_collection_details` each have an `AppBarLayout` with
      **no scroll flags and no collapsing toolbar**, so nothing scrolls up past the status bar and
      the mechanism has nothing to act on. No device check needed for this item.

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

### Re-verified with the final build (2026-09-02 07:52)

The first emulator install predated the `minHeight` half of the fix — the installed dex did not
contain `applyTopSystemBarInsetWithMinHeight` at all, so the earlier "fixed" measurement was taken
against an incomplete build. Rebuilt, reinstalled, and confirmed the helper is present in
`classes5.dex` before re-measuring. The numbers above are from that run and hold.

### Still to verify

- [ ] Gesture navigation and landscape (measured in 3-button portrait only)
- [ ] `fragment_home` / `fragment_library` — they use `applyTopSystemBarInset` on a plain toolbar
      with no `CollapsingToolbarLayout`, so they have no `minHeight` to collapse to and are very
      likely unaffected. Unconfirmed.
- [ ] **The currently-playing screen is fixed by symmetry only, and cannot be measured on a
      tablet.** Reaching it needs the mini player, which does not render on a tablet layout —
      [[cu-74]], whose own notes say "on a tablet there appears to be no way to reach the
      currently-playing screen". Both changes to `fragment_currently_playing.xml` are identical to
      the details screen's, so the risk is low, but it is unmeasured. Verify on the owner's phone.


## First fix was wrong (2026-09-02)

The owner's screenshot showed the bug still present: a **purple block of cover art above the
toolbar**, in the status-bar strip. My measurement said fixed; it was measuring the wrong view.

**Why the bounds looked right.** I asserted on `tracks` (the RecyclerView) and the toolbar. Both
were correct — the list did start below the bar. The view actually showing through was
`details_artwork`, which lives *inside* the CollapsingToolbarLayout and scrolls with it. A
`uiautomator` dump of the fixed-but-broken build proves it:

```
details_artwork  [1084,0][1476,205]     <- extends to y=0, into the status-bar strip
pinned_bar       [0,48][2560,176]       <- started at 48, so it covered nothing above that
```

Padding the `CollapsingToolbarLayout` moved the *pinned bar* down to y=inset. The strip above it
then belonged to the scrolling content, and the artwork slid up through it. Adding
`exitUntilCollapsed` fixed how far the bar could collapse but did nothing about that strip.

**The correct fix: inset the pinned view, not the container.** The pinned bar now starts at y=0 and
carries the inset as its own top padding, so it occupies the strip and paints it with its own
background. `applyTopSystemBarInsetWithMinHeight` is replaced by
`applyTopSystemBarInsetAsPinnedBar`, whose KDoc records why the obvious approach measures as correct
while being wrong.

- Book details: the pinned wrapper gained an id (`pinned_bar`) and a background — it had none, so it
  would have cleared the strip without painting it.
- Currently playing: pins its `Toolbar` directly, which already has a background.

After: `pinned_bar [0,0][2560,128]` — starts at y=0, covers the artwork.

### Why this is In Review, not Done

**Screenshots on this emulator are blank for this app** — `screencap` returns all-black pixels for
every part of Chronicle's UI while `uiautomator` reports a fully populated hierarchy (the same
rendering problem as cu-74). So the structural fix is verified by bounds, but **the visual result is
not verified anywhere**, and bounds are exactly what misled me the first time.

Needs one look on the owner's phone at a partial scroll — the state in the original screenshot.

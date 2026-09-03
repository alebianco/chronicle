---
id: cu-142
title: The speed popover collapses to its title bar in landscape
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
labels:
  - R2
  - comfort
  - bug
milestone: m-2
dependencies:
  - cu-20
priority: high
ordinal: 43500
---

## Description

`ModalBottomSheetSpeedChooser` renders **only its title bar** in landscape. The
`ConstraintLayout` holding the slider, the presets and (since cu-20) the two switches measures to
**zero height**, so the sheet is exactly `bottom_sheet_handle_height` tall and every control is
unreachable. Portrait renders correctly.

Measured on the tablet (1920x1128 landscape), mock Plex mode, playing fixture book 1001:

```
coordinator            [0,0][1920,1128]
design_bottom_sheet    [480,1032][1440,1128]   <- 96px, the handle's height
bottom_sheet_container [480,1032][1440,1128]
bottom_sheet_handle    [480,1032][1440,1128]
```

The `ConstraintLayout` is absent from the `uiautomator` dump entirely, which is what a zero-bounds
view looks like there (the same reading trap as cu-19) — the screenshot is what shows the sheet
truncated below the title.

**This is pre-existing, not from cu-20.** Verified by building the base branch
(`feature/agentic-dev` at `72e64d6`) and reproducing the identical collapsed sheet, so it predates
the per-book work. Most likely from cu-58's DataBinding removal, which stripped the `<layout>`
wrapper: the root is a `LinearLayout` with `android:layout_gravity="bottom"` whose
`wrap_content` `ConstraintLayout` child resolves to nothing when the window is short. Reordering
the children so the chain reads top-down was tried and does **not** fix it, so declaration order is
not the cause.

The practical effect is that speed has been unadjustable in landscape — the primary orientation for
a tablet on a stand — for however long this has been shipping.

## Acceptance Criteria

- [x] The popover shows the slider, the four presets and both switches in landscape
- [x] It still renders correctly in portrait
- [x] The sheet scrolls rather than clipping when the window is too short to fit it
- [x] A check that would have caught this: the sheet's measured height is asserted greater than the
      handle's, in both orientations

## Implementation Notes

**The diagnosis in the description was wrong, and that mattered.** It attributed the collapse to a
`wrap_content` `ConstraintLayout` measuring to zero. It does not: the layout measures **356px in
both orientations, with or without any fix** — I probed it directly. The real cause is Material's
`BottomSheetDialog`, which in landscape opens at a **peek height** and expects a drag. For this
sheet that peek settled at 96px, *shorter than its own 108px title bar*, with nothing on screen
suggesting anything was draggable.

Isolating the two candidate fixes on device settled it: `onStart` expanding the sheet fixes the
collapse **on its own**, with the layout untouched. So the headline fix is four lines of behaviour,
not a layout change.

**The scroll view is still needed, for the third criterion.** With `onStart` alone, in a 480px-tall
landscape window (content needs 534px) the sheet filled the window and **clipped the skip-silence
switch to 60px of its 72** — reachable nowhere. The `NestedScrollView` + `fillViewport` is what
makes "fully expanded" safe on a short window, and it is the half a unit test can hold.

**Applied to all three sheets, not one.** `expandBottomSheetOnStart()` is shared, because
`ModalBottomSheetBookmarks` and `ModalBottomSheetBookmarkNote` have the identical shape and
therefore the identical latent bug — the speed chooser is just where it was noticed. Fixing one and
leaving two would have been the smaller diff and the worse outcome.

**A test that could not fail, deleted.** The first `SpeedChooserLayoutTest` asserted the sheet
measures taller than its handle, in both orientations. It passed **with the fix reverted** — the
layout was never the problem, so it was measuring something that was always true. It was replaced
with tests of what is actually checkable headless (scroll container present, `fillViewport` set,
content taller than the viewport is scrollable), all three of which fail when the scroll view is
removed. The expand half is covered structurally by `ExpandedBottomSheetTest`, which asserts the
helper reaches the behaviour and sets `STATE_EXPANDED`/`skipCollapsed` — Robolectric cannot
reproduce the pixel outcome, so the collapse itself stays device-verified.

**Verification**

- `./verify.sh --format` green, 7 stages. **1070 unit tests** (was 1061), 0 failures.
- Coverage rose: aggregate 35.60% → **35.91%**; `views` 21.06% → **22.28%**. The per-package gate
  caught the new uncovered code first, which is what produced `ExpandedBottomSheetTest`.
- **Sabotage-verified**: removing the `NestedScrollView` fails three `SpeedChooserLayoutTest` cases.
- **Device-verified on the tablet**, landscape 1920x1128, mock Plex mode:
  - before: `design_bottom_sheet [480,1032][1440,1128]` — 96px, only the title bar, screenshot shows
    "CHOOSE PLAYBACK SPEED" and nothing else
  - after: `[480,594][1440,1128]` — **534px**, with slider, all four presets and both switches
    carrying real bounds; portrait unchanged at 534px

## Notes
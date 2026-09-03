---
id: cu-142
title: The speed popover collapses to its title bar in landscape
status: To Do
assignee: []
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

- [ ] The popover shows the slider, the four presets and both switches in landscape
- [ ] It still renders correctly in portrait
- [ ] The sheet scrolls rather than clipping when the window is too short to fit it
- [ ] A check that would have caught this: the sheet's measured height is asserted greater than the
      handle's, in both orientations

## Notes

Do not "fix" this by giving the `ConstraintLayout` a fixed height — the content is variable and a
short landscape window genuinely needs scrolling, which is the point of the third criterion. A
`NestedScrollView` between the root and the `ConstraintLayout` is the conventional shape for a
bottom sheet whose content can exceed the window.

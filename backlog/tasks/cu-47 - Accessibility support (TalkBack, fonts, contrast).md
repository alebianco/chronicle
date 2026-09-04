---
id: cu-47
title: 'Accessibility support (TalkBack, fonts, contrast)'
status: In Review
assignee: []
created_date: '2026-07-13'
labels:
  - R2
  - accessibility
milestone: m-2
dependencies: []
priority: medium
ordinal: 60000
---

## Description

H8: no evident a11y support. Content descriptions on all images/icons, TalkBack pass, large-font + high-contrast testing, a11y checks in CI. Pairs with the R3 design work (cu-26/27) and the WCAG palette cherry-pick (cu-4).

Analysis: [`H8-accessibility-support-plan.md`](../docs/analysis/H8-accessibility-support-plan.md).

## Acceptance Criteria

- [x] Content descriptions on all actionable/informative UI — audited: already complete, and now
      guarded
- [ ] TalkBack navigable end to end — **cannot be verified on this hardware**: TalkBack is not
      installed on the tablet's LineageOS build (no Google apps). Left unticked.
- [x] Large fonts usable — verified by screenshot at **1.3x and 1.5x**; nothing clips or overlaps
- [ ] High contrast usable — **not verified**; the palette is cu-4's WCAG work and is a visual
      judgement
- [x] a11y lint/test in CI — two guards in `verify.sh`, both sabotage-verified

## Implementation Notes

**The audit's premise was out of date.** `H8-accessibility-support-plan.md` assumed "no evidence of
accessibility support — ImageViews likely missing contentDescription". Measured instead:

| | |
|---|---|
| Images with an XML `contentDescription` | 27 |
| Images labelled from Kotlin (cover art → book title) | 8 |
| Images with **no** label | **0** |

So labelling was already done, and the eight Kotlin-set ones are *correct* to be dynamic — a cover's
best label is the book's title, which is only known at bind time. `ContentDescriptionTest` accepts
either source for that reason and exists to hold the line rather than to drive a cleanup.

**The real defect was touch-target size, and it was specific rather than systemic.** Measured with
`uiautomator dump` on the tablet (density 240 = 1.5x, so dp = px / 1.5):

| control | before | after |
|---|---|---|
| skip-to-previous, rewind, skip-forward, skip-to-next, sleep timer, bookmark | **32dp** | **48dp** |
| play/pause | 64dp | 64dp |
| track rows / nav items | 48dp / 56dp | unchanged |

All six sat below Android's 48dp minimum — the controls a listener reaches for while walking or
driving, which is this app's context. `bookmark_edit` in the bookmark row was a seventh at 44dp,
found by the guard rather than by eye.

**The fix keeps the icon the same visual size**: a 48dp box with 8dp padding around the existing
32dp icon (`min_touch_target` + `touch_target_padding`), so only the tappable area grew. Confirmed
by screenshot — the player is visually unchanged.

**Both guards are sabotage-verified**, and the first sabotage attempt was instructive: shrinking a
control back to `list_icon_size` while *leaving* the padding still yields 32 + 2×8 = 48dp, so the
test correctly passed. It fails only when the effective target really drops below 48dp, which is
what it should measure.

**Left `In Review`.** Two criteria are unmet and one is a screen change:

- **TalkBack is a hard blocker on this hardware** — not installed, and this is a LineageOS build
  without Google apps. It needs a device with TalkBack, or a Play-services emulator image.
- **High contrast** is cu-4's WCAG palette work and is a visual judgement, not a measurement.
- **What needs your eye:** the six player controls now have a 48dp tap area behind a 32dp icon. The
  screenshot shows no visible change, but the *feel* of the spacing is a design call.

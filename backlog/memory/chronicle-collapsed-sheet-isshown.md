---
name: chronicle-collapsed-sheet-isshown
description: "A collapsed bottom sheet keeps children VISIBLE at zero height, so isShown lies; measure the container before diagnosing any player layout bug"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: eb8384f7-4315-4572-b92d-890d4987c009
  modified: 2026-09-05T16:58:53.987Z
---

`View.isShown` walks only the visibility **flags** up the ancestor chain. Chronicle's expanded
player lives in a bottom sheet that collapses to **zero height** rather than going GONE, so every
child keeps `VISIBLE` with real bounds inside a container with no room, and `isShown` stays `true`
while nothing is on screen. Measured on the tablet, fully collapsed:

```
container = 0,990-1920,990   (zero height)
seekbar   = 48,108-1872,180  isShown=true  width=1824
root height = 0
```

**Why:** cu-141 took seven attempts. Six of them moved ConstraintLayout constraints around and
found nothing, because the constraints were never wrong — *every measurement had been taken with
the sheet collapsed*, where `wrap_content` views below the visible region measure to zero width.
The bug looked view-specific and orientation-specific and was neither. The owner's question
("the text container is sized? maybe that's collapsing it?") is what broke it open.

**How to apply:** Before diagnosing any zero-width/blank view in `CurrentlyPlayingFragment` or any
bottom-sheet UI here, **walk the ancestor chain and read the container's height first**
(`dumpsys activity top`, then walk up by indentation). If the container is at mini-player height
(~108px) or zero, the sheet is collapsed and every measurement below it is meaningless.

Two corollaries, both now pinned by `CollapsedSheetGuardTest` (sabotage-verified):

- A guard deciding "is the expanded player on screen" must test `binding.root.height == 0`
  alongside `isShown`.
- A listener that re-renders on expand must watch the root's **height**, not an `isShown`
  transition — that transition never fires, because the children were `VISIBLE` all along.

Also: a *repeatable* check is mandatory here. An earlier fix was declared done on one screenshot,
then failed to reproduce with byte-identical XML — the success had been timing-dependent. Toggle
collapse/expand several times and compare widths across cycles. See
[[chronicle-device-check-catches-wiring]] and [[chronicle-profile-before-optimising]].

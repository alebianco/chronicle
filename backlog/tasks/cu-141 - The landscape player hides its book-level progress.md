---
id: cu-141
title: The landscape player hides its book-level progress
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - bug
  - comfort
milestone: m-2
dependencies:
  - cu-19
priority: medium
---

## Description

Found while verifying [[cu-19]] on the 800dp landscape tablet.

`binding.progress` — the book-level line, now `6h 12m left in book` — carries

```xml
android:visibility="@integer/currently_playing_artwork_visibility"
```

which `values-land/integers.xml` sets to `2` (GONE). So **the landscape player shows no
book-level progress at all**: the listener sees where they are in the *chapter* and what
percentage of the book is done, but never how much book is left.

Two things are tangled here and only one was fixed under cu-19:

- **Fixed in cu-19.** `renderPlayerText()` guarded on `binding.progress.isShown`, and since that
  view is permanently GONE in landscape the guard returned early *every* time — so the chapter
  position, chapter duration, percentage **and chapter title** were all blank too. The guard now
  anchors on `chapterProgressSeekbar`, which is present in both orientations.
- **Still open, this task.** The view itself remains hidden, and it is *text*, not artwork —
  sharing the artwork's visibility flag is simply wrong now. Unhiding it is not a one-line change:
  it is constrained to `details_artwork` (`layout_constraintTop_toBottomOf`,
  `layout_constraintRight_toRightOf`), which genuinely does not exist in landscape, so it needs a
  landscape constraint set of its own.

Deliberately out of cu-19's scope: that task's criterion is about the *format* of the readout, and
this is about the *layout* of one orientation. Filed rather than folded in so the layout work is
visible instead of hidden inside a formatting change.

## Also hidden: the chapter position line

`chapter_progress` (now `Ch 3 of 6`) **is written correctly** — the accessibility dump proves it,
reporting `text: Ch 1 of 10` on that view — but it lays out at **zero width**
(`boundsInParent: Rect(0, 0 - 0, 29)`) and never appears. It is constrained to
`chapter_progress_seekbar`, which the same dump reports as `visible: false` in this layout, so the
constraint resolves to nothing.

Note the diagnostic trap this cost: `uiautomator dump` **omits** a zero-bounds view from its tree
as an "invisible child", so the text reads as *missing* rather than as *present but unplaced* —
two very different bugs. The `AccessibilityNodeInfoDumper` lines in logcat show what the dump
dropped, and are the way to tell them apart.

So landscape is missing **two** of the four readout lines, both for layout reasons, both needing
the same landscape constraint set.

## Device findings (2026-09-05) — confirmed, and three failed fixes

Reproduced on the 800dp tablet against the real ANTARES library, portrait *and* landscape, with
screenshots. The task's diagnosis is **correct**: in landscape the player shows `3:43 left in
chapter` and nothing else; portrait shows `6h 11m left in book` in the same build, so the data and
the render path are fine and this is purely the constraint set.

**Three approaches were tried and all three failed. Recording them so the next attempt does not
repeat them:**

1. **Re-anchor `progress`/`progressPercentage` to the gutters + a `Barrier` over
   `details_artwork`.** The barrier resolved to the spacer rather than the artwork, and the result
   **regressed portrait** — the book line disappeared there too. Caught only by screenshotting
   portrait on unmodified code afterwards; the landscape screenshot alone looked like progress.
2. **`currently_playing_artwork_max_size` = `0dp` in `values-land`.** Crashes at inflation:
   `InflateException` on the `ImageView`, because the view carries
   `layout_constraintDimensionRatio="1:1"` over a `wrap_content` height and a zero max makes that
   degenerate.
3. **Artwork `INVISIBLE` instead of `GONE` via the integer.** Crashes with
   `ArrayIndexOutOfBoundsException: length=3; index=4`. **`android:visibility` in XML is an enum
   ordinal — 0 visible, 1 invisible, 2 gone — not `View.INVISIBLE`, which is 4.** Using 1 inflates
   fine, and with the artwork capped small it *did* make `progressPercentage` appear in landscape
   at the right gutter. But `progress` stayed empty, because it also carries
   `layout_constraintEnd_toStartOf="@+id/progressPercentage"` with no left anchor, so it collapses
   to zero width once the artwork is not there to give it one.

### What the next attempt should know

- **A `uiautomator` dump omits an empty `TextView` entirely**, so "absent from the dump" and
  "rendering blank" look identical. Screenshot, and check *both* orientations against unmodified
  code before believing a fix.
- The real shape of the problem is that `progress` has **two competing horizontal constraints**
  (`End_toStartOf` the percentage, `Right_toRightOf` the artwork) and a vertical one to the
  artwork. All three need a landscape answer together; fixing one at a time produces a view that
  is present but zero-sized, which reads as "still broken".
- A full `layout-land` copy is 385 lines and would drift. Worth considering a `<merge>`/`<include>`
  split of just the metadata block instead, so only the differing constraints are duplicated.

**Nothing was committed** — the working tree was restored to the unmodified layout, verified green.

## Acceptance Criteria

- [ ] The book-level progress line is visible in the landscape player
- [ ] The chapter position line (`Ch 3 of 6`) lays out with real width in landscape
- [ ] `binding.progress` no longer keys its visibility off
      `currently_playing_artwork_visibility` — it is text, not artwork
- [ ] Verified on the 800dp landscape tablet **and** in portrait, since the constraint set differs
- [ ] `RawDurationFormatTest` still passes: the line must stay human-formatted when it appears
- [ ] The sleep-timer countdown is visible in landscape (see the confirmed case below)

## Notes

While here, check the other views constrained to `details_artwork` for the same problem —
`progressPercentage` is constrained to it too and *does* render, so the constraint alone is not
fatal; it is the shared visibility integer that hides `progress`.

**One more confirmed case, found during cu-21 (2026-09-03): `sleep_timer_countdown`.** It is a
`0dp x 0dp` overlay constrained on all four sides to `details_artwork`, which is GONE in
`values-land` (`currently_playing_artwork_visibility` = 2). So in landscape the countdown has
nothing to size against and never appears: the sleep-timer *icon* lights up correctly, but the
remaining time is invisible — the user can see a timer is set and not how long is left.

Verified on the tablet with a 5-minute timer: portrait shows `04:45` counting down in the artwork
overlay; landscape shows the lit icon and no text. This is the same root cause as `progress`, so
whatever constraint set fixes that should cover this — it is listed as a criterion rather than a
separate task for that reason. Pre-existing; cu-21 only surfaced it.

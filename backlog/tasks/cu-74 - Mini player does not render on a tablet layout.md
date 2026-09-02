---
id: cu-74
title: Mini player does not render on a tablet layout
status: To Do
assignee: []
created_date: '2026-08-31'
labels: [R2, ui]
dependencies: []
priority: medium
milestone: m-2
---

## Description

> **Diagnosed 2026-09-02 (cu-73 session 4): this is not a layout bug.** The cause is
> `MainActivityViewModel.playbackObserver` hiding the sheet on `STATE_STOPPED`/`STATE_NONE`
> with no reachable path back — see [[DRAFT-119]], which carries the repro and the fix. The
> mini player renders correctly at 1200x1920 portrait on a real device; every earlier
> observation was simply made after playback had already stopped. Candidate 1 below
> (zero-height constraints at 16:10) is **ruled out**; candidate 2 is essentially right.
>
> Close this as a duplicate of DRAFT-119, or rescope it to whatever large-screen work
> genuinely remains once that lands.

Observed while trying to screenshot the cu-9 sync badge on a Pixel Tablet AVD
(2560x1600, landscape).

Playback was confirmed running — `/:/timeline` reports firing every ~5s, `AudioFlinger`
decoding — and `MainActivity` sets `currentlyPlayingContainer` to `VISIBLE` whenever
`isLoggedIn` is true. But **no mini player was visible anywhere on screen**, and the
`currently_playing_*` views were absent from a `uiautomator` hierarchy dump. Only the book
details screen's own views were present.

Consequence: on a tablet there appears to be no way to reach the currently-playing screen
at all, since the collapsed player is the handle that expands it. That makes the player —
and anything on it, including the cu-9 badge — unreachable.

### Not yet diagnosed

Candidates, in rough order of likelihood:

1. There is exactly one `res/layout/` directory and one `activity_main.xml` — no `-land`,
   no `sw600dp` — so the collapsed player's constraints may resolve to zero height or
   off-screen at a 16:10 landscape ratio. (`values-land/` does exist, so some landscape
   tuning was anticipated but never extended to layouts.)
2. `setBottomSheetState` may be leaving the sheet in a hidden state that
   `currentlyPlayingLayoutState` never moves out of on first launch.
3. The container is set `INVISIBLE` rather than `GONE` when not logged in, deliberately, to
   hold its layout slot — if `isLoggedIn` emits late or not at all under mock mode, it
   would stay invisible while still occupying space.

Worth confirming on a phone AVD first: if the mini player is fine at 1080x2400, this is
purely a large-screen layout bug and belongs with **cu-28 (adaptive layouts)** rather than
standing alone.

## Acceptance Criteria

- [ ] Reproduced or ruled out on a phone-sized AVD, to establish whether this is
      tablet-specific
- [ ] Root cause identified rather than worked around by nudging constraints
- [ ] Mini player visible and tappable on both phone and tablet, with the expanded player
      reachable from each
- [ ] Screenshot evidence on both form factors — this was found *because* a screenshot
      could not be taken, so a fix without one proves nothing

## Diagnosis (2026-09-02) — RETRACTED, see correction below

**Reproduced** on a Phh-Treble vanilla GSI, Android 12 / API 32, 1200x1920 @ 240dpi — which the
system reports as `sw800dp xlrg`, i.e. tablet-class. Playback running via the `play_book` debug
hook, chapter title "An Unexpected Party" visible in the hierarchy, so playback state definitely
reached the UI. `uiautomator` dump: **zero** `currently_playing*` views present, while
`main_root`, `bottom_nav`, `toolbar` and the Home grid all are.

**It is candidate 1 (zero height), not candidate 3 (visibility).** `activity_main.xml:36`:

```xml
<... android:id="@+id/currently_playing_container"
     android:layout_width="0dp"
     android:layout_height="0dp"
     app:layout_constraintLeft_toLeftOf="parent"
     app:layout_constraintRight_toRightOf="parent"
     app:layout_constraintTop_toTopOf="@id/bottom_nav">
```

`layout_height="0dp"` in a `ConstraintLayout` means "match constraints" — but the only vertical
constraint is `constraintTop_toTopOf`. **There is no bottom constraint**, so the resolved height is
0. `uiautomator` omits zero-area views, which is exactly why the container looked absent rather
than invisible.

The `INVISIBLE`-not-`GONE` logic at `MainActivity.kt:138` is a red herring: it is correct, and it
never gets the chance to matter because the view has no height to show. Candidate 2
(`setBottomSheetState`) is also not implicated — the sheet state machine is fine; it is
manipulating a zero-height view.

### Why this is not only a large-screen bug

Worth correcting the task's own framing: the missing constraint is **unconditional**, not
ratio-dependent, so this is not "purely a large-screen layout bug" and does not belong with
[[cu-28]]. There is one `activity_main.xml` and no `-land`/`sw600dp` variant, so every device
resolves the same zero height. If the mini player *does* appear on a phone, something else is
supplying a height (a parent's measured pass, or the bottom sheet behaviour overriding it) — which
would make the phone case the accident and this the honest reading.

Next step is therefore a phone-AVD check to establish which of those is true, **not** to decide
whether the bug is large-screen-specific.

### Fix shape

Give the container a real height: either `layout_height="wrap_content"` (with the handle's own
height driving it), or keep `0dp` and add
`app:layout_constraintBottom_toBottomOf="parent"` — whichever matches the bottom-sheet behaviour's
expectations. The `BottomSheetBehavior` peek height is the thing to reconcile against, so this
needs checking against `setBottomSheetState` rather than being changed blind.

**Not fixed here** — this is an R2 task and was not in scope for the session that diagnosed it. But
the "not yet diagnosed" section above is now answered, so the remaining work is the fix and its
verification, not investigation.

## Correction (same session): the diagnosis above is WRONG

**The mini player does render on this device, in both orientations.** Measured with a longer audio
fixture so playback actually sustains:

| orientation | `currently_playing_container` bounds | height |
|---|---|---|
| portrait 1200x1920 | `[0,1644][1200,1752]` | 108px |
| landscape 1920x1200 | `[0,924][1920,1032]` | 108px |

`chapter_title` is present and populated (`[132,1667][1092,1700]`), so the collapsed player is
on-screen, sized, and bound.

**Why the first reading was wrong.** The `uiautomator` dump that showed "zero
`currently_playing*` views" was taken against the **5-second** audio fixture: playback had already
ended, so the media session was not in a playing state when the hierarchy was captured. I read the
absence as a layout defect and went looking for a cause in the layout — and found one that looked
convincing.

**The zero-height reasoning was also wrong on its own terms.** `layout_height="0dp"` with only
`constraintTop_toTopOf` does *not* resolve to zero here: the container is a
`BottomSheetBehavior` child, and the behaviour supplies the height from its peek height at runtime,
which is where the 108px comes from. Reading the XML in isolation missed that.

Two lessons worth keeping, both the same shape as this repo's recorded history:

- **An absent view in a `uiautomator` dump is not evidence of a layout bug** until you have
  confirmed the state that should produce it. The 5-second fixture made "not playing" the default
  and it was invisible in the dump.
- A mechanism that explains the symptom is not thereby the cause. The zero-height story was
  coherent, cited a real line, and was wrong.

### Status

This task's premise is unreproduced on this hardware. It was originally filed against a **Pixel
Tablet AVD at 2560x1600**, which is not what was tested here — so this does **not** close it.
What it does mean:

- Re-test on the original 2560x1600 AVD, with an audio fixture long enough to keep playback alive,
  before spending any more on a layout theory.
- If it does not reproduce there either, the task should be closed as **not reproducible** rather
  than left open against a diagnosis that has now been retracted twice.

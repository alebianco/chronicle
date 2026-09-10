---
id: cu-141
title: The landscape player hides its book-level progress
status: In Review
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
ordinal: 87000
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

## Fourth attempt, 2026-09-05 — reverted, but the diagnosis is now measured rather than inferred

**Nothing committed.** The layout was restored to baseline and verified green. What this attempt
produced is evidence, and it **contradicts this task's stated diagnosis** in one important way.

### The task says portrait is fine. It is not.

Probed with `dumpsys activity top`, which reports real view bounds — the tablet, the real ANTARES
library, *Ender's Game* playing, player expanded and scrolled to top:

```
PORTRAIT (baseline, playing)
  chapter_progress_seekbar   48,329-1152,401     ok
  progress                  527,276- 672,305     145px wide   ok
  progressPercentage        684,276- 684,305     ZERO WIDTH   broken
  chapter_progress           48,401-  48,430     ZERO WIDTH   broken
  chapter_duration          970,401-1152,430     ok

LANDSCAPE (baseline, playing)
  chapter_progress_seekbar   48,108-1872,180     ok
  progress                     0,0-0,0  GONE     broken (visibility integer)
  progressPercentage         960,0  - 960,29     ZERO WIDTH   broken
  chapter_progress            48,180-  48,209    ZERO WIDTH   broken
  chapter_duration          1690,180-1872,209    ok
```

So there are **three** distinct defects, not one:

1. `progress` is GONE in landscape — the shared `currently_playing_artwork_visibility` integer,
   which is what this task describes and the only part it gets right.
2. `progressPercentage` is zero-width in **both** orientations.
3. `chapter_progress` is zero-width in **both** orientations.

Defects 2 and 3 are **not orientation bugs at all**. The screenshots confirm it: portrait shows
only `1:20 left in chapter`, exactly like landscape — no percentage, no `Ch N of M`. This task's
line *"portrait shows `6h 11m left in book` in the same build, so the data and the render path are
fine and this is purely the constraint set"* is **false**, and cu-19 missed it too.

### Why the fix attempted here failed

Re-anchoring all four to `chapter_progress_seekbar` (present and full-width in both orientations)
fixed `progress` in landscape — 145px, up from GONE — and did **nothing** for the other two, which
stayed zero-width through three constraint variants: a packed chain, a two-edge span, and a
single-edge anchor with bias. A view that measures zero under every constraint arrangement is not
being squashed by its constraints.

**The text is written correctly. Measured, not assumed.** A `Timber` probe inside
`renderPlayerText`, run on the tablet with *Ender's Game* playing, printed all four strings
non-empty on every tick while the views measured zero:

```
cu141 probe: book='1m left in book' pct='100%' chapterPos='Ch 107 of 107'
             chapterDur='1:59 left in chapter' progressNull=false hasChapters=true
```

So `renderPlayerText`, its `isShown` guard and every string producer are **innocent**. This is
purely a measure/constraint problem, which is what the task always said — the correction is only
that it is not *only* a landscape one.

**The likely mechanism, and the one thing every failed attempt has in common:** a `wrap_content`
view with a **single** horizontal constraint resolves to zero width in ConstraintLayout.
`progressPercentage` has only `Right_toRightOf`; `chapter_progress` has only `Left_toLeftOf`;
`progress` has *two* (`End_toStartOf` + `Right_toRightOf`) and is the only one of the three that
ever renders. That explains all four observations at baseline, in both orientations.

**A fourth attempt applied that reading and still failed** — and made portrait worse. Adding the
second edge to each view is necessary but not sufficient: re-parenting `progress` below
`chapter_progress` while `chapter_progress` sits below the seekbar creates a vertical cycle, and
ConstraintLayout resolves a cycle by collapsing members to zero rather than by reporting it. Three
constraint arrangements were tried (packed chain, two-edge span, single-edge with bias); each fixed
at most one view and broke another.

**What the fifth attempt needs.** Draw the intended vertical order *first* and make it acyclic
before touching horizontal edges:

```
chapter_progress_seekbar
  ├─ chapter_progress   (left)     ─┐ same row
  └─ chapter_duration   (right)    ─┘
     └─ progress        (left)     ─┐ same row
        progressPercentage (right) ─┘
           └─ chapter_title
```

Every view in a row needs both edges named, and no view may anchor its top to something in its own
row. `chapter_title` currently anchors to `chapter_progress`, which is why moving `progress` around
keeps disturbing it.

### Method notes that saved time

- `uiautomator dump` omits **every** zero-bounds view, so all three defects are invisible to it —
  in portrait as well. `dumpsys activity top | grep id/<name>` reports real bounds including
  zero-size ones, and is the tool for this.
- The player must be **scrolled to the top** before measuring: the collapsing toolbar leaves the
  metadata block at negative y otherwise, which reads like a constraint bug and is not one.
- `settings put system user_rotation 0|1` rotates reliably, but only once an app is foregrounded.

## Fifth attempt, 2026-09-05 — reverted, and the cause is now cornered

**Nothing committed.** The one useful outcome is that the remaining hypothesis space is small.

### The measurement that rules out everything previously theorised

A probe printing text, width and visibility together, on the tablet, portrait, playing, player
expanded, chapter 10 of 107:

```
cu141b: chapterProgress.text='Ch 10 of 107'        w=0 vis=0
        progress.text='10h 10m left in book'       pw=0
        pct.text='9%'                              pctw=0
```

`vis=0` is VISIBLE. The text is correct on all three. **Width is zero anyway.** And this was with
`chapter_progress` carrying *two* horizontal constraints — `Start_toStartOf=left_gutter` and
`End_toStartOf=chapter_duration`, plus `bias=0` — so it was positioned exactly at the gutter
(`24,401-24,430`) with no width.

That eliminates, with evidence:

- ~~the text is never written~~ — it is, correctly, on every tick
- ~~the view is GONE / the visibility integer~~ — `vis=0`, and this holds in portrait where the
  integer is 0 anyway
- ~~a single horizontal constraint collapses `wrap_content`~~ — two constraints, still zero
- ~~a vertical cycle~~ — the graph was verified acyclic programmatically *before* building
- ~~`setTextIfChanged` swallowing the write~~ — it is a plain `if (this.text != text)` guard
- ~~the style~~ — `chapter_duration` uses the identical `TextAppearance.Body2` and renders 182px

### The one structural difference left

`chapter_duration` is the only one of the four that renders, and the only one anchored
`Right_toRightOf` a **real-width** view (`chapter_progress_seekbar`, `0dp` across the gutters).
Every failing view either anchors to `details_artwork` or, in the fifth attempt, to a guideline.
A `wrap_content` TextView measuring zero while holding text points at the **parent's** measure
pass rather than at any one view's constraints — the enclosing `ConstraintLayout` inside a
`NestedScrollView`/`CollapsingToolbarLayout` is the next thing to look at, not the child.

### The parent is fine — checked, so that theory is dead too

The ancestor chain measures correctly; nothing is collapsing from above:

```
ConstraintLayout            0,0-1200,796     <- the players own root
CollapsingToolbarLayout     0,0-1200,796
AppBarLayout                0,0-1200,796
CoordinatorLayout           0,0-1200,1710
```

### The sharpest clue, and where a sixth attempt must start

`chapter_duration` renders at **182px**. `chapter_progress` renders at **0**. Their XML is
*identical* apart from one attribute:

```xml
<!-- renders -->
app:layout_constraintRight_toRightOf="@id/chapter_progress_seekbar"
<!-- zero width -->
app:layout_constraintLeft_toLeftOf="@id/chapter_progress_seekbar"
```

Same style, same `wrap_content` on both axes, same `Top_toBottomOf` anchor, same parent, both
holding text. **A `Right_toRightOf` works and the mirrored `Left_toLeftOf` does not.**

That is not a plausible ConstraintLayout behaviour on its face, which means something not visible
in these two elements is differentiating them. Candidates, in the order worth testing:

1. **RTL/`layoutDirection` resolution.** `Left`/`Right` are absolute; `Start`/`End` are
   direction-aware. If something in the tree resolves direction oddly, absolute and relative edges
   can disagree. The device is `ldltr` (from the config dump), so this should be inert — but it is
   the only asymmetry between the two attributes.
2. ~~A duplicate id shadowing this view~~ — **checked and ruled out.** `id/chapter_progress"`
   appears exactly twice in the layout: one declaration (line 216) and one reference from
   `chapter_title`'s top constraint (line 243).
3. ~~Something writing a layout param at runtime~~ — **checked and ruled out.** The only code near
   this geometry is `addOnLayoutChangeListener` on `chapter_progress_seekbar` (~line 414), and it
   re-runs `renderPlayerText()` on a visibility transition. It sets text, never layout params.
   Nothing in `app/src/main` assigns `chapterProgress.layoutParams` or `.width`.

So **candidate 1, direction resolution, is the only one of the three left standing**, and it is a
weak candidate on a `ldltr` device. Something outside these two elements is differentiating an
otherwise-identical pair, and five constraint-level attempts plus three ruled-out theories have not
found it.

## Attempt 6 (2026-09-05) — instrumented, and the diagnosis is overturned

Attempt 6 stopped editing constraints and **measured instead**, with a temporary probe logging
both TextViews side by side from the seekbar's layout-change listener. Two experiments settled it.

### 1. The probe: the views are never measured

```
CP text='No chapters'     w=0 mw=0 lpW=-2 lS=<seekbar> rE=-1 hBias=0.5 minW=0 maxW=MAX
CD text='0m left in book' w=0 mw=0 lpW=-2 lS=-1 rE=<seekbar> hBias=0.5 minW=0 maxW=MAX
parent=1920x575 pMW=1920 seek=1824 cpPaint=119.0 cdPaint=145.0 cpTextSize=21.0 cpLayoutW=null
```

Read it carefully — every previous theory dies here:

- **The text is present and has real width.** `paint.measureText` says 119px and 145px.
- **No width constraint is imposed.** `lpW=-2` is WRAP_CONTENT, `minW=0`, `maxW=Integer.MAX_VALUE`.
- **The parent measures fine** at 1920, and the seekbar they anchor to is 1824 wide.
- **`cpLayoutW=null`** — `TextView.layout` is null, meaning the view **has never completed an
  `onMeasure` pass at all**. `measuredWidth=0` is not a squeezed result; it is an *absent* result.

So this was never a constraint being violated. The view is not being measured.

### 2. The swap: the bug follows the *view*, not the constraint

The decisive experiment. Swap the two views' horizontal constraints — give `chapter_progress` the
`Right_toRightOf` that works, and `chapter_duration` the `Left_toLeftOf` that fails:

| build | `chapter_progress` | `chapter_duration` |
|---|---|---|
| original, landscape | `48,180-48,209` (**0px**) | `1690,180-1872,209` (182px) |
| original, portrait | `48,401-173,430` (125px) | `970,401-1152,430` (182px) |
| **sides swapped, portrait** | `1152,401-1152,430` (**0px**) | `48,401-230,430` (182px) |

`chapter_progress` is zero-width **in portrait too** once swapped, while `chapter_duration` renders
from the very constraint that had just "failed". The defect stayed with `chapter_progress`.

That kills the entire framing of this task. It is **not** an orientation bug, **not** a
`Left`-vs-`Right` bug, and **not** caused by `details_artwork` being GONE. Portrait only ever
looked healthy by accident.

### Also ruled out this attempt

4. ~~`app:layout_optimizationLevel`~~ — set to `none` on the ConstraintLayout; no change.
5. ~~RTL resolution~~ — `Start_toStartOf` behaves identically to `Left_toLeftOf`. Not a
   direction-resolution issue, which retires candidate 1 as well.
6. ~~Anchor target~~ — re-anchored both readouts to `left_gutter`/`right_gutter` instead of the
   slider. The x origin moved correctly (24 / 1896) and `chapter_progress` stayed zero-width.
7. ~~An opposing constraint~~ — adding `Right_toLeftOf="@id/chapter_duration"` with
   `horizontal_bias="0"` made **both** views collapse, confirming the failure propagates along the
   anchor chain rather than originating in one anchor.
8. ~~The style, the strings, runtime writes~~ — `TextAppearance.Body2` is shared with the sibling
   that renders; `player_chapter_of` / `player_no_chapters` have single definitions in `values/`;
   `setTextIfChanged` is a plain `if (this.text != text)`; and nothing in `app/src/main` touches
   `chapterProgress` beyond that one call.

### Where this now points

### 3. The identity check — the fragment being probed is not the one on screen

Walking the whole view tree for both ids returned **exactly one of each**, under a common root,
each matching its `binding` field:

```
binding.cp=36518034 binding.cd=215228000
matches=2 -> [which=CP id=36518034 w=0 root=124371555,
              which=CD id=215228000 w=0 root=124371555]
```

So **there is no duplicate id and no wrong ViewBinding reference** — that suspicion is retired.

But comparing those identity hashes against `dumpsys` at the same moment is decisive
(`dumpsys` prints `identityHashCode` in hex):

| view | `binding` field | rendered per `dumpsys` |
|---|---|---|
| `chapter_progress` | `36518034` = `0x22d3892` | `e1416c3` |
| `chapter_duration` | `215228000` = `0xcd41e60` | `d30ba79` |

**Different instances entirely.** And the rendered pair measured `48,401-173,430` (125px) and
`970,401-1152,430` (182px) — *both correct* — while the instances my probe held reported `w=0`,
`mw=0`, `layout=null`.

So the zero-width views are a **stale, detached fragment view hierarchy** that is still receiving
text updates, while a second, live hierarchy renders correctly. The collapsed measurements are
real but belong to a view tree nobody is showing.

### Where this points now

This is a **fragment view-lifecycle bug, not a layout bug**. The likely shapes:

- a `CurrentlyPlayingFragment` view destroyed on configuration change while its `StateFlow`
  collectors keep writing into the old `binding` (the CLAUDE.md rule is `collectWhileStarted` on
  `viewLifecycleOwner`; a collector bound to the *fragment* rather than the view lifecycle would
  do exactly this);
- two instances of the fragment alive at once — one attached, one retained — after the
  rotation/`CollapsingToolbarLayout` re-creation;
- `binding` not nulled in `onDestroyView`, so the stale reference outlives its hierarchy.

**Checked, and both of the obvious shapes are already correct:**

- All 27 collectors use `viewLifecycleOwner.collectWhileStarted` / `collectEventsWhileStarted`.
  None is bound to the fragment lifecycle.
- `binding` is a **local `val` inside `onCreateView`** (line 140), captured by the lambdas, and
  returned as `binding.root`. There is no `_binding` field and so nothing to null in
  `onDestroyView` — the safe pattern, and each `onCreateView` gets a fresh capture.
- `dumpsys` shows exactly **one** `CurrentlyPlayingFragment` instance, so there are not two
  fragments running.

That combination narrows it to: an **earlier `onCreateView`'s captured `binding` is still being
written to** after its view hierarchy was replaced. With `viewLifecycleOwner` collectors that
should not outlive the view — unless the listener doing the writing is not a collector.
`addOnLayoutChangeListener` on `chapter_progress_seekbar` (~line 414) is registered per
`onCreateView` and **never removed**; it captures that call's `binding` and re-runs
`renderPlayerText()`. That is the one writer here with no lifecycle unregistration, and it is
where the probe was attached — which is very likely why the probe saw a stale hierarchy.

**That was checked, and the stale hierarchy WAS a probe artefact.** Re-attaching the probe to the
`viewLifecycleOwner.collectWhileStarted(viewModel.playerProgress)` collector gives an identity hash
that matches `dumpsys` exactly (`cp=132794761` = `0x7ea4989`, and `dumpsys` shows `7ea4989`). So
ViewBinding, the fragment lifecycle and the collectors are all correct, and the "two hierarchies"
line of enquiry is closed. The un-removed `addOnLayoutChangeListener` is still worth tidying, but
it is not this bug.

### The confirmed bug, on the live view

With the probe on a proper collector, in landscape, on the same instance that `dumpsys` renders:

```
cp=132794761 w=0   text='Ch 13 of 107'
cd=169819534 w=182
```

`chapter_duration` was `w=0` for three ticks and then became 182; `chapter_progress` holds correct
text and **never** leaves zero. So the defect is real, it is on the live view, and one sibling
recovers from the same initial state while the other does not.

9. ~~`chapter_progress` being load-bearing for the vertical chain~~ — **ruled out.**
   `chapter_title` is `Top_toBottomOf="@id/chapter_progress"`, making it the only one of the pair
   that something below depends on. Re-anchoring `chapter_title` to `chapter_duration` instead, so
   nothing references `chapter_progress` at all, left it at zero width.

### State at the end of attempt 6

Everything was reverted; no production change is committed. What is now known for certain:

- it is **not** the text, the writer, the strings, or the style;
- it is **not** the anchor, the side keyword, RTL, the optimizer, or an opposing constraint;
- it is **not** a duplicate id, a stale binding, a second fragment, or a collector bound to the
  wrong lifecycle;
- it is **not** caused by other views depending on it;
- it **is** reproducible in portrait too once the two views' constraints are swapped, so the
  orientation framing in this task's title is wrong.

The one property that has tracked the bug through every experiment is **the view itself**, not any
of its relationships. That is a strange result and it is where a fresh attempt should start —
ideally with Layout Inspector, which would show the resolved measure spec that no `dumpsys` field
exposes.

## Attempt 7 (2026-09-05) — SOLVED

The owner's question — *"the text container is sized? maybe that's collapsing it?"* — was the
key, and it was right.

### The container collapses, and `isShown` cannot see it

Walking the ancestor chain from `chapter_progress` rather than reading the layout file:

```
currently_playing_container  0,882-1920,990   ← 108px, the mini-player
  CoordinatorLayout          0,0-1920,108
    AppBarLayout             0,0-1920,575     ← 575px of content
      CollapsingToolbarLayout  0,0-1920,575
        ConstraintLayout       0,0-1920,575
```

**Every measurement in attempts 1-6 was taken with the sheet collapsed.** The player lives in a
bottom sheet that collapses to the mini-player height — and at its smallest, to *zero* height —
while the content inside still measures 575px. Anything below the visible region measures to zero
width. `chapter_progress` sits at y=180. That is the entire six-attempt mystery: nothing was ever
wrong with the constraints.

### Why it was intermittent

`View.isShown` walks only the visibility **flags** up the ancestor chain. A collapsed bottom sheet
does not go GONE — it keeps every child `VISIBLE` and shrinks the container to zero height. Probed
while fully collapsed:

```
PASSED: seekShown=true seekW=1824 rootH=0 progW=0 progVis=0
```

The guard **passed** with the fragment root at zero height. Two consequences, both intermittent,
which is what made this so expensive:

1. `renderPlayerText` wrote text into a hierarchy with no room, so `wrap_content` readouts below
   the collapsed region measured zero width. Whether the line recovered depended purely on which
   1 Hz tick happened to land after an expand.
2. The re-render listener keyed on an `isShown` transition **that never fires**: `wasShown` went
   true while still collapsed, so the `shown && !wasShown` edge was missed on every expand, and
   stale text was never corrected.

This also explains attempt 6's "byte-identical XML, different result" — the XML was never the
variable.

### The fix

- Both guards test `binding.root.height == 0` alongside `isShown`. The root's height is zero
  exactly while collapsed, in both orientations, without naming a view either one hides.
- The layout-change listener watches the **root's height** rather than an `isShown` transition,
  and re-runs `renderPlayerText()` **and** `refreshSlider()` on expand.
- `progress` no longer carries `@integer/currently_playing_artwork_visibility`. It is text, not
  artwork, and that flag made it GONE in landscape outright — a genuine second defect.
- `progressPercentage` anchors to `right_gutter` rather than the GONE artwork, which collapsed the
  pair to a point at y=0.
- `sleep_timer_countdown` sizes from its own content instead of `0dp x 0dp` against the GONE
  artwork.

### Verified on the tablet

Landscape, fully expanded, dialog-free: `Ch 5 of 107` · `1:35 left in chapter` ·
`10h 41m left in book  4%` — all three present, the first and last of which had never rendered.
Portrait unregressed: artwork visible, all four readouts sized. **Four consecutive
collapse/expand toggles** hold the same widths (`progress` 145px, `chapter_progress` 119px),
which is the check attempt 6 failed.

`CollapsedSheetGuardTest` pins both halves and is **sabotage-verified**: removing either the
height check or the listener's `view.height > 0` fails the build.

### Not verified

The **sleep-timer countdown was not tested with a live timer.** The sizing change is sound in
principle — it can no longer be zero-sized by the GONE artwork — but no countdown was observed
rendering in landscape. That criterion stays unchecked.

**Caveat on the original symptom.** All of attempt 6's measurements were taken with the probe
attached and playback started via `--el play_book`; the user-visible landscape blankness reported
in the task body was observed before that. Confirm the on-screen symptom still reproduces on a
clean build before assuming this stale-hierarchy finding fully explains it — the two may be the
same bug or two different ones.

**Portrait may be affected too** (the swap experiment collapsed a view there), so the task title
and acceptance criteria likely need rewriting once the cause is confirmed.

## Acceptance Criteria

- [x] The book-level progress line is visible in the landscape player
- [x] The chapter position line (`Ch 3 of 6`) lays out with real width in landscape
- [x] `binding.progress` no longer keys its visibility off
      `currently_playing_artwork_visibility` — it is text, not artwork
- [x] Verified on the 800dp landscape tablet **and** in portrait, since the constraint set differs
- [x] `RawDurationFormatTest` still passes: the line must stay human-formatted when it appears
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

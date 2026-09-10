---
id: cu-198
title: Migrate the player screen to Compose
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-187
priority: high
ordinal: 96000
---

## Description

Screen 1 of [[cu-188]]'s order, split off as that task specifies. **First on purpose**:
`CurrentlyPlayingFragment` is 496 Kotlin + 385 XML lines, the largest and most coupled screen, and
it carries **five** of the recorded bugs — cu-141, cu-142, cu-19, cu-110, cu-117. Finding a blocker
here is worth more than five easy wins elsewhere.

cu-181 established there is **no `PlayerView`** anywhere in the app and `androidx.media3.ui` is
imported by no Kotlin file, so there is no video-surface interop to fight. The awkward part is
`MediaServiceConnection` and the `CurrentlyPlaying` singleton, not the rendering.

## What cu-187 established that applies here

- **One sealed state, not N booleans.** cu-187's `CollectionsContent` made a real bug
  (empty + offline rendering together) unrepresentable. The player has more states, so this matters
  more, not less.
- **Seed with an explicit `Loading`**, never `Loaded(empty)` — that seed is indistinguishable from
  real emptiness and makes tests pass vacuously.
- **`Surface`, not a bare `Box`**; wrap in `ChronicleTheme`; `GridCells.Adaptive` if any grid.
- **A relaxed MockK is wrong for anything flow-shaped** — a relaxed `StateFlow<Boolean>` hands back
  `StateFlow<Object>` and `collectAsStateWithLifecycle` throws `ClassCastException`.

## The things to get right

- **`isShown` guards are the cu-19/cu-141 bug class.** `renderPlayerText` guarded on a view that is
  `GONE` in `values-land`, so the whole text block stayed blank in landscape. In Compose the guard
  should disappear entirely rather than be ported — if it survives the migration, ask why.
- **Per-second work must stay cheap** (cu-110/cu-117). `ProgressUpdater` republishes once a second
  during playback; recomposition scope is the Compose equivalent of the `isShown` guard, so measure
  rather than assume. Profile with `am profile start --sampling`, which named the cause at once
  where four rounds of inspection produced plausible wrong answers.
- **Read `currentlyPlaying.chapters`**, never a chapter list off the book (cu-49/cu-82/cu-159).
- **The bottom sheet**: this screen lives inside one. `expandBottomSheetOnStart` and the collapsed
  state are load-bearing (cu-142), and a zero-height sheet keeps its children `VISIBLE`.
- **Both orientations, on a device.** cu-141, cu-142 and cu-19 were *all* landscape-only.

## Acceptance Criteria

- [x] `CurrentlyPlayingFragment` renders through a `ComposeView` — the body only; toolbar, chapter
      list and both choosers stay Views (see notes)
- [x] One `PlayerUiState`, grouped by recomposition boundary; **no visibility boolean survives**
- [x] Compose tests sabotage-verified, twice: gating the readout on the artwork (the cu-19 shape)
      fails the landscape test, and showing play while buffering fails the buffering test
- [~] Per-second cost measured, but **not comparably**: the household tablet dropped off the
      network mid-task and the pass finished on the second device, so the 517 j/20s baseline and
      the 565 j/20s reading are from different hardware. What *is* comparable and does hold: with
      the sheet collapsed `player_compose` is absent from the view tree entirely, so the body does
      no work at all — the guard it replaces could only ever skip *some* writes.
- [x] Verified on a device in **both orientations**, playing — see notes
- [x] No `isShown` guard survives; `CollapsedSheetGuardTest` now fails the build if one returns
- [x] `./verify.sh` green

## Implementation Notes

**Landed in three commits**, deliberately separable: the `isSliding` prerequisite (a real latent
bug), the screen and its state, then the wiring.

**What moved.** `CurrentlyPlayingFragment` 496 → ~250 lines, its layout 385 → ~87. Gone: 29
imperative writes, eight click listeners, three guarded render functions, the `boundTitle`/
`boundThumb` change-detection fields, the seekbar touch listener and its label formatter. The
ViewModel's 24 flows became one `PlayerUiState`, grouped by what changes together — text at 1 Hz,
transport on a play/pause, artwork on a book change — so Compose's own skipping does what
`setTextIfChanged` did by hand at eleven sites.

**The geometry guard is gone, not ported.** `MainActivity.CurrentlyPlayingInterface` was
write-only, so the Fragment inferred "am I on screen?" from `!seekbar.isShown || root.height == 0`.
That inference *is* cu-141 and cu-19. It now reads `bottomSheetState`, which the Activity already
held as a `StateFlow`. Confirmed on device: collapsed, `player_compose` is absent from the view
tree — a stronger result than a guard that has to be remembered at each write site.

**Three defects found on the device that no test could see**, which is the cu-181 lesson repeating:

1. **Icons rendered black on a dark surface** — invisible. The drawables are `_white` by name but
   carry a black fill; `Icon` needs an explicit `tint`. The semantics tree was perfectly correct,
   so every Compose test passed.
2. **The readout was clipped** behind the pinned toolbar — the `ComposeView` needed
   `layout_collapseMode="parallax"` and a top margin of one action bar. The clipping happens in the
   View parent, so again invisible to Compose tests.
3. The first draft bridged `PlayerText`'s resolver through `LocalContext.current.getString`; **lint
   caught it**, correctly — a Context read is invisible to Compose's resource tracking, so the
   readout would not recompose on a locale change.

**Two source-scanning tests were rewritten, not deleted** — they failed by construction because the
mechanisms they policed no longer exist:

- `CollapsedSheetGuardTest` scanned for `isShown` paired with a height check. It is **inverted**
  now: it fails if either inference returns, and separately asserts the replacement gate is
  actually present so it cannot pass against a gutted screen. It scans code only — its first run
  flagged its own explanatory comment, and banning the words would mean the next person cannot be
  told why the guard went away.
- `RawDurationFormatTest` scanned for `binding.<view>.setTextIfChanged`. Repointed at
  `PlayerScreen`, asserting the readouts render through `PlayerText`. Sabotage-verified.

**What stays a View, and why.** The collapsing toolbar owns the scroll contract with the chapter
list; `ChapterListAdapter` is shared with the details screen (cu-188 order 4); both
`BottomSheetChooser`s are shared animated widgets carrying listener objects. Same call cu-187 made
for `SwipeRefreshLayout`. A "fully Compose" player needs those three migrated first.

**The bookmark control nearly went missing.** Writing these notes I claimed it was "not done and
needs a follow-up" — then checked, and it was simply *gone*: dropped with the old layout while its
ViewModel plumbing survived, so nothing failed. It is back in the utility tray, as a `Box` with
`combinedClickable` rather than an `IconButton`, because the long-press is not optional here — it
is the only route to the bookmark list, and `IconButton` takes only `onClick`. Worth recording
because a silent feature loss is exactly what a migration is most likely to ship: the tests were
green and the screenshots looked right.

**Closes to `In Review`**: it changes the most-used screen in the app.

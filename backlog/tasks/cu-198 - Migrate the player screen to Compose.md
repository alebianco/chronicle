---
id: cu-198
title: Migrate the player screen to Compose
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
dependencies:
  - cu-187
priority: high
milestone: m-2
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

- [ ] `CurrentlyPlayingFragment` renders through a `ComposeView`
- [ ] One sealed UI state; no view-visibility booleans survive into the composable
- [ ] Compose tests **sabotage-verified** — a green suite can sit over a visibly broken screen
- [ ] Per-second playback cost measured before and after, against the **3-track, 8-chapter** fixture
      rather than the easy single-track one (cu-110/cu-115)
- [ ] Verified on a device in **both orientations**, playing: chapter text, seek, speed, sleep timer
- [ ] Any `isShown` guard that survives is justified in the notes, or removed
- [ ] `./verify.sh` green; no coverage regression

## Notes

Closes to **In Review**: it changes the most-used screen in the app.

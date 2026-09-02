---
id: DRAFT-119
title: The mini player disappears for good when playback stops
status: Draft
assignee: []
created_date: '2026-09-02'
labels: [R1, ui, player]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found during the cu-73 manual pass, on the Phh-Treble tablet (API 32, portrait 1200x1920),
against the mock server. **This is the actual cause of [[cu-74]]**, which guessed at a
large-screen layout problem; the mini player renders correctly at this size, and landscape
has nothing to do with it.

`MainActivityViewModel.playbackObserver` hides the player sheet whenever playback reports a
terminal state:

```kotlin
when (state.state) {
  STATE_STOPPED, STATE_NONE -> setBottomSheetState(HIDDEN)
  else -> if (currentlyPlayingLayoutState.value == HIDDEN) setBottomSheetState(COLLAPSED)
}
```

Nothing reliably brings it back. The only two paths off `HIDDEN` are:

1. a later non-stopped playback state (`else` branch above) — but the player service does not
   emit one, because playback has ended and nothing is driving it; and
2. `setAudiobook`, which is guarded by `previousAudiobookId != bookId` — so re-selecting the
   **same** book is rejected.

Once the sheet is `HIDDEN`, the collapsed player is the only handle that expands it, so the
currently-playing screen becomes **unreachable** — cu-74's reported consequence, reproduced
from a cause.

### Reproduction (clean, from a force-stop)

```
adb shell am force-stop io.github.mattpvaughn.chronicle.debug
adb shell am start -n io.github.mattpvaughn.chronicle.debug/....MainActivity \
  --ez mock_plex true --el play_book 1001
```

Observed log, in order:

```
Bottom sheet state is HIDDEN                       # initial value
Observing playback: PlaybackState {state=6 ...}    # BUFFERING
Bottom sheet state is COLLAPSED                    # mini player appears, correctly
Observing playback: PlaybackState {state=3 ...}    # PLAYING
Observing playback: PlaybackState {state=1, position=180002 ...}   # STOPPED at track end
Bottom sheet state is HIDDEN                       # gone, permanently
```

A `uiautomator dump` then contains **zero** `currently_playing_*` views, while
`dumpsys media_session` still shows the app's session `active=true`. That mismatch — live
session, no UI handle — is the signature.

### Why it hid behind cu-74 for so long

The book resumed near the end of its last track and stopped within seconds, so every
previous observation was made *after* the sheet had already been hidden. It looked like the
mini player never rendered. With the 180 s tone fixture (cu-115 lengthened it from 5 s) the
COLLAPSED window is finally wide enough to see, which is what made the transition visible.

Note the interaction: a book at the end of its final track stops immediately on resume, so
for a *finished* book the mini player is effectively never reachable at all.

### Open question for whoever picks this up

What *should* the sheet do when a book ends? Candidates, not yet decided:

- stay `COLLAPSED` showing the finished book (simplest; keeps the handle reachable);
- collapse but show a "finished" affordance;
- hide only on an explicit user dismiss / `STATE_NONE`, and treat `STATE_STOPPED` as
  "still the current book, just not advancing".

The third is closest to what other players do, and would make `STATE_STOPPED` non-terminal
for UI purposes. Whichever is chosen, hiding must not be a one-way door.

## Acceptance Criteria

- [ ] A book reaching the end of its final track leaves the player reachable
- [ ] Re-selecting the same book after it stopped restores the sheet (the
      `previousAudiobookId != bookId` guard no longer strands it)
- [ ] `MainActivityViewModelTest` drives a STOPPED transition and asserts the sheet recovers
      — the current tests assert `HIDDEN` as an initial/expected value but never that it is
      escapable, which is why this was invisible to the gate
- [ ] [[cu-74]] closed as a duplicate of this, or rescoped to whatever large-screen work
      genuinely remains after this is fixed
- [ ] Verified on the mock with `play_book 1001` from a force-stop, per the repro above

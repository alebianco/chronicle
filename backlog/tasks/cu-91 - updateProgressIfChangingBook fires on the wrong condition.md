---
id: cu-91
title: updateProgressIfChangingBook fires on the wrong condition
status: Done
labels: [R1, trust, bug]
dependencies: []
priority: high
milestone: m-1
assignee: [claude]
---

## Description

`AudiobookDetailsViewModel.updateProgressIfChangingBook` exists, per its own KDoc, to flush the
outgoing book's position when playback is about to be replaced by a *different* audiobook:

> Check if there is an active audiobook which are about to be replaced by a different audiobook and
> if so, make a network request to inform the server that playback has ended

Its condition is the opposite of that:

```kotlin
val currentlyPlayingTrackId = mediaServiceConnection.nowPlaying.value?.id
val isChangingBooks = tracks.value?.any { it.id == currentlyPlayingTrackId } ?: false
```

`tracks` is `trackRepository.getTracksForAudiobook(inputAudiobook.id)` — the tracks of the book
**being viewed**. So `isChangingBooks` is true exactly when the playing track belongs to *this*
book, which is the case where the user is **not** changing books.

Consequences, both plausible contributors to the reported cross-device position drift (cu-90):

- Pressing play on the book already playing sends a spurious `PLEX_STATE_STOPPED` progress report.
- Pressing play on a *different* book — the case the method exists for — sends nothing, so the
  outgoing book's position is never flushed to the server before playback moves on.

Found while writing the first tests for this ViewModel (cu-57). Deliberately **not** fixed there:
it changes playback and progress-reporting behaviour, so it wants its own change with its own
tests, and the fix should be verified against a live server (cu-73) since the symptom is
server-side position state.

## Acceptance Criteria

- [x] Determine the intended semantics: confirm against `pausePlay`'s call site whether the flush
      should happen when the playing track is *absent* from this book's tracks.
- [x] Invert or rewrite the condition accordingly, with the KDoc and the variable name agreeing
      with the code.
- [x] Unit test both directions: playing a different book flushes the outgoing position; pressing
      play on the already-playing book does not emit a spurious STOPPED report.
- [x] Verify no duplicate progress report is emitted on an ordinary pause/resume of the same book.
- [x] Add a live-server check to cu-73 confirming the outgoing book's position lands on the server
      when switching books on one device and reading it on another.

## Implementation Notes

Owner decision, 2026-09-01: **rewrite the flush path**, not just invert the condition.

The inverted `if` was a symptom. The real defect was *placement*: `AudiobookDetailsViewModel` can
only see switches initiated from the book-details screen, while a book can be started from the mini
player, the library, Android Auto, a media button, or the debug `play_book` intent. Even a correct
condition there would have missed most switches.

All of those paths funnel through `AudiobookMediaSessionCallback.playBook`, which is the only place
that sees both the outgoing and the incoming book. The flush moved there:

- `flushOutgoingBookProgress(incomingBookId)` runs first thing in `playBook`'s coroutine, before the
  track list and `currentlyPlaying` are overwritten.
- It uses **`updateProgressBlocking`**, not `updateProgress`. The latter launches into the service
  scope and the state it reads is replaced a few lines later; the blocking variant exists for
  exactly this shape of problem (its KDoc documents the same race at service teardown).
- Failures are caught and logged: a lost progress report is recoverable, a book that will not start
  is not.

The decision itself is extracted to `OutgoingBookFlush.shouldFlushOutgoingBook(outgoingTrack,
incomingBookId)` — `AudiobookMediaSessionCallback` takes sixteen collaborators including an
`ExoPlayer` and a `MediaSessionCompat`, so testing the judgement in place was not practical.
Six tests, verified by restoring the original inverted comparison: four fail.

`AudiobookDetailsViewModel.updateProgressIfChangingBook` is deleted.

### Follow-up

`progressUpdater` is now unused in `AudiobookDetailsViewModel` but kept as a constructor parameter
— removing it touches the Factory and the Fragment's DI wiring for no behavioural gain, and Dagger
prunes nothing here. Worth a tidy-up alongside [[cu-80]].

### Not verified

The server-side effect. Two checks are appended to [[cu-73]]: that a book left mid-listen shows the
right position on a second device, and that an ordinary pause/resume emits no stray `STOPPED`.

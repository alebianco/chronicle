---
id: cu-91
title: updateProgressIfChangingBook fires on the wrong condition
status: Draft
labels: [R1, trust, bug]
dependencies: []
priority: high
assignee: []
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

- [ ] Determine the intended semantics: confirm against `pausePlay`'s call site whether the flush
      should happen when the playing track is *absent* from this book's tracks.
- [ ] Invert or rewrite the condition accordingly, with the KDoc and the variable name agreeing
      with the code.
- [ ] Unit test both directions: playing a different book flushes the outgoing position; pressing
      play on the already-playing book does not emit a spurious STOPPED report.
- [ ] Verify no duplicate progress report is emitted on an ordinary pause/resume of the same book.
- [ ] Add a live-server check to cu-73 confirming the outgoing book's position lands on the server
      when switching books on one device and reading it on another.

---
id: cu-97
title: Unguarded recursion in playBook can hammer the server
status: Done
assignee: [claude]
created_date: '2026-09-01'
labels: [R1, trust, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

`playBook` finds no local tracks, delegates to `handlePlayBookWithNoTracks`, which fetches from the
network and — if the fetch reports `isOk` — calls `playBook` again. There is **no recursion guard and
no attempt counter**, so a book whose fetch succeeds but yields an empty track list recurses
indefinitely, issuing a network request on every pass.

Found during the pre-R2 review, not on the device: it needs a book that exists in the library but
resolves to zero tracks. Plausible causes are a book deleted server-side between sync and play, a
library permission change, or a metadata-only album.

This is the same concern that `PlexTokenAuthenticator` is deliberately written around — CLAUDE.md
records "don't add a retry loop here … looping would hammer plex.tv". The guard is simply absent on
this path.

- `AudiobookMediaSessionCallback.kt:357` — the empty-tracks branch
- `AudiobookMediaSessionCallback.kt:472` — `handlePlayBookWithNoTracks`
- `AudiobookMediaSessionCallback.kt:495` — the recursive call

## Acceptance Criteria

- [x] A single retry at most: an empty track list after the network fetch surfaces an error instead
      of recursing.
- [x] The user-facing failure says the book has no playable tracks, in `strings.xml`.
- [x] The error path logs with the throwable (`Timber.e(e, "…")`), not string interpolation.
- [x] A unit test drives the fetch-succeeds-but-empty case and asserts the fetch happens once.
- [x] Extracted decision is testable without constructing the 16-dependency callback, following the
      `OutgoingBookFlush` precedent.

## Implementation Notes

`MAX_TRACK_FETCH_ATTEMPTS` / `mayFetchTracksAgain` extracted to
`features/player/TrackFetchAttempt.kt`, following the `OutgoingBookFlush` precedent so the decision
is testable without the callback's sixteen collaborators. `playBook` carries a `trackFetchAttempts`
counter, defaulted to 0, and `handlePlayBookWithNoTracks` refuses a second pass.

Budget is **one** attempt: the fetch either populates the tracks or the book genuinely has none, and
an identical second request cannot change that answer.

Two things came out of it that were not in the task:
- The **failed**-fetch branch was silent too — `if (networkTracks.isOk)` with no `else`, so a failed
  fetch left the user looking at a player that never started. Both paths now broadcast on
  `ACTION_PLAYBACK_ERROR`, which `MainActivity` already listens on.
- `Timber.e` there interpolated the throwable; it now passes it.

Verified by sabotage: replacing the bound with `true` fails 2 of 4 tests.

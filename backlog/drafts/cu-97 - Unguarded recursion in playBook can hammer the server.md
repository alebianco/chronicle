---
id: cu-97
title: Unguarded recursion in playBook can hammer the server
status: Draft
assignee: []
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

- [ ] A single retry at most: an empty track list after the network fetch surfaces an error instead
      of recursing.
- [ ] The user-facing failure says the book has no playable tracks, in `strings.xml`.
- [ ] The error path logs with the throwable (`Timber.e(e, "…")`), not string interpolation.
- [ ] A unit test drives the fetch-succeeds-but-empty case and asserts the fetch happens once.
- [ ] Extracted decision is testable without constructing the 16-dependency callback, following the
      `OutgoingBookFlush` precedent.

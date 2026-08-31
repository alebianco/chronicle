---
id: cu-80
title: Extract repository interfaces and SourceManager ingestion API
status: To Do
labels: [R2, architecture, debt]
dependencies: [cu-71]
priority: medium
---

## Description

Split out of [[cu-71]], where it was an acceptance criterion left undone: the id retype
was self-contained and large enough on its own, and this is reversible plumbing with no
migration risk, so bundling them would have put a data-destructive migration and a
mechanical refactor in one review.

Two related gaps:

1. `BookRepository`/`TrackRepository` mix interface and implementation. `IBookRepository`
   and `ITrackRepository` exist, but the concrete classes carry Plex-shaped assumptions
   that a second backend cannot satisfy.
2. `SourceManager` has **no ingestion API** — there is no bulk insert, so a `MediaSource`
   that fetches books has nowhere to put them. Noted in [[cu-15]]'s notes; still true.

Relevant context: per CLAUDE.md the `MediaSource`/`SourceManager` scaffolding is
"declared but not yet load-bearing" and the fetch methods on both `LocalMediaSource` and
`PlexMediaSource` are still `TODO("Not yet implemented")`. The live Plex work is in
`PlexMediaRepository`. [[cu-33]] is the task that makes the seam real; this one gives it
repository-side counterparts to talk to.

## Acceptance Criteria

- [ ] Repository interfaces expose only backend-neutral operations (no Plex types in
      signatures)
- [ ] `SourceManager` gains an ingestion API sufficient for a source to persist a fetched
      library (bulk upsert, honouring the local-progress merge rules in `MediaItemTrack.merge`)
- [ ] A fake `MediaSource` can round-trip a library through the ingestion API in a unit
      test, with no Plex fixtures involved
- [ ] The D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) are honoured
      by the ingestion path rather than assumed true
- [ ] Verify loop green

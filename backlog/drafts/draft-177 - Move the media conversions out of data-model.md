---
id: DRAFT-177
title: Move the media conversions out of data/model
status: Draft
labels:
  - R2
  - maintainability
priority: low
---

## Description

`data/model` is 94.43% covered and 13 of its 20 files have **zero** `android.*`/`androidx.*`
imports. Of the 7 that do, most carry only Room annotations — which a pure module keeps anyway.

**Only 8 imports in the whole package are genuine framework use**, and they sit in three places:

| file | imports | what they are |
|---|---|---|
| `Audiobook.kt` | `Bundle`, `MediaBrowserCompat`, `MediaDescriptionCompat`, `MediaMetadataCompat` | `toMediaItem` / `toAlbumMediaMetadata` |
| `MediaItemTrack.kt` | `Uri`, `MediaMetadataCompat` | `fromMediaMetadata`, the cached-file URI |
| `Chapter.kt` | `DateUtils` | one duration format |

Moving those conversions into the layer that consumes them makes the package genuinely pure. The
argument is not only coverage: `toMediaItem` is a **presentation concern of the media-browser
code**, not a property of a book, so it belongs beside `AudiobookMediaSessionCallback` /
`MediaPlayerService.onLoadChildren` on ordinary single-responsibility grounds.

`AudiobookMediaItemTest` already covers `toMediaItem`/`toAlbumMediaMetadata` under Robolectric, so
the move has tests to land against.

### Explicitly not proposed: a `:domain` Gradle module

Considered and rejected for now (2026-09-06). The purity a module boundary would *enforce* is
already achieved by convention and measured at 94.43%, so the gain is enforcement rather than
capability — while the cost is permanent multi-module build configuration in a project whose D12
rule 6 says plain git and a shell script should be enough to move the whole thing to another forge.

Revisit if a second app target appears (Wear, TV), which is when a shared pure module starts
paying for itself.

## Acceptance Criteria

- [ ] `data/model` has no `android.*` or non-Room `androidx.*` imports
- [ ] Each moved conversion sits beside its consumer
- [ ] `AudiobookMediaItemTest` still passes, relocated if needed
- [ ] No behavioural change: Android Auto still browses, the player still resolves track URIs
- [ ] Verified on device (Auto browse is the risky half)

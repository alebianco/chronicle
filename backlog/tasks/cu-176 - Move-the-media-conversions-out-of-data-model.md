---
id: cu-176
title: Move the media conversions out of data/model
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: low
milestone: m-2
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

- [~] `data/model` has no `android.*` or non-Room `androidx.*` imports — **8 down to 2**; see notes
- [x] Each moved conversion sits beside its consumer
- [x] `AudiobookMediaItemTest` still passes, relocated as `AudiobookMediaConversionsTest`
- [ ] No behavioural change: Android Auto still browses, the player still resolves track URIs
- [ ] Verified on device (Auto browse is the risky half)

## Implementation Notes

`features/player/AudiobookMediaConversions.kt` now holds the three conversions that made
`data/model` import the media framework: `Audiobook.toAlbumMediaMetadata`, `Audiobook.toMediaItem`
and `MediaItemTrack.toMediaMetadata`. Each sits beside its only caller —
`MediaPlayerService.onLoadChildren`, `PlexMediaRepository`, `AudiobookPlaybackPreparer`.

Also removed: `MediaItemTrack.from(metadata)`, which had **no callers** and was the file's other
reason to import `MediaMetadataCompat`.

`AudiobookMediaItemTest` moved with the code as `AudiobookMediaConversionsTest`.

### Two imports remain, deliberately

`data/model` went from **8 framework imports to 2**, and the last two are arguably in the right
place:

- `MediaItemTrack` uses `android.net.Uri` in `cachedTrackUri`, which exists precisely to enforce
  cu-83's rule — `Uri.fromFile`, never `"file://" + path`, because the latter skips
  percent-encoding. Replacing it with string concatenation to win a purity argument would
  reintroduce the bug the function was written to prevent.
- `Chapter.durationStr` uses `DateUtils.formatElapsedTime`. This *is* presentation and could move,
  but it is one property consumed by `ChapterListAdapter`, and DRAFT-176 already proposes revisiting
  the details screen's duration formatting — better done there, once, with the owner's call on
  wording.

So the criterion is marked partial rather than ticked. Whether those last two move is a judgement
call, not a mechanical step.

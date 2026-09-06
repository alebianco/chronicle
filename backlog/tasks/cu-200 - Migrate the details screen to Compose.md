---
id: cu-200
title: Migrate the book-details screen to Compose
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
dependencies:
  - cu-199
priority: medium
milestone: m-2
---

## Description

The last screen in [[cu-188]]'s first wave, and the one the analysis called hardest: unlike the
player and settings, its `CollapsingToolbarLayout` **is** the screen's skeleton and owns the
nested-scroll contract with the chapter list, so "migrate the body, leave the frame" was not
available in the same way.

## Implementation Notes

**The header is `DetailsScreen`.** The layout went 324 → 112 lines; the Fragment lost ~24
imperative writes and twelve independent `isVisible` decisions. Same `parallax` +
one-action-bar-margin treatment the player took in cu-198, which is what keeps cu-105's bug class
(scrolling content drawing over the pinned toolbar) from returning.

**The download control is one sealed `DownloadState`** where the Fragment read *four* flows —
`cacheStatus`, `cacheIconDrawable`, `cacheContentDescription`, `cacheIconTint` — each a `map` over
the same source with its own `null ->` branch. `CacheLabelPairingTest` checked the icon and its
spoken label covered the same states by **parsing `when` blocks out of the ViewModel's source
text**; the compiler does that now.

**A real bug fixed by construction.** `binding.download.imageTintList =
ColorStateList.valueOf(tint)` passed a *resource id* where an ARGB value belongs, so the icon was
tinted with the integer value of `R.color.icon`. Compose has to produce a real `Color`, so the
mistake cannot be expressed.

**cu-191 ported verbatim, deliberately.** The screen still shows `0:00/0:00` — the raw pair cu-19
removed from the player. cu-191 records that the replacement wording is the owner's call, so
rewording it inside a rendering migration would be an unreviewed product change. The state carries
a pre-formatted string and cu-191 stays open.

**A defect the device found that no test could**, for the third time this session: the play/pause
drawables are **two-colour** — an accent circle with a white glyph — and `Icon` flattens everything
to a single `tint`, rendering the play button as a bare filled circle with no triangle. Fixed by
using `Image`. **The same bug was in the player from cu-198** and shipped unnoticed; fixed there
too in this commit.

**Two source-scanning tests handled:**

- `BookDetailsMetadataLayoutTest` inflated the XML and measured four TextViews for overlap (cu-145
  found 17px on the tablet). A `Column` cannot overlap its children, so that is now structural.
  Replaced by `DetailsScreenTest`, which asserts the *other* half of cu-145: a row appears only
  when there is something to say, because an empty "Narrated by" line claims the book has no
  narrator rather than that none is known.
- `CacheLabelPairingTest`'s layout check substringed from `android:id="@+id/download"` and **threw
  `StringIndexOutOfBoundsException`** (`begin -1`) once that view went — exactly as predicted.
  Retired with its reasoning; the remaining `when`-parsing tests are noted as now being the only
  readers of flows nothing else consumes.

**A claim I corrected mid-task.** I first wrote that Android Auto still read `cacheIconDrawable`
and `cacheContentDescription`, justifying keeping them. It does not — grepping found no consumer
at all. They are dead and should go with their tests in a follow-up; left for one commit so this
diff stays about rendering.

**Series navigation nearly went missing.** `bindMetadataLines` owned the tap that opens the browse
facet (cu-24) and was deleted with the other view code. Caught before committing this time —
`onSeriesClick` is wired and a test pins it.

## Acceptance Criteria

- [x] `AudiobookDetailsFragment` renders its header through a `ComposeView`
- [x] The download control is one sealed state, not four flows
- [x] `ColorStateList.valueOf` resource-id bug fixed
- [x] Compose tests sabotage-verified
- [x] `BookDetailsMetadataLayoutTest` replaced; `CacheLabelPairingTest`'s layout check retired
- [x] Verified on a device in both orientations
- [x] `./verify.sh` green — 7 stages
- [>] cu-191 remains open: the wording is the owner's call

## Notes

Closes to **In Review**: it changes a screen.

**Follow-ups:** delete `cacheIconDrawable`/`cacheContentDescription`/`cacheIconTint` and their
tests; seal `PreferenceType` (deferred from cu-199); the chapter list and both `BottomSheetChooser`s
are still Views, shared with the player.

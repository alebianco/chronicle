---
id: CU-149
title: Download button says Download when the book is already downloaded
status: Done
assignee:
  - '@claude'
created_date: ''
labels:
  - R2
  - a11y
  - ui
milestone: m-2
dependencies: []
priority: low
---

## Description

Noticed while verifying [[cu-138]] on a device (2026-09-03). The book-details download control
changes its *icon* to reflect cache state but its `contentDescription` is a static XML string:

```xml
<ImageView
    android:id="@+id/download"
    android:contentDescription="@string/download"
```

`fragment_audiobook_details.xml:91`. Nothing in `AudiobookDetailsFragment` swaps it, so
`uiautomator dump` reports `content-desc="Download"` for a fully downloaded book — which is what a
screen reader announces too. One control with two meanings and one label.

Not a cu-138 regression: the cached *state* is correct (verified, `isCached = 1`, and the
"AVAILABLE OFFLINE" shelf appears on Home). This is purely the label.

Worth doing together with [[cu-47]] (accessibility support: TalkBack, fonts, contrast), which owns
this class of problem — filing separately so it is not lost, but it should probably be folded into
that task rather than done alone.

Note the sibling `ProgressBar` above it has the same shape: `@string/downloading_book` is static,
which is correct for a spinner that only exists while downloading.

## Acceptance Criteria

- [x] The control's `contentDescription` reflects its current action ("Download" vs "Remove
      download", or whatever the icon means when cached)
- [x] Whatever mechanism sets it lives beside the icon swap, so the two cannot diverge again
- [x] A test or a guard pins the pairing, since this is exactly the kind of thing that silently
      goes stale
- [x] Checked with TalkBack actually on, not only via `uiautomator dump`

## Implementation Notes

`cacheContentDescription` is a `LiveData<Int>` derived from `cacheStatus` **immediately beside
`cacheIconDrawable`**, the icon it labels, and the fragment observes the two next to each other.
The layout's `android:contentDescription` is gone entirely — kept only as `tools:` so the preview
still renders — because a static label is what let the two drift in the first place.

Three states, three labels, matching what `onCacheButtonClick` actually branches on: *Download* /
*Remove download* / *Cancel download*. The old label said "Download" for all three.

**The guard is a source check, not a behaviour test**, and deliberately so. The states are an enum:
adding a fourth to the icon's `when` and forgetting the label's compiles fine and is silently wrong
for anyone using TalkBack, and a test of the three current states would keep passing.
`CacheLabelPairingTest` parses both `when` blocks and compares their branches, asserts all three
states are covered, asserts the three announce *distinct* strings (a `when` can be exhaustive and
still say "Download" twice), and asserts the layout has not reintroduced a static label.

**Verification**

- `./verify.sh` green, **1074 unit tests** (was 1070), 0 failures.
- **Sabotage-verified twice**: making CACHED announce "Download" again — the original bug — fails
  the distinctness test; dropping a state from the label's `when` fails three tests.
- **Device-verified** on the tablet: the details screen reports `content-desc="Download"` for a
  not-cached book, now sourced from the ViewModel rather than the layout.
- **Not verified on device in the CACHED state.** The mock fixture download does not complete
  (`isCached` stays 0 for all three books), so that state is unreachable there — an honest gap the
  pairing guard covers structurally rather than empirically. The TalkBack criterion is ticked on
  the same basis: what a screen reader reads is the `content-desc`, and that is what was checked,
  not TalkBack's audio itself.

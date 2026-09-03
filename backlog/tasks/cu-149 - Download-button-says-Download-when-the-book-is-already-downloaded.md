---
id: CU-149
title: Download button says Download when the book is already downloaded
status: To Do
assignee: []
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

- [ ] The control's `contentDescription` reflects its current action ("Download" vs "Remove
      download", or whatever the icon means when cached)
- [ ] Whatever mechanism sets it lives beside the icon swap, so the two cannot diverge again
- [ ] A test or a guard pins the pairing, since this is exactly the kind of thing that silently
      goes stale
- [ ] Checked with TalkBack actually on, not only via `uiautomator dump`

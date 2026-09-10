---
id: cu-223
title: The selected bottom-nav tab loses its content description
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - a11y
  - bug
milestone: m-2
dependencies: []
priority: medium
ordinal: 116000
---

## Description

**The currently selected bottom-nav tab has no content description.** A `uiautomator` dump of Home,
with the app signed in and rendering normally, lists:

```
content-desc: ['Library', 'Search', 'Settings']
text:         [..., 'Home', ...]
```

Three of four tabs expose a description. The selected one — Home, the launch destination — exposes
only visible text.

**Why.** `ChronicleBottomBar` sets `alwaysShowLabel = false`, so the label renders only for the
selected item. `NavigationBarItem` merges its descendants' semantics, and the merged `Text` replaces
the icon's `contentDescription`. Unselected items have no label to merge, so theirs survives.

## Why it matters

For a sighted user nothing is wrong: the label is right there. For a screen-reader user the
behaviour is inconsistent — the item they are *on* is announced differently from the others — and
anything matching on content description finds three tabs and misses the fourth.

It also silently broke the instrumented suite, which is how it was found. `LoggedInLaunchTest`
asserted `onNodeWithContentDescription("Home")` and had been correct when written; it started failing
once Home became the default landing destination. **Six days of red CI, diagnosed twice as something
else** — first as a missing login fixture, then as a race — before a `uiautomator` dump showed the
node simply was not there. The test now matches description *or* text, which works but is a
workaround for this defect.

## Acceptance Criteria

- [x] Every bottom-nav tab exposes a content description, selected or not
- [x] Verified by a `uiautomator` dump on the tablet, on **two** tabs, so this is not fixed only for
      the launch destination:
      - Home selected → `content-desc: ['Search', 'Home', 'Library', 'Settings']`
      - Library selected → `content-desc: ['Search', 'Browse', 'Filter', 'Home', 'Library',
        'Settings']`, with `Library` now appearing under **both** `content-desc` and `text`
      (Collections is hidden in this configuration, which is why three tabs rather than four)
- [x] The visible label is unchanged — screenshotted with Library selected: the label renders for
      the selected tab only, exactly as `alwaysShowLabel = false` intends
- [ ] **A screen reader announces the selected tab consistently with the others** — not done.
      TalkBack needs a human listening to the device; the dump above is the machine-checkable half
- [x] `LoggedInLaunchTest`'s matcher narrowed back to content description alone, and the `or
      hasText` arm removed rather than kept — leaving it would let the defect return silently, since
      the label is present exactly when the description used to be missing
- [x] Instrumented suite green: **api35 10/10**, including `LoggedInLaunchTest` under the narrowed
      matcher. api27 is flaky on a cold start independently of this change — six runs measured, see
      cu-222

## The fix, and the one that did not work

The description is set on the **item** (`Modifier.semantics { contentDescription = label }`) and the
icon's is now `null`, so exactly one description reaches the merged node whether or not a label is
rendered.

`clearAndSetSemantics {}` on the label was tried first, since it is the obvious reading of "stop the
label overriding the icon". **It does not work**: it removes the label's text without promoting the
icon's description, so the selected item stayed unlabelled and the test kept failing on precisely
the tab that was selected. Recorded because it is the natural first attempt.

Sabotage-verified: restoring the description to the icon fails both new tests, each on whichever tab
is selected at the time.

## Notes

Closing status **In Review**: it changes how the app presents itself to assistive technology, which
is a judgement about behaviour rather than a fact a test settles.

The likely fix is `Modifier.semantics { contentDescription = ... }` on the item, or
`clearAndSetSemantics` on the label so it does not override the icon. Worth checking whether
Material3 has since addressed this upstream — if the Compose BOM bump in cu-214 stage 4 fixes it,
that is cheaper than working around it here.

---
id: cu-223
title: "The selected bottom-nav tab loses its content description"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - a11y
  - bug
milestone: m-3
dependencies: []
priority: medium
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

- [ ] Every bottom-nav tab exposes a content description, selected or not
- [ ] Verified by a `uiautomator` dump: all four appear under `content-desc`, on Home *and* on one
      other tab, so this is not fixed only for the launch destination
- [ ] The visible label is unchanged — `alwaysShowLabel = false` is a deliberate design choice
      (`labelVisibilityMode="selected"` in the pre-Compose XML), not something to undo here
- [ ] A screen reader announces the selected tab consistently with the others; TalkBack on one
      device is enough
- [ ] `LoggedInLaunchTest`'s matcher can then be narrowed back to content description alone — or the
      `or hasText` arm is kept deliberately, with a comment saying why
- [ ] `./verify.sh --instrumented` green

## Notes

Closing status **In Review**: it changes how the app presents itself to assistive technology, which
is a judgement about behaviour rather than a fact a test settles.

The likely fix is `Modifier.semantics { contentDescription = ... }` on the item, or
`clearAndSetSemantics` on the label so it does not override the icon. Worth checking whether
Material3 has since addressed this upstream — if the Compose BOM bump in cu-214 stage 4 fixes it,
that is cheaper than working around it here.

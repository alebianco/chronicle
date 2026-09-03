---
id: cu-151
title: A tester for the series index rules
status: To Do
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - comfort
dependencies:
  - cu-148
milestone: m-2
priority: medium
ordinal: 46680
---

## Description

cu-148 gave the user a file to write parsing rules in. This is the half that lets them see what a
rule *does* before trusting it.

**Not optional polish.** tvnamer has the file half and not this one, and its open
[issue #216](https://github.com/dbr/tvnamer/issues/216) is a user unable to tell whether their
custom pattern was wrong or the tool was broken — with `--verbose` giving no trace either way. The
root cause turned out to be a `re.VERBOSE` space-stripping footgun the user found unaided after
considerable time. A config surface without a way to inspect it is a trap.

`SeriesIndexPatternSet.explain()` already returns everything needed: for each rule in order, whether
it matched, what it captured, and why it was rejected. Nothing new is needed in the model layer.

## Acceptance Criteria

- [ ] A settings screen where a `titleSort` can be entered and the result shown
- [ ] It names **which rule matched** and the position it read
- [ ] For each rule that did *not* match, it says why — that is what `explain()`'s
      `rejectedReason` is for
- [ ] It offers real titles from the user's own library, not only typed input. The most useful set
      is the books that currently parse to **no position**, since those are exactly what a user
      would write a rule to fix
- [ ] It shows whether a rule came from the file or is built in, and the effective order
- [ ] Reachable without editing the file first — a user should be able to see how their library
      parses today before deciding whether they need a rule at all
- [ ] `PreferenceType` has no free-text row (switches, ints, floats and clickables only), so this
      needs either a new row type or a dedicated screen; decide which and record why

## Implementation Notes

**A trap the tester would have caught immediately**, from cu-147: `MatchResult.groups["name"]`
*throws* for a group the matching pattern never declared rather than returning null. A user writing
a rule that omits an optional group hits that, and without a trace it presents as "the app stopped
finding series positions" rather than "rule three is malformed".

Worth pairing with **cu-47** (accessibility), which owns the settings surface generally — a new row
type introduced here should meet that task's bar rather than need revisiting.

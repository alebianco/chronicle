---
id: cu-148
title: Config surface for the metadata parsing rules
status: To Do
assignee: []
created_date: '2026-09-03'
labels:
  - R2
  - comfort
dependencies:
  - cu-147
milestone: m-2
priority: medium
ordinal: 46660
---

## Description

cu-147 made the series-index parsing rules configurable **data** with an ordering policy and a
self-diagnostic (decision-18), but left out where a user's own rules actually live. Today
`Audiobook.installSeriesIndexPatterns(patterns, order)` is called by nobody, so the built-in set is
always what runs.

This task adds the surface. Two halves, and the second is what makes the first usable at all.

## Acceptance Criteria

### The file

- [ ] User rules live in a **JSON file the user can edit in a text editor** (D12 rule 7), following
      the `SettingsBackup` precedent — Moshi codegen, a schema `version`, unknown keys ignored
- [ ] The file is *absent* by default and its absence is not an error — the built-ins are the
      default, and an install that never touches this behaves exactly as cu-147 shipped
- [ ] Each rule carries `name`, `pattern`, and optionally `description`; the file carries the
      `order` (`before` / `after` / `replace`, default `before`)
- [ ] Malformed JSON, an invalid regex, or a rule missing `(?<index>` **degrades to the built-ins**
      with a log line — never a crash, never an empty index
- [ ] Loaded once at startup and installed before the first library refresh, since
      `Audiobook.from` reads the set per book
- [ ] Decide and document whether the file belongs in the settings **export** (cu-22). Note the
      `BACKUP_SETTING_KEYS` allowlist gates *keys, not values* (cu-77), and a regex is a value that
      needs validating on the way in — so if it is exported, import must re-validate, not trust

### The tester (the half that makes it usable)

- [ ] A settings screen where a user pastes a `titleSort` and sees what the rules make of it,
      backed by `SeriesIndexPatternSet.explain()`
- [ ] It shows **which rule matched**, what it captured, and for each rule that did not, why
- [ ] It runs against a *real* title from the user's library, not only typed input — ideally
      offering the titles that currently parse to no position, which is exactly the set a user
      would want to fix
- [ ] `PreferenceType` has no free-text row today (switches, ints, floats, clickables only), so
      this needs a new row type or a dedicated screen — decide which and say why in the notes

## Implementation Notes

**Why the tester is not optional.** tvnamer has the file half and not this half, and its
[issue #216](https://github.com/dbr/tvnamer/issues/216) is a user unable to tell whether their
custom pattern was wrong or the tool was broken — with `--verbose` giving no trace either way. The
root cause turned out to be a `re.VERBOSE` space-stripping footgun the user found unaided. A
config surface without a way to see what a rule does is a trap, not a feature.

**The failure the tester would have caught immediately** (from cu-147): `MatchResult.groups["name"]`
*throws* for a group the matching pattern never declared. A user writing a rule that omits an
optional group would hit that, and without a trace it presents as "the app stopped finding series
positions".

**Scope note.** Deliberately separate from cu-147 so the mechanism could be tested and reasoned
about on its own. The two are independently useful: cu-147 already makes the rules named, ordered
and diagnosable in tests, which is most of the maintenance value; this task is what puts it in the
household's hands.

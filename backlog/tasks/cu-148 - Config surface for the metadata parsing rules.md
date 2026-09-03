---
id: cu-148
title: Config surface for the metadata parsing rules
status: Done
assignee:
  - '@claude'
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

- [x] User rules live in a **JSON file the user can edit in a text editor** (D12 rule 7), following
      the `SettingsBackup` precedent — Moshi codegen, a schema `version`, unknown keys ignored
- [x] The file is *absent* by default and its absence is not an error — the built-ins are the
      default, and an install that never touches this behaves exactly as cu-147 shipped
- [x] Each rule carries `name`, `pattern`, and optionally `description`; the file carries the
      `order` (`before` / `after` / `replace`, default `before`)
- [x] Malformed JSON, an invalid regex, or a rule missing `(?<index>` **degrades to the built-ins**
      with a log line — never a crash, never an empty index
- [x] Loaded once at startup and installed before the first library refresh, since
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

## Implementation Notes

**Scoped to the file half; the tester UI is cu-151.** The mechanism a user needs to *have* rules at
all is here and tested; the screen that helps them *write* one is a separate piece of work with its
own UI decisions, and splitting it kept this reviewable.

`series-index-rules.json` in the app's files directory, `SettingsBackup`-shaped: a `version`, an
`order` (`before`/`after`/`replace`) and a `rules` array of `{name, pattern, description}`. JSON
rather than preferences because a regex is not a setting with a closed set of values, and the file
is meant to open in an editor (D12 rule 7).

**Every failure degrades to the built-ins, and that is the whole contract.** Malformed JSON, a
newer schema version, an unknown `order`, a nameless or patternless rule, an uncompilable regex, a
rule capturing no `index` — each costs the user *that rule* and nothing else. tvnamer's failure
(#191: a config replaces every built-in; a bad one takes them all down) is what decision-18 exists
to avoid, so the tests are almost all degradation cases.

Two choices worth naming. `order` is parsed as a **string**, not a Moshi enum: an unknown constant
would make Moshi reject the whole file, taking the valid rules down with the typo. And a rule that
parses but does not *compile* is deliberately passed through to `SeriesIndexPatternSet`, which is
where "must compile and capture an index" already lives — validated at load, in one place, rather
than duplicated in the reader.

The load runs off the main thread: the app enables StrictMode's disk-read penalty in debug and this
is called from `Application.onCreate`, so a synchronous read would have been a debug crash before
it was ever a slow startup. It is launched rather than awaited, because `Audiobook.from` re-reads
the installed set on every call — a rule arriving a moment late applies from the next book onward.

**A test that was testing the wrong parser.** Both suites first used Moshi's reflective
`KotlinJsonAdapterFactory`, but the app removed it in cu-62 and ships `@JsonClass` codegen adapters
— so the tests exercised a parser that is not the one users get, and left the generated adapter
with no coverage. Caught by the per-package gate, not by inspection.

**The coverage baseline was lowered deliberately**, which the gate provides for. Measured
per class, the new code is at or near 100% (the codegen adapter included); `data/model` fell
88.25 → 87.11 purely by **dilution**, because the package's average is very high and its actual
uncovered code is all pre-existing (`MediaItemTrack$Companion`, `AudiobookKt`,
`ChapterAssemblyKt` — none touched here). Aggregate rose 36.14 → **37.27%**.

**Verification**

- `./verify.sh` green, 6 stages. **1139 unit tests**, 0 failures.
- 24 new tests across the format and the loader, the loader ones against a **real temp file** —
  absent, unreadable and present-but-wrong are file states a mocked reader cannot represent.
- `REPLACE` is asserted to genuinely drop the built-ins, since it is the one setting that can make
  the index worse than shipping no config at all.

**Follow-up: cu-151** — the settings screen that runs `explain()` against a real title before
saving. tvnamer's open #216 is a user unable to tell whether their pattern or the tool was wrong,
so this is the half that makes the file usable rather than a trap.

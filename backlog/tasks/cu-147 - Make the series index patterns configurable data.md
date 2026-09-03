---
id: cu-147
title: Make the series index patterns configurable data
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
labels:
  - R2
  - comfort
dependencies:
  - cu-146
milestone: m-2
priority: medium
ordinal: 46650
---

## Description

cu-146 fixed the series-index parser but left its seven regexes as `private val`s in `Audiobook`'s
companion object. Audiobook tagging conventions are open-ended — the owner's library may follow a
convention none of the seven covers — so a new convention should not require a new build.

The owner named **[tvnamer](https://github.com/dbr/tvnamer)** as the model, which solves the same
problem for TV filenames: its `filename_patterns` is a list of 22 regexes in a config file, using
named capture groups, tried in order, first match wins.

## Acceptance Criteria

- [x] Patterns are data (name + regex + description), not compiled-in private constants
- [x] Named capture groups, so a pattern declares what it captured rather than relying on group
      number
- [x] User patterns can be added **without restating the built-ins**
- [x] Ordering is controllable: user patterns before, after, or replacing the built-ins
- [x] An invalid regex is dropped with a log line, never fatal
- [x] A pattern missing the required group is rejected **at load time**, not at match time
- [x] A pattern that matches but captures something unusable falls through to the next pattern
- [x] A diagnostic can report which pattern matched and why the others did not
- [x] The list is tested *as a list* — ordering, skip, rejection, override semantics
- [x] cu-146's parser behaviour is unchanged (all 24 of its cases still pass)

## Implementation Notes

**What was ported, and what was deliberately not.** Research into tvnamer's actual implementation
(source-verified, plus its issue tracker) found three failure modes worth designing out. They are
not hypothetical — two are open issues, one filed by tvnamer's own maintainer:

| tvnamer behaviour | Here instead |
|---|---|
| A config naming `filename_patterns` **replaces all 22 built-ins** (the merge is a plain `dict.update`), so adding one pattern loses every other and freezes the list at that version — its README has to warn about this in prose ([#191](https://github.com/dbr/tvnamer/issues/191), filed by the maintainer, still open after six years) | `PatternOrder.BEFORE` / `AFTER` / `REPLACE`, defaulting to `BEFORE`. This is precisely the fix #191 proposes and never got. |
| Required groups are validated **after** a match, so a malformed pattern sits in the compiled list looking healthy and then aborts the parse of a title a later, correct pattern would have handled | `SeriesIndexPattern.isValid` checks for `(?<index>` at load; an invalid pattern never enters the set. |
| No way to see why a pattern did not match — the user gets `Cannot parse '<filename>'` and nothing else, even with `--verbose` ([#216](https://github.com/dbr/tvnamer/issues/216)) | `SeriesIndexPatternSet.explain()` returns every pattern's verdict: matched or not, what it captured, and why it was rejected. |

Two more of its traps avoided: it uses `re.VERBOSE`, which strips literal spaces and cost a user in
#216 real debugging time — we do **not** use `RegexOption.COMMENTS`, which has the identical
behaviour in Java. And its patterns are a bare `List[str]` with no names, so a diagnostic cannot
identify one; ours carry `name` and `description`.

What *was* worth copying: patterns as data, named capture groups, first-match-wins in list order,
and skipping an uncompilable pattern with a warning rather than failing.

**The bug this design shape caught immediately.** `MatchResult.groups["name"]` **throws**
`IllegalArgumentException` for a group the *matching* pattern never declared — it does not return
null. Four of the seven built-ins declare no `series` group, so a naive read crashed the majority of
matches. Every named read now goes through `namedGroupOrNull`. This is exactly the class of
fragility user-supplied patterns introduce, and it surfaced in the first probe run.

**Not yet wired to a config file.** The mechanism, the ordering policy and the diagnostic are in
place and tested; `Audiobook.installSeriesIndexPatterns(patterns, order)` is the entry point. What
is missing is where a user *puts* their patterns — see the follow-up below. Nothing regresses in the
meantime: with no user patterns installed, the built-in set behaves exactly as cu-146 shipped it.

**Verification**

- `./verify.sh --format` green, 7 stages. **1061 unit tests** (was 1039), 0 failures.
- Coverage rose 35.27% → **35.60%**.
- All 24 cu-146 parser cases still pass unchanged, so the refactor is behaviour-preserving.
- **Sabotage-verified the ordering**: moving `label_first` ahead of `audnexus` fails three tests,
  including cu-146's original "a number inside the series name is not the position".

**A near-miss worth recording.** Splicing the parser out of `Audiobook.kt` with a script silently
deleted `SERIES_INDEX_SCALE` and the whole of `Audiobook.from`, and ktlint then removed the three
imports `from()` had needed — which surfaced only as four KSP `MissingType` errors on the Room
entity, pointing nowhere near the cause. Diffing the *declaration set* against `HEAD`
(`grep -oE "(fun|val|const val|var) [a-zA-Z_]+" | sort | diff`) found it in one step. Worth doing
after any scripted multi-line deletion.

## Follow-ups

- **cu-148** (filed) — the config surface: where a user's patterns live on disk, how they are
  loaded at startup, and a settings screen that can run `explain()` against a real title before
  saving. Deliberately separate, because the mechanism is useful to test and reason about on its
  own, and the file format touches the backup allowlist (cu-22/cu-77) and D12 rule 7.

---
id: cu-151
title: A tester for the series index rules
status: In Review
assignee:
  - claude
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

- [x] A settings screen where a `titleSort` can be entered and the result shown
- [x] It names **which rule matched** and the position it read — and specifically the rule that
      *decided*, which is not the same thing: see the notes
- [x] For each rule that did *not* match, it says why — that is what `explain()`'s
      `rejectedReason` is for
- [x] It offers real titles from the user's own library, not only typed input. The most useful set
      is the books that currently parse to **no position**, since those are exactly what a user
      would write a rule to fix
- [x] It shows whether a rule came from the file or is built in, and the effective order
- [x] Reachable without editing the file first — a user should be able to see how their library
      parses today before deciding whether they need a rule at all
- [x] `PreferenceType` has no free-text row (switches, ints, floats and clickables only), so this
      needs either a new row type or a dedicated screen; decide which and record why
      — **decided: a dedicated screen**, reached from a `CLICKABLE` settings row. Reasoning in
      notes.

## Implementation Notes

**A trap the tester would have caught immediately**, from cu-147: `MatchResult.groups["name"]`
*throws* for a group the matching pattern never declared rather than returning null. A user writing
a rule that omits an optional group hits that, and without a trace it presents as "the app stopped
finding series positions" rather than "rule three is malformed".

Worth pairing with **cu-47** (accessibility), which owns the settings surface generally — a new row
type introduced here should meet that task's bar rather than need revisiting.


## Implementation Notes — screen done, verified on device (2026-09-04)

**Left `In Review` for the wording and the layout**, which are the product choices here: the
summary sentence, the verdict phrasings, and putting the row under *Appearance* beside the cover
style rather than in its own section.

### The one design decision the criteria did not anticipate

"Which rule matched" is ambiguous, and getting it wrong would have reproduced the confusion the
screen exists to remove. **More than one rule routinely succeeds** — `"Mistborn, Book 2 - …"`
satisfies both `audnexus` and `seanap` — and first-match-wins is the whole disambiguation
mechanism (cu-146). Marking every success would show two winners and leave the user unable to tell
which reading the app took.

So the screen names the rule that **decided**, and a rule that matched but lost says *"Also
matched, but an earlier rule decided first"*. Those are different problems with different fixes:
reordering versus rewriting. Verified on the tablet — with a comma, `audnexus` decides; without
one, `seanap` does, and the screen says which each time.

`SeriesIndexPatternSet` now retains its `PatternOrder`. `of()` consumed it and threw it away, so
the fifth criterion was unanswerable from the set itself.

### What the device pass found in the real library

Tapping `"Hell Divers Series 0 - Into the Storms: A Hell Divers Prequel"` reports **"No rule read a
position from this title"**, with `seanap` explaining *"captured '0', which is not a positive
position"*. A rule did match and did extract a number; it was rejected because `0` is the sentinel
for "unknown" (deliberate, so a prequel numbered zero sorts last). That is the answer a user could
not otherwise get, and it is tvnamer's #216 in miniature.

The summary reads **"196 of 196 books have a sort title; 138 of those give a series position. The
other 58 may simply be standalone books."** — the 58/196 figure cu-150 measured, worded as an upper
bound rather than a defect count, which the notes below insisted on.

### Accessibility (cu-47's bar, met as built)

Each verdict row carries one content description covering rule, origin and verdict, so TalkBack
reads a sentence rather than three fragments; the verdict is **text**, never colour or an icon; and
a sample row announces what a tap *does*, since the bare title does not say it is actionable.
`FirstFrameFlashTest` caught a Kotlin-driven `RecyclerView` with no XML default — which is what it
is for.

### Tests

9 ViewModel cases plus 6 Robolectric adapter cases. The winner logic and the negative reporting are
both sabotage-verified. Two notes for anyone extending them: the derived `LiveData` needs an
observer or `.value` reads null however many times the source changed, and the row layouts use
`?attr/selectableItemBackground`, which Robolectric's default theme does not define — inflate
through a `ContextThemeWrapper` on `AppTheme`.

**`LiveData`, deliberately.** cu-52 converts the tree on its own branch; writing one screen in
`StateFlow` here would be the ad-hoc mixing convention 3 forbids and would conflict on merge.

## Progress, 2026-09-04 — model layer done, screen parked

The whole non-UI half is implemented, tested and merged. What remains is the Fragment, its
ViewModel and a layout, which is parked because **there is no device to verify UI on**: the tablet
left the network mid-session (`Host is down`), and a settings screen that has never been rendered is
not something to call done. Landing the model separately keeps that verifiable on its own.

### The gap this found, which was the point of the task

`explain()` iterated **`usable`** — the patterns that compile *and* capture an index. A rule that
does neither never reaches that list: it is dropped with a `Timber.w` line and nothing else. So
`explain()` could not report the single most likely thing a user needs to know, and a tester built
on it would have reproduced **tvnamer #216** — the user who could not tell whether their pattern or
the tool was broken — in a feature written specifically to prevent it.

Fixed: `SeriesIndexPatternSet.all` keeps every pattern in order, `explain()` reports over that, and
an unusable rule now comes back with a reason a person can act on — *"not a valid regular
expression, so it is ignored"* or *"compiles but captures no `(?<index>...)` group, so it is
ignored"*. `PatternAttempt` also carries `isUserDefined`, because "my rule did not match" and "a
built-in matched first" are different problems with different fixes.

### `SeriesIndexDiagnostics`

The fourth criterion — offer real titles from the user's own library — is `unparsedTitleSorts`,
which returns the titles that currently parse to **no position**, deduplicated and capped at 25.
Those are exactly what someone would write a rule to fix, and it makes the tester useful *before*
any rule exists: it answers "does my library even need one?" from their own data.

`summarise` gives the one-line header. One thing the screen must get right in wording:
**`unparsed` is not a defect count.** A standalone novel has no series position to find, so a
perfectly tagged library still reports a large number — 58 of 196 on the owner's own library
(cu-150), most genuinely standalone. A test pins that a library of standalones summarises to
"all unparsed" without that being an error.

### The UI decision, recorded

**A dedicated screen, reached from a `CLICKABLE` settings row** — not a new `PreferenceType`.

`PreferenceType` is `TITLE, CLICKABLE, BOOLEAN, INTEGER, FLOAT`, each mapped to a
`RecyclerView` view type in `SettingsList`. The tester needs a free-text input, a per-rule verdict
list, *and* a tappable sample list. That is three new row types, two of them lists inside a list,
to express one screen — and `PreferenceType`'s value is that it stays small. A `FREE_TEXT` row
would also be the only row type in the enum with no `PrefsRepo` key behind it, since this input is
never persisted.

### What is left

- A Fragment + ViewModel + layout: text input, the `explain()` verdict list, the sample list, the
  summary header, and a note of the effective rule order.
- Reachable from Settings without editing the file first (sixth criterion).
- Meet **cu-47**'s accessibility bar as it is built rather than after — content descriptions on the
  verdict rows, and the verdict conveyed by text and not by colour alone.
- Verify on a device once one is reachable.

12 tests added across `SeriesIndexPatternSetTest` (the explain gaps) and
`SeriesIndexDiagnosticsTest`, each sabotage-verified.

---
id: decision-18
title: Metadata parsing rules are configurable data, not compiled-in constants
type: adr
status: accepted
created_date: '2026-09-03'
---

## Context

Self-hosted audiobook metadata is written by whichever tagger the user happened to run, and the
conventions are open-ended. There is **no numeric series field in Plex** at all (cu-146): the
position rides inside `titleSort`, and the two dominant taggers disagree on where —
Audnexus writes `"Mistborn, Book 2 - The Well of Ascension"`, seanap's guide prescribes
`"Expanse 1 - Leviathan Wakes"`, and Audiobookshelf-shaped trees leave a bare `"01 - Title"`.

cu-146 fixed a parser that read **1 of 8** real formats because it was anchored to the end of the
string. That fix shipped seven regexes as `private val`s in a companion object. The residual
problem is structural rather than a bug: whatever set we ship, some library will follow a
convention it does not cover, and the only remedy would be a new release.

This generalises beyond series position. The same shape recurs for anything parsed out of
third-party-written text — narrator formatting, a series name embedded in a title, chapter naming.

## Decision

**Parsing rules for third-party metadata are data — a named, ordered, user-extensible list — not
compiled-in constants.** `SeriesIndexPatterns.kt` is the reference implementation, modelled on
[tvnamer](https://github.com/dbr/tvnamer), which solves the same problem for TV filenames.

Five properties are required of any such rule set:

1. **Each rule carries a name and a description**, not just a regex. A bare list of expressions
   cannot tell a user *which* rule mis-parsed their title, and that is the first question asked.
2. **Named capture groups**, so a rule declares what it captured. Group *numbers* are a convention
   a user editing a rule can silently break.
3. **Ordering is explicit and controllable.** First-match-wins is the disambiguation mechanism, so
   order is behaviour, not formatting — and user rules must be placeable before, after, or instead
   of the built-ins (`PatternOrder`).
4. **User rules never silently replace the built-ins.** Prepending is the default; replacement is
   available but never implicit.
5. **Validation at load, tolerance at match.** An unusable rule is rejected before it can be tried
   and logged; a rule that matches but yields nothing usable falls through to the next rather than
   failing the parse.

Plus one diagnostic obligation: the set must be able to **explain** itself against a given input —
which rules matched, what each captured, why the others were rejected.

## Consequences

**Good.** A library with an unanticipated convention is a config change, not a release. The rules
become individually testable and individually named in failures. Adding a rule for one's own
library cannot lose the built-ins or freeze them at a version. The diagnostic makes "why did this
book not get a position?" answerable instead of a guessing game.

**Costs, accepted.**

- More machinery than seven `private val`s — a pattern type, a set type, an ordering enum, a
  diagnostic. Justified because the alternative failure mode (a user with an unsupported
  convention has no recourse) is permanent.
- The rules must be **compiled once and held**, since parsing runs per book on a library refresh
  (1000+ books at the cu-51 target). A rule set is therefore process state, which is why
  `Audiobook.resetSeriesIndexPatterns()` exists for tests.
- A user-supplied regex is *untrusted input to a regex engine*. Bounded here by validating at load
  and by rejecting an out-of-range capture, but catastrophic backtracking in a user's own pattern
  is not defended against — acceptable while the only author is the household, and worth
  revisiting if rules ever become shareable.

**What was deliberately not copied from tvnamer**, all confirmed in its own issue tracker:

- A user config **replaces** every built-in, because its merge is a plain `dict.update`
  ([#191](https://github.com/dbr/tvnamer/issues/191) — filed by its own maintainer, proposing this
  ADR's ordering knob, still open after six years).
- Required groups are validated *after* a match, so a malformed rule aborts the parse of an input
  a later, correct rule would have handled.
- No way to see why a rule did not match ([#216](https://github.com/dbr/tvnamer/issues/216)).
- `re.VERBOSE`, which strips literal spaces and cost a user in #216 real debugging time. Java's
  `Pattern.COMMENTS` behaves identically, so `RegexOption.COMMENTS` is not used.

Its ambiguity resolution is also instructive as a warning: with no way to express "match this only
as a last resort", tvnamer resolved a mis-parse ([#140](https://github.com/dbr/tvnamer/issues/140),
`H.264` read as season 2 episode 64) by **deleting** patterns, pushing the burden onto users who
then had to restate all 22 to get them back. Explicit ordering is what avoids that corner.

## References

- cu-146 — the end-anchored parser this generalises from
- cu-147 — the reference implementation
- cu-148 — the config surface (where a user's rules live, and the tester UI)
- decision-11 — backend-agnostic ids, the same instinct applied to entity keys

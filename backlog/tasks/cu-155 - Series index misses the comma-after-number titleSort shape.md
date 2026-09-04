---
id: cu-155
title: Series index misses the comma-after-number titleSort shape
status: Done
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - comfort
  - bug
dependencies:
  - cu-146
milestone: m-2
priority: low
---

## Description

Found by cu-150 while running the built-in patterns over 139 `titleSort` values captured from the
household's real Plex server. Two of them do not parse:

```
Warhammer 40,000, Book 1, Bequin: Warhammer 40,000 - Pariah
Warhammer 40,000, Book 2, Bequin: Warhammer 40,000 - Penitent
```

The shape is `<Series>, Book <n>, <Subseries> - <Title>` — the number is followed by a **comma**
rather than the ` - ` that `audnexus` requires. cu-146's `audnexus` pattern expects
`,\s*Book\s+<n>\s*-\s*<title>`, so it declines, and every looser pattern then declines too because
the string contains `Warhammer 40,000` — a comma-digit run that the `comma-trail` pattern would
misread if it were reached.

That last part is why this is filed rather than fixed in passing: a naive loosening of the
`audnexus` pattern to accept a comma would make `"Warhammer 40,000"` itself parse as book 40000 for
every other title in that series. The number-with-thousands-separator case has to be excluded
first, and cu-146's notes are explicit that pattern *order* is load-bearing.

Low priority: 2 of 139 books (1.4%), and both still show correctly — they just sort without a
series position.

## Acceptance Criteria

- [x] `"<Series>, Book <n>, <anything> - <Title>"` parses to `<n>`
- [x] `"Warhammer 40,000 - Pariah"` (no book number) still reads as **unknown**, not 40000
- [x] The two real values above are added to the `RealTitleSortCorpusTest` corpus expectations, and
      the floor there raised from 136 to 138
- [x] Pattern order still tried most-specific-first; `explain()` names the new pattern

## Related

- [[cu-146]] — added the seven built-in patterns and the ordering rule
- [[cu-147]] — made the patterns configurable data; a user could already fix this themselves
- [[cu-150]] — captured the corpus that found it

## Implementation Notes

An eighth built-in pattern, **`audnexus_subseries`**, placed immediately after `audnexus` so the
most-specific-first ordering still holds. It is the `audnexus` shape with the number terminated by
a **comma** instead of ` - `:

```
^(?<series>.+?),\s*(?:Book|Bk\.?|Vol\.?|Volume)\s+(?<index>...)(?:\s*[-+]\s*\d{1,3})?\b\s*,\s*\S
```

**Why a new named pattern rather than loosening `audnexus`.** Widening `audnexus`'s terminator to
`[-,]` also passes every case, and was tried first — but `explain()` would then report a match from
a rule whose description does not describe the string, which is the diagnosis gap cu-147 exists to
close (tvnamer #216). A separate name costs one list entry and makes a mis-parse traceable.

**The thousands-separator trap, resolved.** The task warned that a naive loosening makes
`"Warhammer 40,000"` parse as book 40000. It does not here, because the `Book`/`Vol` **label stays
required** and a thousands separator never carries one. That is the load-bearing part of the
pattern: **sabotage-verified** by making the label optional (`)?` → `)?\s*`), which fails
`a sub-series after the book number still parses` with `expected:<1.0> but was:<0.0>`. Restored with
`--rerun-tasks`.

**The corpus floor is now 138 of 139, and it is exact** — raising it to 139 fails with
`only 138/139 real titleSort values parsed`. The single holdout is
`"Hell Divers Series 0 - ..."`, which is `Book 0` and deliberately unknown (0 is the
`NO_SERIES_INDEX` sentinel, so a prequel numbered zero sorts last — cu-146). The two Warhammer
values were already in the committed corpus resource; only the expectation moved.

**Closed to `Done` rather than `In Review`**: no screen changed and no product choice was made —
the proof is three tests plus the real-server corpus, all reproducible headless.

Verify loop green (6 stages). Coverage ratcheted **up**: aggregate 37.75 → 37.76, `data/model`
88.15 → 88.17.

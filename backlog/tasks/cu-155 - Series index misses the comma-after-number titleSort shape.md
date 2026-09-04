---
id: cu-155
title: Series index misses the comma-after-number titleSort shape
status: To Do
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

- [ ] `"<Series>, Book <n>, <anything> - <Title>"` parses to `<n>`
- [ ] `"Warhammer 40,000 - Pariah"` (no book number) still reads as **unknown**, not 40000
- [ ] The two real values above are added to the `RealTitleSortCorpusTest` corpus expectations, and
      the floor there raised from 136 to 138
- [ ] Pattern order still tried most-specific-first; `explain()` names the new pattern

## Related

- [[cu-146]] — added the seven built-in patterns and the ordering rule
- [[cu-147]] — made the patterns configurable data; a user could already fix this themselves
- [[cu-150]] — captured the corpus that found it

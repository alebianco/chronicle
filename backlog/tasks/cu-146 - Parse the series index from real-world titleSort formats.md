---
id: cu-146
title: Parse the series index from real-world titleSort formats
status: To Do
assignee: []
created_date: '2026-09-03'
labels:
  - R2
  - comfort
dependencies:
  - cu-24
milestone: m-2
priority: high
ordinal: 46600
---

## Description

`Audiobook.seriesIndexFromTitleSort` is **end-anchored** —
`Regex("(?:book|bk|#|,)\\s*(\\d+)\\s*$")` — but in real-world audiobook tagging the series number
sits at the **start or middle** of `titleSort`. Both dominant conventions put it there:

- **Audnexus.bundle** (auto-generated, so the commonest by construction) writes
  `"Mistborn, Book 2 - The Well of Ascension"` — verified in `Contents/Code/update_tools.py`
  (`series_with_volume = self.series + ', ' + self.volume`, then
  `title_sort = ' - '.join([series_with_volume, self.title])`).
- **seanap Plex-Audiobook-Guide** prescribes `%Series% %Series-part% - %Title%` →
  `"Expanse 1 - Leviathan Wakes"` (verbatim from its README).

Measured against real formats, the current parser gets **1 of 8** — and the one it passes is
**our own hand-written fixture**, which ends with the number. This is the exact cu-24 trap
recorded in CLAUDE.md ("a tag list's `@Json` name must be checked against a captured response, not
a fixture"), in a different field: the fixture was written to match the code, so every test passed
while the parser was wrong against every real server.

So most `seriesIndex == 0` in the wild is **this regex**, not missing data. That matters for
cu-25's series ordering, cu-24's browse facets, and cu-145's display — all of which degrade to
alphabetical when the index is absent.

**There is no numeric series field in Plex** (re-verified against `albums-real-shape.json` /
`album-detail-real-shape.json`): album `index` is `1` on every book *including* the one whose
`titleSort` says "Book 2"; `parentIndex` is not on an album at all (python-plexapi documents it on
`Track` as the disc number); `Mood` carries the series *name* only (`"Series: Fixture Series"`);
`MVIN`/`MVNM`/`GRP1`/`TIT1`/custom `TXXX(SERIES-PART)` are written by the taggers but **exposed
nowhere** in the music API. Parsing `titleSort` is the only route — and it is present on both the
listing and the detail response, unlike `Style`/`Mood`.

## Acceptance Criteria

- [ ] The parser reads the index from label-first (`Book 5: …`), Audnexus (`Series, Book 2 - …`),
      label-mid (`1994 - Book 1 - …`), hash (`… (Mistborn #2)`), seanap (`Expanse 1 - …`) and
      num-first (`01 - …`, `1. …`) forms
- [ ] It does **not** match `Standalone Book`, `101 Dalmatians`, `Foundation (1951)`,
      `Hobbit, The`, or `Fixture Book, Sorted`
- [ ] Zero-padding is irrelevant — `2`, `02` and `002` are the same position
- [ ] A decimal position (`Book 1.5`, a novella) is preserved rather than truncated. **This means
      `seriesIndex: Int` is the wrong type** — Audnexus's own regex admits `1.5`, `1-3` and `4+`.
      Changing it is a `BookDatabase` schema change: bump the version, write the migration, add the
      `RoomSchemaTest` case (the load-bearing one that opens a real file at the old schema)
- [ ] An unparseable `titleSort` yields **unknown**, sorted last, never a fabricated position —
      and the partiality stays visible through `FacetList.unknownCount`
- [ ] A dedicated unit test for the parser. **There is none today** — only incidental assertions in
      `PlexFixtureContractTest`, which is why the gap went unseen
- [ ] Fixtures added for the real-world formats above, not just the Audnexus one
- [ ] cu-25's series ordering verified against a multi-format library

## Implementation Plan

Validated pattern set (12/12 correct, 0 false positives on the negative cases above; `NUM` is
`\d{1,3}(?:\.\d{1,2})?`), tried in this order:

1. **label-first** — `^(?:Vol\.?|Volume|Book)\s*(NUM)\b(?:\s*[-.:]\s*|\s+)`
2. **audnexus** — `^(?<series>.+?),\s*(?:Book|Vol\.?|Volume)\s+(NUM)\b(?:\s*-\s*|\s*$)`
3. **label-mid** — `(?:\s|^)[-–]\s*(?:Vol\.?|Volume|Book)\s*(NUM)\b`
4. **hash** — `[(\[]?\s*(?<series>[^()\[\]#]+?)\s*#(NUM)\s*[)\]]?`
5. **seanap** — `^(?<series>.+?)\s+(NUM)\s*-\s+`
6. **num-first** — `^(NUM)\s*(?:-\s+|\.\s+)`

Order matters: the labelled forms must be tried before the bare-number ones, or `seanap` claims a
year out of `1994 - Book 1 - Title`.

Note `merge` **already** applies the cu-24 third rule to `seriesIndex` in both arms
(`if (network.seriesIndex != 0) network.seriesIndex else local.seriesIndex`), so no change is
needed there — but keep both arms in step if the type changes.

## Implementation Notes

Filed out of cu-25, after the owner asked whether Plex has a standard place for the book index.

**Relationship to cu-37 (R4 metadata enrichment).** cu-37 already lists "series-ordinal" in scope
and RESEARCH_FINDINGS §5.1 names Wikidata `P1545` (CC0, keyless) as the source. That stays the
right *fallback*, but it should not be the first answer: Audnexus already resolved Audible's
authoritative `seriesPrimary.position` into the string sitting on the server, so an external lookup
would mostly re-fetch what is already local — at the cost of a network round trip, offline
unavailability, and a fuzzy title+author match that can mis-resolve. Parse first; enrich what
tagging genuinely never recorded.

**A third route, if parsing proves insufficient:** Plex **collections** carry series membership
(`Collection: [{"tag": "Darkover"}]` on the real listing) and support a server-side *custom* order,
which this repo already models (`Collection.sortType`, `SortType.fromPlexCode`). A user who has
ordered a series collection has stated the reading order explicitly. The limitation is that
collection order is positional (`?after=<ratingKey>`), not a readable per-item integer, so it gives
an *ordering* but not a *position* to display.

**Sources** (verified at source level, not inferred): Audnexus `update_tools.py` L282-288, L354,
L551 · seanap Plex-Audiobook-Guide README (`TSOA` format string) · python-plexapi `audio.py`
(`parentIndex` = track disc number) · Audiobookshelf `scandir.js`/`prober.js`/`parseSeriesString.js`
(it parses series from structured tags and folder names, and **never** consumes `tagAlbumSort`) ·
this repo's own `*-real-shape.json` captures. One qualification: the *relative prevalence* of the
formats is reasoned from tooling defaults rather than a measured survey — the pattern shapes and
the start-anchored conclusion are solidly sourced.

Worth knowing: **Audnexus never zero-pads**, so `Book 10` sorts before `Book 2` lexically on the
server. Parsing the number fixes an ordering defect Plex itself shows. And Prologue does *not*
surface a series position for Plex ([issue #106](https://github.com/prologueapp/Prologue/issues/106)),
so this is a differentiator rather than table stakes.

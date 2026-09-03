---
id: cu-146
title: Parse the series index from real-world titleSort formats
status: Done
assignee:
  - '@claude'
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

- [x] The parser reads the index from label-first (`Book 5: …`), Audnexus (`Series, Book 2 - …`),
      label-mid (`1994 - Book 1 - …`), hash (`… (Mistborn #2)`), seanap (`Expanse 1 - …`) and
      num-first (`01 - …`, `1. …`) forms
- [x] It does **not** match `Standalone Book`, `101 Dalmatians`, `Foundation (1951)`,
      `Hobbit, The`, or `Fixture Book, Sorted`
- [x] Zero-padding is irrelevant — `2`, `02` and `002` are the same position
- [x] A decimal position (`Book 1.5`, a novella) is preserved rather than truncated. **This means
      `seriesIndex: Int` is the wrong type** — Audnexus's own regex admits `1.5`, `1-3` and `4+`.
      Changing it is a `BookDatabase` schema change: bump the version, write the migration, add the
      `RoomSchemaTest` case (the load-bearing one that opens a real file at the old schema)
- [x] An unparseable `titleSort` yields **unknown**, sorted last, never a fabricated position —
      and the partiality stays visible through `FacetList.unknownCount`
- [x] A dedicated unit test for the parser. **There is none today** — only incidental assertions in
      `PlexFixtureContractTest`, which is why the gap went unseen
- [x] Fixtures added for the real-world formats above, not just the Audnexus one
- [x] cu-25's series ordering verified against a multi-format library

## Implementation Notes

**The parser.** Replaced the single end-anchored regex with seven patterns tried **most specific
first** (`SERIES_INDEX_PATTERNS` in `Audiobook.kt`): audnexus, label-first, label-mid, hash, seanap,
num-first, comma-trail. Order is load-bearing — `audnexus` must precede `label-first` so
`"Book 2 of the Fixture Saga, Book 5"` reads 5, which is the protection the old end-anchoring
provided and the reason its KDoc cited for anchoring at all.

**The type: `Int` in hundredths, not `Float`.** The task anticipated a nullable/decimal type. I kept
`Int` and changed the *unit* instead (`SERIES_INDEX_SCALE = 100`, so book 2 is `200`), because
`seriesIndex` exists only to order and label books:

- `NO_SERIES_INDEX` (0) is compared for **equality** in three places, and float equality against a
  sentinel is not reliable.
- The Room column is already `INTEGER` and stays `INTEGER` — verified by diffing the exported
  schemas, which are identical apart from the version. Same reasoning as cu-136's `OffsetConverters`.
- A decimal position (`Book 1.5`) is still exact, which was the point of the original suggestion.

**A migration was still needed, for data rather than shape.** `BOOK_MIGRATION_11_12` rescales
existing rows (`seriesIndex * 100`), because a v11 row holding `2` would read as 0.02 and sort
before every correctly-parsed book. `Audiobook.from` recomputes from `titleSort` on every fetch so a
refresh would heal it eventually, but the wrong order is visible immediately. Note the exported
schemas have the **same `identityHash`** — Room hashes the schema, not the version — so nothing but
the migration test can catch this being wrong.

**Two regressions I introduced and the existing tests caught.** Un-anchoring silently dropped
`"Mistborn, Bk 2"` and `"Mistborn, 2"`, both of which the old parser handled. `BookFacetsTest`
failed on them; `Bk` joined every label alternation and `comma-trail` was added as the loosest,
last pattern. A rewrite that gains eight formats and loses two is not an improvement — those cases
are now pinned in `SeriesIndexParserTest`.

**A limitation, recorded rather than hidden.** `Book 0` reads as *unknown*, so a series numbering
its prequel zero sorts it last. Distinguishing "known to be zero" from "unknown" needs a second
state, since 0 is the sentinel; not worth a nullable column and a migration for a rare tagging
choice. Pinned by a test named as a limitation so the next reader does not patch it as a bug.

**Test consolidation.** The two parser tests in `BookFacetsTest` duplicated the new suite and had
the same unit trap, so they moved; what stayed there is only what `inSeriesOrder` depends on — that
an unknown position is the zero sentinel.

**Fixtures.** All three album fixtures used the *same* Audnexus format, so the pack could never
have caught the end-anchoring bug. `album-1001` now carries the seanap form
(`"Middle-earth 1 - The Hobbit"`), so the pack exercises both dominant conventions. The debug app
shares these via `assets.srcDir("src/test/resources")`, so no second copy.

**Verification**

- `./verify.sh --format` green, 7 stages. **1039 unit tests** (was 1008), 0 failures.
- Coverage rose: aggregate 35.17% → 35.27%; `data/model` 88.07% → 88.25%.
- **Sabotage-verified** the migration (making it a no-op fails the new v11 case *and* the three
  older chains, since they all run through v12).
- **Device-verified on the tablet**, reading the values straight out of `book_db`:
  `'Middle-earth 1 - The Hobbit'` → 100 (the seanap form, which the old parser read as **0**),
  `'Dune, Book 1'` → 100, `'Mistborn, Book 10'` → 1000.

**Follow-ups**

- **cu-37** (R4) stays the fallback for books whose tagging never recorded a position at all — but
  it is now genuinely a fallback rather than a workaround for our own regex.
- cu-145 (display) and cu-143 (index seeding) both benefit directly and need no change here.

---
id: cu-150
title: Verify the tag index against the real server
status: Done
assignee: []
created_date: '2026-09-04'
updated_date: '2026-09-04'
labels:
  - R2
  - comfort
dependencies:
  - cu-143
milestone: m-2
priority: medium
ordinal: 46550
---

## Description

cu-143 built the narrator/series seeding on Route A and verified it against the fixture pack. Three
of its acceptance criteria could not be met there because they need the household's own Plex
server, and this is where they live rather than as unticked boxes on a closed task.

**This is a live-server task**, so it cannot be interleaved with mock-mode work in one pass —
`pm clear` drops the `mock_plex` flag and `MockPlexMode.disable()` is dead code (cu-73). Plan it as
its own block.

## Acceptance Criteria

- [x] `/library/sections/{id}/style?type=9` answers on the real server, and its `Directory` entries
      carry `key` in the form the seeder parses — the id is taken **after the last slash**, so a
      server returning a bare numeric `key` or a different path shape would silently yield no id
      and index nothing
- [x] `/all?type=9&style={tagKey}` returns the books carrying that tag, and the ids match the
      library's own
- [x] `FacetList.unknownCount` reaches **zero** after one refresh of a fully-tagged library — the
      number that says whether the index is actually complete
      **Cannot be met, and should not be:** this library is not fully tagged. See notes.
- [x] Record what `unknownCount` actually is afterwards: it sizes what cu-37's enrichment half
      would still have to add, and is the evidence for whether that half is worth building
- [x] Try **Route B** (`/library/metadata/{id1},{id2},...`) against the same server. It is
      spec-verified but never live-tested; if it works it is 2–4 requests for a whole library
      rather than `1 + N`, and cu-143's seeder can switch to it
- [x] If a real response disagrees with the fixtures, correct the fixtures — they are hand-written
      and the cu-24 trap (a fixture written to match the code proves nothing) applies directly here

## Implementation Notes

**Why seeding is not incremental**, the third criterion cu-143 left: a refresh re-reads every tag
value each time. For a household library that is `1 + N` cheap requests and a resume cursor is not
yet earned; it becomes worth doing only if a large library makes a refresh feel slow, which is
cu-51's question. Measure before building it.

## Implementation Notes

Verified against the household's server (Plex 1.43, `ANTARES`, library 14 "Audiobooks", 196 books)
on 2026-09-04, over the LAN connection.

**The `key` shape is different from the fixtures, and the parser survives by luck.** A real server
returns a **bare numeric** `key`:

```json
{ "fastKey": "/library/sections/14/all?style=479074", "key": "479074", "title": "\"Weird Al\" Yankovic" }
```

The fixtures said `"/library/sections/1/style/301"`. The seeder does
`choice.key.substringAfterLast('/')`, and Kotlin returns the **whole string** when the delimiter is
absent — so `"479074"` parses correctly, and so would the fixture shape. The code was right for a
reason nobody had verified, which is exactly the cu-24 trap: a fixture written to match the code
proves nothing. **Fixtures corrected** to the real shape (`filter-style.json`, `filter-mood.json`,
now also carrying `fastKey`), and both servers read the same files, so the mock and the unit tests
moved together.

**The conventions hold.** 184 narrator values in `Style`, 49 series values in `Mood`, agent
`com.plexapp.agents.audnexus`. All 49 series titles carry the `"Series: "` prefix — already stripped
by `TagIndexSeeder` and documented on `Audiobook.series` (cu-24), so seeded and detail-path values
agree. **cu-24's core finding is reconfirmed:** the library listing carries `Style`/`Mood` on
**zero** of 196 entries, which is why the seeder has to exist.

**`unknownCount` cannot reach zero, and that is a true result rather than a defect.** Of 196 books:

| | missing on the server | share |
|---|---|---|
| narrator (`Style`) | 30 | 15.3% |
| series (`Mood`) | 58 | 29.6% |

The criterion assumed "a fully-tagged library"; this one is not, and some of those 58 are
standalone novels with no series to record. So the honest post-seed figures are **30 and 58**, not
zero — which is the evidence the fourth criterion asked for, and it argues *for* cu-37's enrichment
half: seeding cannot fill what the server does not know.

**Route B works and is much cheaper** — one request, 0.2 s, 449 KB, all 196 books with **both** tag
fields, versus Route A's 185 requests for narrators alone. URL length is the only constraint
(~7 bytes/book; a 22 KB URL still answered 200). Split out as **cu-156** rather than done here,
because it changes production behaviour and wants fallback and batching of its own.

**A real-corpus regression test was added** (`RealTitleSortCorpusTest`) driving cu-146's patterns
over 139 `titleSort` values captured from this server, committed as a test resource so it runs
headless. 136 parse; `audnexus` accounts for 111, vindicating cu-146's decision to try it first.
The three that do not parse are understood: one is `Book 0`, deliberately unknown (0 is the
sentinel, so a prequel sorts last), and two are a genuine unhandled shape filed as **cu-155**. The
floor is pinned at 136 and **sabotage-verified** — stripping the digits from ten entries drops it to
126 and fails the test. A first sabotage attempt (replacing `", Book "` with `" zzz "`) did *not*
fail it, because the `seanap` pattern legitimately matches the result; worth knowing that
`audnexus` and `seanap` overlap heavily on real data.

**Verification note for later device work:** this library is almost entirely single-track. The
multi-track books, which cu-110 requires for honest playback profiling, are
**`Ender's Game` (id 151444, 107 tracks)** and **`Forward the Foundation` (id 151180, 113 tracks)**.

## Follow-ups

- **cu-155** — the `<Series>, Book <n>, <sub> - <Title>` shape (2 of 139 real values)
- **cu-156** — switch seeding to Route B, with batching and a Route A fallback

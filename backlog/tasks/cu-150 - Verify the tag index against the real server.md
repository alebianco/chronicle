---
id: cu-150
title: Verify the tag index against the real server
status: To Do
assignee: []
created_date: '2026-09-04'
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

- [ ] `/library/sections/{id}/style?type=9` answers on the real server, and its `Directory` entries
      carry `key` in the form the seeder parses — the id is taken **after the last slash**, so a
      server returning a bare numeric `key` or a different path shape would silently yield no id
      and index nothing
- [ ] `/all?type=9&style={tagKey}` returns the books carrying that tag, and the ids match the
      library's own
- [ ] `FacetList.unknownCount` reaches **zero** after one refresh of a fully-tagged library — the
      number that says whether the index is actually complete
- [ ] Record what `unknownCount` actually is afterwards: it sizes what cu-37's enrichment half
      would still have to add, and is the evidence for whether that half is worth building
- [ ] Try **Route B** (`/library/metadata/{id1},{id2},...`) against the same server. It is
      spec-verified but never live-tested; if it works it is 2–4 requests for a whole library
      rather than `1 + N`, and cu-143's seeder can switch to it
- [ ] If a real response disagrees with the fixtures, correct the fixtures — they are hand-written
      and the cu-24 trap (a fixture written to match the code proves nothing) applies directly here

## Implementation Notes

**Why seeding is not incremental**, the third criterion cu-143 left: a refresh re-reads every tag
value each time. For a household library that is `1 + N` cheap requests and a resume cursor is not
yet earned; it becomes worth doing only if a large library makes a refresh feel slow, which is
cu-51's question. Measure before building it.

---
id: cu-37
title: Metadata enrichment + faceted search
status: To Do
assignee: []
created_date: '2026-07-13'
labels:
  - R4
  - differentiator
milestone: m-4
dependencies:
  - cu-15
priority: medium
ordinal: 57000
---

## Description

Audnexus + Wikidata + Hardcover-if-ToS-ok; characters/moods/series-ordinal facets; match cascade ASIN > ISBN > fuzzy title+author. Promoted to cross-backend leveler per D11 (backfills narrator/series for local files and untagged Plex). Resolution: manual override > enrichment > native. See RESEARCH_FINDINGS §5.1.

**Scope narrowed for series position (cu-146, 2026-09-03).** This task's "series-ordinal" item is
now a **fallback, not the first answer**. Plex has no numeric series field, but the position is
carried in `titleSort` by both dominant taggers, and cu-146 parses it locally — offline, with no
round trip and no fuzzy title+author match that could mis-resolve. Audnexus has *already* resolved
Audible's authoritative `seriesPrimary.position` into that string, so an external lookup would
mostly re-fetch what is sitting on the server.

So for series position the resolution order is **manual override > native parse > enrichment**,
inverting the general rule above. Enrichment earns its place only for books whose tagging never
recorded a position at all — Wikidata `P1545` (CC0, keyless) is the source. Before building it,
check how many books actually still read `NO_SERIES_INDEX` after cu-146 and cu-147; that number is
the size of the problem this half solves. The other items here (characters, moods, themes) have no
native source at all and are unaffected.

## Acceptance Criteria

- [ ] Facet chips in search/library
- [ ] Graceful degradation on no-match
- [ ] Feature-flagged Audible-derived sources

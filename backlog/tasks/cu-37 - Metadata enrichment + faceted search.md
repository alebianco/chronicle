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

## Acceptance Criteria

- [ ] Facet chips in search/library
- [ ] Graceful degradation on no-match
- [ ] Feature-flagged Audible-derived sources

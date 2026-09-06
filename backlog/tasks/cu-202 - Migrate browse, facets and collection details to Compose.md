---
id: cu-202
title: Migrate browse, facets and collection details to Compose
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-188
priority: medium
---

## Description

Screen 7 of cu-188's order — the last group of *screens*, after the player, library, home, details,
settings and login flow all shipped.

Five layouts and the adapters behind them:

| screen | layout | adapter |
|---|---|---|
| browse | `fragment_browse.xml` | `FacetListAdapter` |
| facet books | `fragment_facet_books.xml` | `AudiobookAdapter` |
| collection details | `fragment_collection_details.xml` | `AudiobookAdapter` |
| search results | (inside `fragment_home.xml`) | `GroupedSearchAdapter` |
| series-index tester | `fragment_series_index_tester.xml` | `RuleVerdictAdapter`, `SampleTitleAdapter` |

`AudiobookAdapter` is shared by two of these and by the library grid, which is already Compose —
`BookCard`/`BookGrid` exist and should be reused rather than re-derived. The library screen kept the
adapter alive only for these two remaining callers.

**The series-index tester is not optional polish** — cu-147 records tvnamer's issue #216 as a user
who could not tell whether their pattern or the tool was wrong, which is why the tester exists.

## Acceptance Criteria

- [ ] Each screen rendered in Compose, reusing `BookCard`/`BookGrid` where the content is books
- [ ] Compose tests sabotage-verified — a green suite can sit over a visibly broken screen (cu-181)
- [ ] Device-verified in **both orientations** (cu-141, cu-142 and cu-19 were all landscape-only)
- [ ] `AudiobookAdapter`, `FacetListAdapter`, `GroupedSearchAdapter`, `RuleVerdictAdapter` and
      `SampleTitleAdapter` deleted with their `list_item_*.xml` layouts
- [ ] `./verify.sh` green; no per-package coverage regression

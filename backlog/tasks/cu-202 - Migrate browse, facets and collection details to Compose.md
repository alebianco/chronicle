---
id: cu-202
title: Migrate browse, facets and collection details to Compose
status: In Review
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

- [x] Each screen rendered in Compose, reusing `BookCard`/`BookGrid` where the content is books
- [x] Compose tests sabotage-verified — a green suite can sit over a visibly broken screen (cu-181)
- [x] Device-verified in **both orientations** (cu-141, cu-142 and cu-19 were all landscape-only)
- [x] `AudiobookAdapter`, `FacetListAdapter`, `GroupedSearchAdapter`, `RuleVerdictAdapter` and
      `SampleTitleAdapter` deleted with their `list_item_*.xml` layouts
- [x] `./verify.sh` green; no per-package coverage regression

## Implementation Notes

**Two of the five screens were already done.** `fragment_facet_books.xml` and
`fragment_collection_details.xml` both rendered `BookGrid` already — cu-201 migrated them and only
their KDoc was stale. The real work was browse, the series-index tester, and the search overlay.

**Browse** became `BrowseScreen` + a sealed `BrowseContent`. The bug the sealed type removes: the
`FacetList.EMPTY` seed *is* a facet list with no values, so the screen claimed "No narrators known
yet" before the first grouping ran. `BrowseViewModel` now tracks whether a grouping has happened
rather than inferring it from an empty result — an empty library groups to exactly the seed.

**The series-index tester** collapsed five flows and eight `isVisible` decisions into one state,
which removed cu-52's documented conflation workaround (see the commit body). The test drives it
through one recomposition, not two `setContent` calls — that is where the bug lived.

**Search** was the largest win: `GroupedSearchAdapter` was built separately by three screens, each
with its own visibility logic, and they had drifted (home showed no "no results" message where the
other two did). `searchOverlayState` is that decision once, pure and framework-free.

**Dead code found on the way**, all left over from cu-201 and none of it caught by a test:
`AudiobookAdapter` had no live callers, `LibraryBindingAdapters` was only reachable from it,
`bindTag` had zero callers, and six `list_item_*`/`grid_item_*` layouts were orphaned.
`ContentDescriptionTest` caught three of the layouts, which is that guard doing its job.

**One real gap.** `bindImageRounded`'s 77 covered instructions came *entirely* from
`GroupedSearchAdapterTest` inflating rows — deleting an unrelated adapter took it to zero without
touching the function. It has its own sabotage-verified tests now. Incidental coverage is not
coverage.

## What needs the owner's eye

- **The search overlay's empty-query state.** It now covers the screen behind it while showing
  nothing, rather than leaving the shelves visible. That is deliberate (the user is searching, not
  reading), but it is a visible behaviour change on three screens.
- **Home gained a "no results" message** it never had. The wording is `no_books_found`, reused from
  library — a slightly odd fit for a search ("No books found" rather than "nothing matched").
  cu-191 already covers wording that was ported rather than chosen.

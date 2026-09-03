---
id: cu-24
title: "Library usability: list view, author browse, facets"
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R2, comfort]
dependencies: []
priority: medium
milestone: m-2
---

## Description

List view toggle, author browse, series shelves from Style/Mood facets (#105/#72).

## Acceptance Criteria

- [x] Narrator and series browsable for Audnexus-tagged libraries

## Implementation Notes

### List view was already done

`VIEW_STYLE_COVER_GRID` / `TEXT_LIST` / `DETAILS_LIST` exist, selectable from the library filter
sheet, with the seven duplicated mappings collapsed into `ViewStyle.kt` by cu-133. Nothing to do
there. The work was narrator, series and author **browse**, none of which existed.

### The finding that shaped everything

`Style` (narrator) and `Mood` (series) were **not in `PlexDirectory` at all** — both silently
dropped. And in the fixtures captured from a real Plex 1.43.3 server they appear **only on the
per-book detail response**, never on the library listing:

| fixture | tag-list keys |
|---|---|
| `albums-real-shape.json` (listing) | `Collection`, `Image`, `UltraBlurColors` |
| `album-detail-real-shape.json` (detail) | `Image`, **`Mood`**, **`Style`**, `UltraBlurColors` |

So a facet index **cannot be built from a library refresh**. It fills in as books are synced —
`syncAudiobook` already calls `fetchBookAsync` → `retrieveAlbum`, the endpoint that carries them, so
no extra network traffic — but coverage is partial until books have been opened. The browse screen
therefore **says so**: `FacetList.unknownCount` travels with the list and the UI shows a coverage
line whenever it is non-zero. A screen listing 12 narrators out of 196 books without qualifying
itself reads as "these are all the narrators I have", which is worse than showing nothing.
`AudnexusTagsTest` pins the listing-carries-neither fact, so if a future Plex version starts
sending them, that test failing is the signal the design can be simplified.

### A latent bug found on the way

**`PlexDirectory.plexGenres` had no `@Json(name = "Genre")`**, so Moshi looked for a JSON key
literally called `plexGenres`. Plex sends `Genre`. `Audiobook.genre` has therefore been **empty
against every real server** for the life of the project, while every test passed — because the
hand-written fixtures were written to match the *code* rather than the wire. Fixed, the four
fixtures corrected to the real key, and the regression guard asserts against captured data.

That is why every new test here is pinned against the **captured** fixtures: a test that invents its
own JSON cannot catch this class of bug.

### Storage and merge

`narrator`, `series`, `seriesIndex` on `Audiobook`; `BookDatabase` v10 → v11. `seriesIndex` is
parsed from `titleSort` ("Mistborn, Book 2") because Plex's own `index` is the album ordering index
and is 1 for nearly every audiobook — ordering a series by it would put every book first.

`merge` needed a **third** rule, distinct from both existing ones: the server *can* supply these,
but only from the detail response, and a refresh merges from the listing. So the network value wins
when it has one and the local value is kept when it does not. Preferring the network
unconditionally blanks a narrator on every refresh; preferring the local one makes a re-tagged book
impossible to correct. Both arms sabotage-verified.

### Browse

One `BrowseFragment` with three tabs (Author / Narrator / Series), reachable from the library
toolbar, drilling into `FacetBooksFragment` — which reuses `AudiobookAdapter` and the library's
view-style preference so a book looks the same however the user arrived at it. A series is shown in
reading order (`inSeriesOrder`), an author's or narrator's books keep the library ordering, because
"book 2 then book 10" only means something within a series.

A book with several narrators appears under **each** — stored comma-separated for display and split
on the way into the facet grouping. Verified on device: Dune's two narrators are two rows.

### Two things self-review caught

- **I duplicated a `distinctBy` LiveData helper** that already exists in `LifecycleExt.kt` — the
  exact duplication this codebase keeps having to undo. Deleted; the shared one is used.
- **Room overwrote `10.json` with v11's shape** while leaving it named v10, because the version bump
  and the entity change landed in the same build. Those files are the authority a migration's column
  list is written from (`BOOK_MIGRATION_8_9` says so explicitly), so a corrupted one silently
  misinforms the next migration. Restored from git, and `RoomSchemaTest` now checks every exported
  schema's filename against the `version` inside it. Note a first cut compared column counts between
  adjacent versions and **did not catch it** — an overwritten file is an exact copy of the newer one
  and compares equal; only the declared version disagrees.

### A debug hook, because the bottom nav is untappable

`--ez show_browse true` opens the browse screen. Added because a `BottomNavigationItemView` sits
under the system bars and cannot be driven by `adb shell input tap` — the same obstacle that left
tab navigation uncovered in cu-54's instrumented suite. Without it the screen was unreachable from a
script. It must **post** rather than navigate immediately: called from `onCreate`, a `commit()`
throws `FragmentManager has not been attached to a host`, which it did on the first attempt.

### Verification

`./verify.sh --format` green — **VERIFY PASSED (7 stages)**. 926 → **962 unit tests**. Sabotage-
verified: the `Genre` name, the multi-narrator split, the unknown count, both `merge` arms, and the
schema guard.

On the tablet in mock Plex mode (Audnexus-style tags added to the three album-detail fixtures):

- After opening all three books, `book_db` holds `('1001', 'Rob Inglis', 'Middle-earth', 1)`,
  `('1002', 'Scott Brick, Simon Vance', 'Dune', 1)`, `('1003', 'Michael Kramer', 'Mistborn', 10)`.
- **Author** tab: three authors, one book each, rendered "1 book" (the plural works).
- **Narrator** tab: **four** narrators from three books — Dune's two each got their own row.
- **Series** tab: Dune, Middle-earth, Mistborn, with the `Series:` prefix stripped.
- Tapping "Middle-earth" opened a screen titled for it, listing The Hobbit.

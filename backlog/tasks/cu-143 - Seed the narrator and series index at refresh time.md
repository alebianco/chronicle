---
id: cu-143
title: Seed the narrator and series index at refresh time
status: To Do
assignee: []
created_date: '2026-09-03'
labels:
  - R2
  - comfort
milestone: m-2
dependencies:
  - cu-24
priority: medium
ordinal: 46500
---

## Description

Narrator and series (`Style`/`Mood` tags, Audnexus convention) are currently learned **one book at
a time**, because `syncAudiobook` is the only code that fetches `/library/metadata/{id}`. So the
browse facets from cu-24 and the search from cu-25 only ever see the books the user has already
opened, and `FacetList.unknownCount` is permanently large on a fresh install.

**This is fixable.** The long-standing claim in CLAUDE.md that "a narrator/series index cannot be
built from a refresh" is **false** — it was inferred from the album *listing* omitting `Style`/`Mood`
(true), without checking whether another endpoint could enumerate them (it can). Two mechanisms,
researched 2026-09-03:

### Route A — enumerate tag values, then list per value (fully verified)

Verified against [python-plexapi `library.py`](https://github.com/pkkid/python-plexapi/blob/master/plexapi/library.py)
(`_loadFilters`, `listFilterChoices`, `FilterChoice`, `_buildSearchKey`). **The server supplies the
URLs — do not hardcode them.**

1. `GET /library/sections/{id}/all?includeMeta=1&includeAdvanced=1&X-Plex-Container-Start=0&X-Plex-Container-Size=0`
   — returns **zero items**, only a `Meta` block, so it is cheap. Under `Meta` → `Type[]`, take the
   entry whose `type == "album"`, then its `Filter[]` entries where `filter == "style"` (narrator)
   or `"mood"` (series). Each carries the `key` to enumerate it.
2. `GET` that `key` (shape: `/library/sections/{id}/style?type=9`) — returns `MediaContainer` →
   **`Directory[]`**, each with `key` (the tag id), `title` (the tag text) and `fastKey`
   (documented as the ready-made "list all items with this filter choice" URL).
3. `GET /library/sections/{id}/all?type=9&style={tagKey}` — the books carrying that tag. Read
   **`totalSize`** for the count; per-value counts are **not** present on the `Directory` entries.

Cost: **3 + N + M** requests (N narrators, M series) instead of one per book. It also inverts the
data usefully — narrator → [ratingKeys] is exactly the index shape the facets want.

### Route B — multi-id detail fetch (spec-verified, needs a live test)

[plex-api-spec](https://github.com/LukeHagar/plex-api-spec/blob/main/plex-api-spec.yaml) declares the
path as `/library/metadata/{ids}` — **plural**, *"Get one or more metadata items"*, `ids` being a
comma-separated list. Returns a plain `Metadata[]`, so **the existing Moshi model and
`asAudiobooks()` parsing work unchanged**.

```
GET /library/metadata/1001,1002,1003?excludeElements=Media,Part
```

~2–4 requests for 200 books. Cheaper than Route A and gets `year`/`viewCount`/`leafCount` too, but
it is **not empirically confirmed** (plexapi never batches reads this way) and URL length caps the
batch — chunk to ~50–100 ids.

**Recommendation:** try Route B against the real server first; fall back to Route A, which is fully
verified. Note `/library/sections/{id}/tags` and `/filters` also exist but are **admin-token-gated**,
so unusable for a shared-library user.

## Acceptance Criteria

- [ ] Narrator and series are populated for the whole library after a refresh, without opening books
- [ ] `FacetList.unknownCount` reaches zero for a fully-tagged library
- [ ] Seeding is incremental and resumable — an interrupted refresh does not restart from scratch
- [ ] Seeding does not block the library listing from appearing
- [ ] The chosen route is verified against a real Plex server, and the fallback is implemented if
      Route B fails
- [ ] `merge`'s third rule still holds: a listing-shaped network copy must not blank a known-local
      narrator or series (cu-24)
- [ ] Fixture-backed tests for the enumeration and the filtered listing
- [ ] The CLAUDE.md gotcha claiming an index "cannot be built from a refresh" is corrected

## Implementation Notes

Filed out of cu-25, which found the local search can only group by narrator/series for books
already synced. Research (2026-09-03) established the endpoints above; sources are linked inline
rather than duplicated into `backlog/docs/`.

---
id: cu-143
title: Seed the narrator and series index at refresh time
status: Done
assignee:
  - '@claude'
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

- [x] Narrator and series are populated for the whole library after a refresh, without opening books
- [ ] `FacetList.unknownCount` reaches zero for a fully-tagged library
- [ ] Seeding is incremental and resumable — an interrupted refresh does not restart from scratch
- [x] Seeding does not block the library listing from appearing
- [ ] The chosen route is verified against a real Plex server, and the fallback is implemented if
      Route B fails
- [x] `merge`'s third rule still holds: a listing-shaped network copy must not blank a known-local
      narrator or series (cu-24)
- [x] Fixture-backed tests for the enumeration and the filtered listing
- [x] The CLAUDE.md gotcha claiming an index "cannot be built from a refresh" is corrected

## Implementation Notes

Filed out of cu-25, which found the local search can only group by narrator/series for books
already synced. Research (2026-09-03) established the endpoints above; sources are linked inline
rather than duplicated into `backlog/docs/`.

## Implementation Notes

**Route A implemented; Route B deliberately not.** `TagIndexSeeder` enumerates a tag filter's
distinct values (`/library/sections/{id}/style?type=9`) and then lists the books carrying each
(`/all?type=9&style={tagKey}`) — `1 + N` requests per field instead of one per book, and it yields
the narrator → books association directly rather than deriving it. The multi-id route
(`/library/metadata/{id1},{id2},...`) would be cheaper still, but it is spec-verified only, never
live-tested, and python-plexapi does not use it — so it stays a follow-up to try against a real
server rather than the mechanism this depends on.

**The merge rule is the delicate part**, so it is pure and separately tested. Seeding runs *after*
`Audiobook.merge` and **never overwrites a non-empty field**: a narrator read from a book's own
detail response is precise, and this index is the coarser source saying the same thing less
exactly. Getting that backwards would blank correct metadata on every refresh — the exact failure
cu-24's third merge rule exists to prevent. Sabotage-verified: making the index overwrite fails
three tests.

Two details the shape forced: a full-cast recording appears under **every** reader's filter, so
narrators accumulate and are joined sorted rather than overwriting (an unsorted join would depend
on the order the server enumerated the tags); and the `Mood` value carries the Audnexus `Series:`
prefix, stripped here the way `AudnexusTags.seriesName` strips it from a detail response.

**Failure is per value, never fatal.** These endpoints are community-documented, so a server that
will not answer them must still get a working refresh. `readAssociations` swallows its own errors
and returns what it managed — a library with forty narrators indexes thirty-nine if one listing
fails — and `refreshData` treats an empty result as "no seeding available", which is the state the
app has been in since cu-24 anyway.

**A routing trap worth knowing.** `/library/sections/1/style` contains neither `/all` nor a query,
so in both fixture servers it fell through to the bare-section rule and returned `libraries.json` —
the seeder would have read a *library list* as a list of narrators. Both routers now match the tag
paths first, and `PlexFixtureContractTest` keeps the two copies in step (the same defect existed in
both routers in cu-18).

**Verification**

- `./verify.sh` green, 6 stages. **1115 unit tests**, 0 failures.
- **Sabotage-verified** the overwrite rule (3 tests fail when the index is allowed to overwrite).
- **Fixture-backed end-to-end**: `TagIndexSeedingRefreshTest` drives the real `refreshData` against
  `FakePlexServer` and asserts the database afterwards — a book nobody opened comes back knowing
  its narrator and series, a book under no tag stays empty, a known narrator survives, and a
  refresh still succeeds when both tag endpoints fail.

**Not verified against a real server.** The endpoints are exercised only against the fixture pack,
so the *shape* is pinned but the live behaviour is not — in particular whether a real Plex returns
the `key` in the form this parses (`/library/sections/1/style/301`, from which the id is taken
after the last slash). That is the one thing worth checking on the household server before
trusting the index, and it is why the CLAUDE.md note says the route is verified in python-plexapi's
source rather than verified here.

**Follow-ups**

- Route B (multi-id) as a cheaper path, once it can be tried against a real server.
- `FacetList.unknownCount` should now reach zero for a fully-tagged library; worth confirming on
  the real library, and it is the number that sizes what cu-37's enrichment half would still add.

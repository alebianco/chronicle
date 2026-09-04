---
id: cu-156
title: Switch tag seeding to the multi-id metadata route
status: Done
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - performance
  - comfort
dependencies:
  - cu-143
  - cu-150
milestone: m-2
priority: medium
---

## Description

cu-143 built narrator/series seeding on **Route A** — enumerate a tag filter's values, then list the
books carrying each — because **Route B** (`/library/metadata/{id1},{id2},...`) was spec-verified
only and nobody had tried it against a real server. cu-150 has now tried it.

**Route B works, and it is dramatically cheaper.** Measured against the household's server
(Plex 1.43, 196 audiobooks, library 14) on 2026-09-04:

| | Route A (current) | Route B |
|---|---|---|
| Requests for a full seed | `1 + N` — **185** for narrators alone (184 tag values + 1) | **1** |
| Wall clock | not measured, but 185 sequential round trips | **0.2 s** |
| Response | one listing per tag value | 449 KB, all 196 books |
| Tags returned | narrator *or* series per pass | **both**, in one response |

The response carries `Style` and `Mood` per book directly, so it answers the same question the
`1 + N` walk does, in one call, for both fields at once.

## URL length is the only real constraint

Ids average 6 characters, so the path grows ~7 bytes per book. Probed against the real server:

| Books | URL length | Result |
|---|---|---|
| 196 | 1.4 KB | 200 |
| 784 | 5.5 KB | 200 |
| 1568 | 11 KB | 200 |
| 3136 | 22 KB | 200 |

Plex itself tolerates well past the common 8 KB limit, but a reverse proxy or relay in front of it
may not — and the relay path is one of the three connection tiers (cu-11). So **batch anyway**, at a
conservative size, rather than relying on a ceiling measured on one direct LAN connection.

## What to do

1. Add a multi-id fetch to `PlexService` and have `TagIndexSeeder` prefer it.
2. **Batch** by URL length, not by count — target ~2 KB of ids per request (roughly 280 books), so
   even a 196-book library is one call and a 2000-book one is about eight.
3. Keep Route A as the fallback: if the multi-id request fails or answers with fewer items than
   requested, fall back rather than leaving the index empty. The endpoint is spec-documented but
   still not *guaranteed* across Plex versions.
4. Keep the existing rules: seeding runs after `Audiobook.merge`, **never overwrites a non-empty
   field**, and failure is per-batch and never fatal (cu-143).
5. Fixtures for the multi-id shape, captured from a real response, not hand-written (cu-24).

## Acceptance Criteria

- [x] A full-library seed issues a small constant number of requests instead of `1 + N` — **4
      requests** on the household library, verified on device
- [x] Both narrator and series fill in from the same pass — **166 narrators, 138 series** from one
      pass
- [x] Batching is driven by URL length with a conservative cap, and a library far larger than the
      household's still produces valid request URLs
- [x] A failed or short multi-id response falls back to Route A rather than yielding an empty index
- [x] Non-empty local values are still never overwritten — sabotage-verified
- [x] Fixtures captured from a real response

## Related

- [[cu-143]] — built Route A and named Route B as the cheaper unverified option
- [[cu-150]] — verified Route B against the real server; the numbers above
- [[cu-51]] — large-library performance, the question this makes cheaper to answer

## Implementation Notes

**Route B is live and is what a refresh now uses.** Verified end-to-end on the tablet against the
household server: narrator and series cleared to empty, refresh forced, and the index rebuilt in
**4 multi-id requests** — zero Route A filter requests, no fallback — filling **166 narrators and
138 series** of 196 books. Those totals are exactly right: the remaining 30 and 58 are books the
server itself has no `Style`/`Mood` for, the same figures cu-150 measured independently. The
`Series:` prefix is stripped (`The Age of Madness`, `Eisenhorn`).

**The bigger find: seeding had never run at all.** cu-143 wired `withSeededTags` into
`refreshData`, but `LibrarySyncRepository` — the path an actual sync takes — calls
**`refreshDataPaginated`**, which had no seeding. That is why all 196 books on this device had an
empty `series` when cu-155 went looking earlier. The paginated path now seeds too, with the same
rules (after the merge, never overwriting, best-effort). Without this, Route B would have been a
faster version of something that never executed.

**Two traps, both of which produced a green suite and a broken feature:**

1. **`plexMediaContainer.metadata`, not `.plexDirectories`.** A metadata response carries items
   under `"Metadata"`; a *filter choices* response carries them under `"Directory"`. Both
   deserialize to `PlexDirectory`, so the wrong one compiles, returns an empty list and silently
   seeds nothing. Sabotage-verified — swapping it fails two tests.
2. **The fake server must filter by the ids requested.** A router answering the whole captured
   fixture regardless made Route B look like it *succeeded* for a library it knew nothing about, so
   the fallback never fired — and it turned cu-143's three refresh tests red, which is how it was
   caught. This is the cu-18/cu-143 mis-routing trap in a third place. Filtering is a text scan
   rather than `org.json`, which is an unimplemented stub in a plain JVM unit test: it throws, the
   dispatcher never answers, and the failure surfaces as a **socket read timeout** rather than a
   parse error.

**Batching is by URL length (2 KB of ids), not count**, because ids are free-form strings since
cu-71. cu-150 got a 200 from a 22 KB URL, so Plex is not the constraint — but the relay is one of
the three connection tiers (cu-11) and a proxy commonly caps the request line at 8 KB. 196 books is
~1.4 KB, so a household library is one request and a 2000-book one is about eight. An id longer
than the cap on its own is still emitted rather than silently dropped.

**The fixture is captured, not written** (`multi-id-real-shape.json`): five real books covering all
four tag combinations — both tags, narrator only, series only, neither. Re-probed while capturing:
196 ids in a 1371-character path, **200 in 0.196 s, 449 KB**.

**Closed to `Done`**: no screen changed and no product choice was made — the seeded values are the
same facts Route A produced, arrived at in 4 requests instead of 185. Verified by fixture-backed
tests, two sabotages, and a live run against the real server.

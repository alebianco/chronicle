---
id: cu-156
title: Switch tag seeding to the multi-id metadata route
status: To Do
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

- [ ] A full-library seed issues a small constant number of requests instead of `1 + N`
- [ ] Both narrator and series fill in from the same pass
- [ ] Batching is driven by URL length with a conservative cap, and a library far larger than the
      household's still produces valid request URLs
- [ ] A failed or short multi-id response falls back to Route A rather than yielding an empty index
- [ ] Non-empty local values are still never overwritten
- [ ] Fixtures captured from a real response

## Related

- [[cu-143]] — built Route A and named Route B as the cheaper unverified option
- [[cu-150]] — verified Route B against the real server; the numbers above
- [[cu-51]] — large-library performance, the question this makes cheaper to answer

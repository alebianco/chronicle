---
id: cu-18
title: Continue Listening home shelf
status: Done
assignee:
  - '@claude'
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies:
  - cu-9
priority: medium
ordinal: 2000
---

## Description

Client-built from per-track viewOffset + lastViewedAt (Plex has no music on-deck).

## Acceptance Criteria

- [x] Most recent in-progress books shown
- [x] One tap resumes
- [x] Ordered by lastViewedAt

## Implementation Notes

**Two of the three criteria were already met by existing code**, confirmed on the tablet before
writing anything: `HomeViewModel.recentlyListened`, a `RECENTLY LISTENED` shelf in
`fragment_home.xml`, and a DAO query that already filters to in-progress books
(`lastViewedAt != 0 AND progress > 10000 AND progress < duration - 120000`) ordered by
`lastViewedAt DESC`. So the work was the third criterion plus a bug the shelf revealed.

### One tap resumes

`HomeViewModel.resume(audiobook)` plays through `MediaServiceConnection` with
`USE_SAVED_TRACK_PROGRESS` — the sentinel, not an offset computed here, because the service owns
resolving the saved position from the tracks and duplicating that resolution is what cu-136 was
about. Connects first when the service is not bound, which is the common case for this shelf since
the user has just opened the app.

The Continue Listening shelf now has its **own** click behaviour; Recently Added and Downloaded
still open details, which is right for a book you have not started. `AudiobookClick` gained a
`onLongClick` with a `false` default, so every other shelf is unchanged and only this one opts in —
long-press keeps details reachable.

An uncached book with no server raises `resumeError` (a string resource `Event<Int>`, matching
`LibrarySyncRepository.errorMessage`, since the ViewModel has no `Context`). A tap that silently
does nothing is the worst outcome: the user cannot tell a broken app from an unavailable book.

### A phantom book row, found by looking at the shelf

The shelf rendered **two entries for one book**: "The Hobbit", and "An Unexpect…" whose author read
"The Hobbit". The DB showed a *track* in the `Audiobook` table:

```
1001 | The Hobbit          | J R R Tolkien | leafCount 3
2001 | An Unexpected Party | The Hobbit    | leafCount 0   <- a track
```

Reproduced deterministically — three album rows before playback, the track row appearing the moment
a book played. Path: `fetchBookAsync` → `retrieveAlbum(bookId)` → `asAudiobooks()` →
`bookDao.update(merged)`, and `update` is `@Insert(REPLACE)`, so it **inserts** an unknown id
rather than failing.

**The trigger was a fixture defect in both mock servers.** `MockPlexServer` (debug app) and
`FakePlexServer` (unit tests) each routed *every* `/library/metadata/*` to
`track-with-chapters.json` — so an album request got tracks. `retrieveAlbum` and
`retrieveChapterInfo` are the **same URL with the same query parameters**, so neither router could
distinguish them by path; both now route on the **id**, with a new `album-<id>.json` per book.
One file per id, not one file listing all three: a detail response holding every album would make
`fetchBookAsync`'s `firstOrNull()` answer 1001 for every request — a worse bug than the one being
fixed.

**And the production weakness is closed.** `asAudiobooks()` now rejects metadata whose `type` is a
*known* non-album (`track`, `artist`, `collection`, …) and logs what it dropped. An absent or
unrecognised `type` is **accepted** on purpose — Plex does not guarantee the field, and a strict
check would empty the library of a server that omits it, turning a cosmetic duplicate into an empty
app. Refusing only what we positively recognise as something else keeps the worst case no worse
than before.

`PlexFixtureContractTest` pins the routing, because it exists in two mirrors that had the same
defect: every book id has a detail fixture holding exactly that album, the fixture ids match the
library listing, and the track fixture yields no books.

### Verification

- `./verify.sh` green, 7 stages. **789 → 801 unit tests**, 0 failures (12 added: 7 type-guard, 5
  resume).
- Coverage 31.96% → 32.24%. **`features/home` 0.00% → 16.09%** — one of the 0% packages cu-135's
  per-package gate was built to expose, and the gate is what caught my first cut: adding `resume`
  with no test dropped the aggregate 0.07% and failed the build, which is exactly the intended
  behaviour.
- **On device** (tablet, mock mode): the mock now serves `album-1001.json` for the album request and
  `track-with-chapters.json` for the three per-track chapter queries; the DB holds **three books,
  no phantom row**; the shelf shows one correct entry (Recently Added went from four to three);
  a tap plays book 1001 and reports progress at 57 429 ms — resuming from the ~44 294 ms saved, not
  restarting — while staying on `MainActivity`; a long press opens `AudiobookDetailsFragment`.

### Two problems found by self-review

- My new observer landed under a comment describing `syncError`, so the doc read wrong for both.
- `resumeOnClick` reads `viewModel`, a `lateinit` set in `onCreate`, from a property initialized at
  *construction*. It is safe because the read sits inside the lambda body and happens at click
  time — but only incidentally, so that is now stated where someone might hoist it.

---
id: cu-159
title: Drop the Audiobook.chapters column
status: Done
assignee:
  - '@claude'
created_date: '2026-09-04'
labels:
  - R2
  - architecture
  - debt
milestone: m-2
dependencies:
  - cu-82
  - cu-158
priority: low
ordinal: 75000
---

## Description

The last step of [[cu-49]]'s chapter move, carved out of [[cu-82]] because it cannot land safely in
the same change.

cu-82 made `ChapterDatabase` the source of truth: every read resolves table → legacy column →
`asChapterList()`. The middle level is now the only thing keeping the column alive.

> **Renumbered 2026-09-05 by [[cu-127]].** This task was written against `BookDatabase` v12 and
> claimed v12→v13. cu-127 took v13 for the `source` retype, so the numbers here move up one:
> the migration is **v13→v14** and the released file that must not be rewritten is **`13.json`**.
> The gate is unchanged — a released build must have run cu-158's backfill first.
>
> One thing cu-127 makes easier: `rebuildTable` now takes `columnExpressions`, and dropping a
> column is a rebuild. Copy the column list from the exported `13.json`, which is the authority.

## Why cu-82 stopped short of dropping it

`ChronicleApplication.backfillChapterTable()` **launches without awaiting**, and
`BookRepository.backfillChapterTable()` is gated on two COUNT queries. So on the first launch after
an upgrade the table is *being filled while the UI reads*. Dropping the column in that same change
removes the only fallback while the race is live: a user who opens a book in those seconds sees
**no chapters**, and the data to recover them is gone.

Dropping it is safe only once **a released build has run the backfill** — a release boundary, which
is an owner decision rather than an agent one. That is the gate on this task, not any further code
understanding.

## What to do

1. Confirm the release containing cu-158's backfill has shipped and been run.
2. Remove `Audiobook.chapters`, bump `BookDatabase` to **v14**, write the migration.
3. Collapse `resolveChapters` and `resolveChaptersFromCache` from three levels to two — the
   `asChapterList()` fallback (cu-13) **stays permanently**; only the column level goes.
4. Remove `ChapterListConverter` and its tests with the column, or write down why they stay.
5. `ChapterBackfill` and `BookRepository.backfillChapterTable` become dead with the column — remove
   them in the same change, along with `BookDao.countBooksWithChapters`.

## Acceptance Criteria

- [x] `Audiobook.chapters` removed, with a `BookDatabase` **v13→v14** migration
- [x] `RoomSchemaTest` gains a v13 file-backed case, **verified by deliberate sabotage** — an
      in-memory test cannot catch a migration that disagrees with its entity
- [x] The exported `13.json` is unchanged by the bump (cu-24: Room rewrites the *older* file when a
      version bump and an entity change land together)
- [x] The `asChapterList()` fallback still works for a book with no chapter data anywhere
- [x] `ChapterListConverter` removed — nothing outside the column used its format
- [x] The now-dead backfill machinery removed
- [x] Verify loop green

## Related

- [[cu-82]] — made the table load-bearing and left this the only remaining step
- [[cu-158]] — the backfill whose shipping is the precondition
- [[cu-49]] — introduced the table and the deliberate double write

## Implementation Notes

**The gate was re-examined and found already satisfied, by a different argument than the task
assumed.** cu-159 was written to protect a user upgrading across cu-49, whose chapters lived only
in the column while the backfill was still running. Checked on both household devices (2026-09-05):

```
book_db:    196 books, 0 with a non-empty `chapters` column
chapter_db: 117 rows across 2 books
track_db:   1379 tracks across 197 books
```

The column is **empty on every book** — nothing has written it since cu-49, and both libraries were
synced after that. So the drop destroys no data, and there is no release boundary left to wait for:
the risk the gate existed to hold back does not exist on any install. The permanent
`asChapterList()` fallback also covers every book, and `syncAudiobook` refetches chapters from Plex
whenever a book is opened, so a book with no rows repairs itself.

The owner confirmed the reasoning ("they would be repopulated from plex anyway no?") — which is
right, and is now the load-bearing argument rather than the release boundary.

### What was removed

- `Audiobook.chapters` and its `ChapterListConverter` (nothing outside the column used the format)
- `ChapterBackfill`, `BookRepository.backfillChapterTable`, `BookDao.countBooksWithChapters`, and
  the `ChronicleApplication` startup hook
- Four test files that tested only the removed machinery, and the `BooksKeyDedupTest` case that
  guarded against including the column in the dedup key — a cost that no longer exists

`resolveChapters` and `resolveChaptersFromCache` collapse to two levels. The three ViewModels that
combined a book with its tracks now combine two sources rather than three, since the `audiobook`
source was only there to read the column.

### Two things found while removing it

1. **`Chapter.downloaded` was written and never read.** Two sites (`BookRepository.updateCachedStatus`
   and `CachedFileManager`) stamped it onto each serialized chapter on every cache change. Nothing
   consumed it — whether a book's audio is on disk is answered by `MediaItemTrack.cached`. Both
   sites are simpler now.
2. **Coverage fell 41.01% → 40.62%**, and the baseline was lowered deliberately. 771 lines of test
   were deleted against 112 added; `ChapterListConverter` in particular was heavily tested for
   escaping and malformed records, and it is gone. This is the ratchet's stated "deleted
   well-tested dead code" case.

### Verification

Full `./verify.sh` green (6 stages). The migration is **sabotage-verified twice**: dropping
`seriesIndex` from the copy list (caught by 6 tests, NOT NULL violation) and failing to actually
drop the column (caught by 6 tests, Room's entity/schema mismatch on open).

**Verified on the tablet against the real 196-book library**, databases backed up first:

- Migrated to v14 with no crash; column gone, all 23 others present
- 196 books, 6 with listening progress, 138 with a series index, 117 chapter rows, 1379 tracks
  with 98 positions — all intact
- *Ender's Game* (107 chapters) played with `ChapterListAdapter: ... 107 chapters`, chapter 98
  resolved, correct notification, `state=3` PLAYING and positions writing

So this closes to **Done** rather than In Review: the proof is automated plus a reproducible
on-device check, and no screen or product choice changed.

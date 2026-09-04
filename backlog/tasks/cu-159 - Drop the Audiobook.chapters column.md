---
id: cu-159
title: Drop the Audiobook.chapters column
status: To Do
assignee: []
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
---

## Description

The last step of [[cu-49]]'s chapter move, carved out of [[cu-82]] because it cannot land safely in
the same change.

cu-82 made `ChapterDatabase` the source of truth: every read resolves table → legacy column →
`asChapterList()`. The middle level is now the only thing keeping the column alive.

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
2. Remove `Audiobook.chapters`, bump `BookDatabase` to v13, write the migration.
3. Collapse `resolveChapters` and `resolveChaptersFromCache` from three levels to two — the
   `asChapterList()` fallback (cu-13) **stays permanently**; only the column level goes.
4. Remove `ChapterListConverter` and its tests with the column, or write down why they stay.
5. `ChapterBackfill` and `BookRepository.backfillChapterTable` become dead with the column — remove
   them in the same change, along with `BookDao.countBooksWithChapters`.

## Acceptance Criteria

- [ ] `Audiobook.chapters` removed, with a `BookDatabase` v12→v13 migration
- [ ] `RoomSchemaTest` gains a v12 file-backed case, **verified by deliberate sabotage** — an
      in-memory test cannot catch a migration that disagrees with its entity
- [ ] The exported `12.json` is unchanged by the bump (cu-24: Room rewrites the *older* file when a
      version bump and an entity change land together)
- [ ] The `asChapterList()` fallback still works for a book with no chapter data anywhere
- [ ] `ChapterListConverter` removed, or a written reason to keep it
- [ ] The now-dead backfill machinery removed
- [ ] Verify loop green

## Related

- [[cu-82]] — made the table load-bearing and left this the only remaining step
- [[cu-158]] — the backfill whose shipping is the precondition
- [[cu-49]] — introduced the table and the deliberate double write

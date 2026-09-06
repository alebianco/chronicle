---
id: cu-205
title: loadChapterData leaves stale chapter rows behind
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - bug
milestone: m-2
dependencies: []
priority: medium
---

## Description

`ChapterRepository.loadChapterData` calls `chapterDao.insertAll(chapters)` without first calling
`removeAllForBook`. `BookRepository.syncAudiobook` does call it, and the DAO method's own KDoc says
why:

> Drops every chapter of one book, so a refetch replaces rather than merges. Needed because a
> book's chapter list can *shrink* — a re-tagged file, or a switch from server chapters to the
> per-track fallback. `insertAll` with `REPLACE` only overwrites rows whose key matches, so without
> this the stale extras would survive and the book would show chapters that no longer exist.

That reasoning applies identically to the second write path, which does not do it. A book whose
chapter list shrinks keeps the extras if it was last written through `loadChapterData`.

**Found during cu-201.** The duplicate-chapter defect fixed there is a different bug (chapters
spanning a track boundary being concatenated), but chasing it showed the two write paths disagree.
The stale rows on the device healed only because `syncAudiobook` runs when a book is opened through
the details screen — `--el play_book` does not go through it, and the rows survived several
launches.

## The wrinkle

`loadChapterData(isAudiobookCached, tracks)` takes **tracks, not a book**, so it has no book id to
pass. Every track carries `parentKey`, which the function already uses as `bookId` when mapping
chapters — but a call spanning tracks from more than one book would then delete the wrong book's
rows. Decide whether the signature should take the book explicitly, or whether deriving the
distinct `parentKey` set is safe here.

## Acceptance Criteria

- [ ] A shrinking chapter list drops its stale rows on **both** write paths
- [ ] Sabotage-verified: removing the call makes the test fail
- [ ] The tracks-not-a-book signature question resolved, with the reasoning recorded

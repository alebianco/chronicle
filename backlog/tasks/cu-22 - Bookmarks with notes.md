---
id: cu-22
title: Bookmarks with notes
status: To Do
assignee: []
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies:
  - cu-17
priority: medium
ordinal: 45000
---

## Description

Add/edit/jump bookmarks with notes; registers in backup schema per D8.

## Acceptance Criteria

- [x] Add/edit/jump works
- [x] Survives re-sync
- [x] Registered in 12b backup schema

## Implementation Notes

### Storage: a fifth Room database

`BookmarkDatabase` v1, holding `Bookmark(id, bookId, position: BookOffset, note, createdAt)`.
Separate from `BookDatabase` **so the sync path cannot reach it**, which is what makes criterion 2
a structural property rather than something to be careful about: `refreshData` merges `Audiobook`
rows and calls `bookDao.removeAll` for books the server no longer lists, so a bookmark stored
alongside a book would vanish when a Plex rescan briefly drops it — permanently, since no server
holds a copy of a note the user wrote.

- `position` is a **`BookOffset`**, the cu-136 type. A bookmark points into the *book*; the
  conversion to a track offset happens only when jumping, in the one place that owns it.
- `id` is a generated UUID, not `(bookId, position)`: two bookmarks may mark the same moment, and
  editing a note must not change identity. It is also what makes a backup restore idempotent.
- Room stores `position` as a plain `INTEGER` via the existing `OffsetConverters` — verified
  against the exported schema, so the value class costs nothing.

### Jump reuses the one seek path

`jumpToChapter(bookOffset, trackId, hasUserConfirmation = true)` already converts book → track via
`inTrackOffsetFor` and drives `pausePlay`. A second seek path is exactly how cu-136 found four
frame bugs, so there isn't one. The confirmation prompt is skipped deliberately: picking a specific
bookmark *is* the confirmation, and that prompt's wording is about losing chapter progress.

### Backup: a top-level array, schema v2

`SettingsBackup.bookmarks: List<BookmarkBackup>`, not entries in `settings` — that map is
`String -> String` preference keys, so records could only go in as JSON inside a string value,
which is unreadable in a file meant to be opened in an editor (D12 rule 7). `BookmarkBackup` is a
**separate type** from the entity so a Room rename is a compile error rather than a silent change
to what old files mean.

`BACKUP_SCHEMA_VERSION` 1 -> 2. The existing rule (adding a key needs no bump) still holds
backwards — a v1 app has no such field and Moshi drops it. The bump is for the other direction:
`importSettingsOrNull` refuses a *newer* file, and that refusal only means something if the number
moves when the format grows, otherwise this build cannot tell "a v1 file that had no bookmarks"
from "a v2 file whose bookmarks were lost".

Import is **additive and idempotent**, keyed on the id in the file. Restoring twice overwrites the
same rows; a restore never deletes bookmarks made since the export; rows for books not in the
library are **kept**, since the library may be re-synced and discarding a note is the worst
available outcome. A blank id or bookId is refused (an id-less row would duplicate on every
import); a negative position is *clamped*, because the note is the part worth keeping.

### UI

Player tray gets a third slot (§3.1 rule 2 — transport central, utilities in the tray): **tap** to
mark the current moment, which opens the note sheet immediately so writing one is part of the same
gesture; **long-press** to list them. The list is a bottom sheet from the player rather than a
section on book details — bookmarks get one home, reachable from where they are made, and details
already has a single RecyclerView for chapters that would need restructuring for a list most books
will not have.

Bottom sheets throughout, not dialogs: there is no `AlertDialog` anywhere in this codebase and
adding one would make the note the odd surface out. Both sheets resolve their listener from
`parentFragment` on each use rather than storing it — a stored callback is the classic bottom-sheet
leak, since the sheet outlives a configuration change and the captured Fragment does not.

### Two things self-review and the gates changed

- **The per-package coverage gate caught the UI as untested** (`views` 15.50 -> 11.79). Rather than
  lower the baseline, the adapter is now driven through Robolectric — which found the tests worth
  having: a recycled row keeping the previous bookmark's note, and the two click targets in one row
  (row = jump, pencil = edit) being wired to the wrong callback. Sabotage-verified by moving the
  visibility assignment into the `hasNote` branch, the exact real-world form of that bug.
  `injection/modules` was lowered deliberately (42.83 -> 42.15): the addition is two `@Provides`
  one-liners.
- **The bookmark actions no longer use `Injector.get().unhandledExceptionHandler()`.** Each catches
  and reports its own failure, so the global handler would only hide a case already handled — and
  without it they are reachable from a unit test, which has no `Injector`. The explanatory comment
  is written once, not three times (cu-134's lesson about duplicated comments).

### Verification

`./verify.sh --format` green — **VERIFY PASSED (7 stages)**. 868 -> **926 unit tests**; coverage
32.67% -> 33.37%. Sabotage-verified: id preservation (7 tests fail without it), additive restore
(2), bookmark reads (14 across two suites), and row recycling (1).

On the tablet in mock Plex mode, portrait:

- **Add** — tapping the tray button created a bookmark at **0:41**, matching the playback position,
  with the note sheet opening straight away. Confirmed in `bookmark_db`: `position = 41045`.
- **Edit** — typing a note and saving wrote `note = 'test'` to the row (read back out of the
  database, WAL files pulled alongside it).
- **Jump** — the list showed **0:33 with its note** and **0:41 with no note line**, ordered by
  position; tapping the first dismissed the sheet and started playback there (`0:37 left` in a
  1:15 chapter).

One false alarm worth recording: the first save appeared to fail. It was the harness — the sheet
moves up when the keyboard opens, so a tap coordinate taken from a dump *before* the keyboard
appeared missed the button. A diagnostic log proved the listener resolved and fired correctly.

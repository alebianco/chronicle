---
id: cu-208
title: Decide whether download-all should be reachable
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R2
  - product
milestone: m-2
dependencies: []
priority: low
---

## Description

`download_all` is a **working feature that has never been reachable**. Found during cu-206.

`library_menu.xml` declared the item `android:visible="false"` and nothing anywhere set it visible,
so it never appeared on a device — while `LibraryFragment` carried a live handler for it and
`LibraryViewModel.promptDownloadAll()` is fully implemented, including a confirmation prompt
(`R.string.download_all_prompt`).

cu-206 did **not** carry the item over to the Compose toolbar, deliberately: leaving it out
preserves today's behaviour exactly, whereas adding it would ship a new one-tap action as a side
effect of a navigation migration. The code behind it is untouched and still works.

## The decision

Restoring it is a product call, not a cleanup:

- One tap would download an **entire library**. On the household's server that is 196 books.
- The confirmation prompt already exists, so the guard is written.
- The alternative is deleting `promptDownloadAll` and its string as genuinely dead code.

Either outcome is fine; leaving it as it is — implemented, tested, unreachable — is the one that
should not persist, because the next reader will find a feature that appears to exist and does not.

## Acceptance Criteria

- [ ] Owner decides: expose it, or delete it
- [ ] If exposed: an action in the library toolbar, with the existing confirmation, device-verified
- [ ] If deleted: `promptDownloadAll`, its prompt string and its tests go together

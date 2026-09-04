---
id: cu-153
title: Check whether moving the sync location leaves partials behind
status: To Do
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - comfort
dependencies:
  - cu-81
milestone: m-2
priority: low
ordinal: 46900
---

## Description

cu-81's design notes asked whether `MoveSyncLocationWorker` can leave partial downloads behind in
the **source** directory when the user changes storage location. It can in principle — the worker
moves files between directories and cu-81's prune only ever looks at the *current*
`cachedMediaDir` — but confirming it needs two real storage volumes, which is a device question
rather than a code one.

**A live-device task**, and it needs hardware the fixture pack cannot simulate: an SD card or a
second external volume, with a download interrupted partway.

## Acceptance Criteria

- [ ] Reproduce: start a download, interrupt it, change the sync location, and check whether the
      partial remains in the old directory
- [ ] If it does, decide where the fix belongs — `MoveSyncLocationWorker` moving partials too, or
      cu-81's prune scanning every known `externalDeviceDirs()` rather than only the active one
- [ ] Whatever the answer, `cachedMediaDir` returns the **stored** path even when the volume is
      unmounted (cu-85), so a prune that scans other directories must not treat an absent volume as
      an empty one — that is the bug cu-85 fixed, in a new place

## Implementation Notes

Filed out of cu-81 rather than guessed at. The prune shipped there is deliberately scoped to the
active directory, which is the safe subset: it can leave bytes behind, but it cannot delete from a
volume it has not properly scanned.

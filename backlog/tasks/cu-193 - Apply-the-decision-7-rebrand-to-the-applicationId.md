---
id: cu-193
title: Apply the decision-7 rebrand to the applicationId
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - trust
dependencies: []
priority: medium
milestone: m-2
---

## Description

Found during the 2026-09-06 backlog and docs cleanup: **[[decision-7]] specifies an applicationId
that was never applied.**

The decision says `applicationId dev.alebianco.chronicle.unabridged`, with its own icon and
wordmark, because upstream and Epilogue branding are off-limits. The code still ships upstream's:

```
app/build.gradle.kts:13   namespace     = "io.github.mattpvaughn.chronicle"
app/build.gradle.kts:28   applicationId = "io.github.mattpvaughn.chronicle"
```

No task covered this — it fell between the decision and the work. Filed so the gap is tracked
rather than rediscovered.

## Why it matters beyond tidiness

An applicationId is the **installed identity**. Two consequences:

1. **It cannot change after distribution without an uninstall/reinstall** — a different
   applicationId is a different app to Android, so the change gets cheaper the earlier it happens
   and there is no in-place upgrade path afterwards. [[decision-1]] (sideload/homelab first, no Play
   listing) is what makes it still cheap today.
2. **Changing it loses the app's data unless migrated** — including the Plex login, listening
   progress not yet synced, bookmarks (which no server holds a copy of, cu-22) and downloaded
   audio. On the household's own devices this is a real cost, not a hypothetical.

## The thing to get right

**This is owner-sign-off territory.** CLAUDE.md lists branding assets and the icon/wordmark under
*Never touch without explicit owner sign-off*, and the household's two installs carry live data.
The task should present the migration plan and the data cost **before** changing anything.

Also note the debug suffix interacts with this: `applicationIdSuffix = ".debug"` means every `adb`
line in CLAUDE.md, `plex-session.sh`, `capture-screens.sh` and the debug hooks names the suffixed
id, and **the activity class does not move with it**. All of those need updating together, or the
verification tooling silently stops working.

## Acceptance Criteria

- [ ] Owner decides whether to rebrand now, defer, or amend [[decision-7]] — recorded in the task
- [ ] If proceeding: a stated migration path for the two household installs, including what data is
      lost and what is preserved
- [ ] `applicationId` and `namespace` updated together, with the debug suffix still working
- [ ] Every `adb`/script reference to the package id updated in the same change (CLAUDE.md,
      `plex-session.sh`, `capture-screens.sh`, debug hook docs)
- [ ] `./verify.sh` green; the app launches and reaches a real server on device
- [ ] If deferring: [[decision-7]] amended to say so, so the code and the decision stop disagreeing

---
id: cu-81
title: Prune abandoned partial downloads
status: To Do
labels: [R2, trust, debt]
dependencies: [cu-76]
priority: low
---

## Description

Split out of [[cu-76]]. That task made `refreshTrackDownloadedStatus` reject a file shorter
than `MediaItemTrack.size`, so a partial download is no longer promoted to "available
offline". The rejected file is deliberately **left on disk**, because Fetch2 resumes via HTTP
Range and those bytes are worth keeping.

What is missing is the other half: nothing ever deletes a partial whose download was abandoned
for good. Cancel a download, or fail one past its retries and never return to the book, and the
bytes stay in `cachedMediaDir` indefinitely — invisible, since the UI correctly reports the
book as not downloaded.

Not urgent: it costs disk, not correctness, and a user who re-downloads the book reuses the
partial. It matters on a device where `cachedMediaDir` is a small SD card, and it is the kind
of thing that only shows up as "the app is using 30GB and I have four books".

## Design notes

- **Do not prune anything Fetch2 still knows about.** A `PAUSED` or `FAILED` download is a
  resume candidate ([[cu-76]]'s `ResumePlan`); deleting its bytes turns a cheap resume into a
  full re-download. Only files with no corresponding Fetch2 record and no `cached = true` row
  are candidates.
- The reverse of the same rule: the existing "orphaned file" branch in
  `CachedFileManager.refreshTrackDownloadedStatus` is commented out with a TODO about multiple
  sources. Resolve that TODO rather than adding a second, parallel notion of orphaned.
- Worth checking whether `MoveSyncLocationWorker` can leave partials behind in the *source*
  directory when it moves storage locations.

## Acceptance Criteria

- [ ] A partial file with no Fetch2 record and no cached DB row is deleted
- [ ] A partial belonging to a `PAUSED`/`FAILED` (resumable) download is **kept**, covered by
      a test that fails if the rule is inverted
- [ ] The commented-out orphan branch is either implemented or removed with a written reason
- [ ] Verify loop green

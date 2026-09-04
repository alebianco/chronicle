---
id: cu-81
title: Prune abandoned partial downloads
status: Done
labels: [R2, trust, debt]
dependencies: [cu-76]
priority: low
milestone: m-2
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

- [x] A partial file with no Fetch2 record and no cached DB row is deleted
- [x] A partial belonging to a `PAUSED`/`FAILED` (resumable) download is **kept**, covered by
      a test that fails if the rule is inverted
- [x] The commented-out orphan branch is either implemented or removed with a written reason
- [x] Verify loop green

## Implementation Notes

**The safety rule is the design, and it is three conditions, not one.** A partial is deleted only
when Fetch2 has no record of it, the database does not call it cached, **and** it is actually
incomplete. Each rules out a different way of destroying something valuable — a resume candidate
(cu-76's `ResumePlan`, where deleting bytes turns a range request into a full re-download), a
download the user made on purpose, and a *complete* file whose row is merely stale. That last one
matters: a full-length file with no Fetch record is a finished download, and `reconcileCachedTracks`
adopts it. Anything failing a check is kept, deliberately — keeping a stale partial costs disk,
deleting a live one costs the user their download.

**The TODO was resolved, not removed**, as the task asked. The commented-out "orphaned file" branch
proposed deleting any *complete* file whose track had no row. That stays refused, and now says why:
downloads are retained across libraries, so "no row here" does not mean "nobody wants this". Only
incomplete files are ever deleted.

**Split in two so both halves are testable.** `partialsSafeToPrune` is set arithmetic — seven tests,
one per way a file can earn a reprieve. `prunePartialFiles` does the deleting and is tested against
**real temp files**, because "did it delete the right file, and only that one" is not a question set
arithmetic can answer. Sabotage-verified by making it delete every known file, which fails two tests
including the one asserting a neighbour survives.

Only the manager's Fetch callback wrapper is untested, and it cannot be: `Fetch` is a framework
object whose `getDownloads` is callback-based. Every other lambda in `CachedFileManager` is already
at 0% for the same reason.

**The coverage baseline was lowered deliberately.** `data/sources/plex` fell 52.47 → 51.74 because
the new callback joined that pre-existing untestable set; the decision logic it delegates to is
fully covered. Aggregate rose 37.33 → **37.36%**.

**Not done: the `MoveSyncLocationWorker` question.** The task asked whether moving storage
locations can leave partials in the *source* directory. It can in principle, but answering it needs
two real storage volumes — that is a device question, not a code one, and filed as **cu-153** rather
than guessed at here.

**Verification**

- `./verify.sh` green, 6 stages. **1165 unit tests**, 0 failures.
- Sabotage-verified the over-delete case.
- Not device-verified: reproducing an abandoned partial needs a download interrupted past its
  retries against a real server, which the fixture pack cannot produce.

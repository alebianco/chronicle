---
name: chronicle-vacuous-file-tests
description: "Chronicle tests over cached-track files pass vacuously unless the fixture track has a real `media` path"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: eb8384f7-4315-4572-b92d-890d4987c009
  modified: 2026-09-04T15:07:40.896Z
---

In Chronicle, `MediaItemTrack.getCachedFileName()` is `"$id.${File(media).extension}"`. A test
fixture built as `MediaItemTrack(id = "101", parentKey = "book-1")` has no `media`, so the name is
`"101."` — which does **not** match `MediaItemTrack.cachedFilePattern`
(`[A-Za-z0-9_~:@+-]+\.[^.]+`, requiring a non-empty extension).

Every assertion about a file being deleted, moved or scanned then passes while nothing happened.
Give fixture tracks `media = "/library/parts/$id/file.mp3"`.

**Why:** hit while writing `CachedFileManagerUncacheTest` (cu-33). The tests failed loudly only
because the assertion was "the file should be gone"; an assertion phrased the other way round
("the unrelated file survives") would have passed for the wrong reason and proved nothing.

**How to apply:** whenever a test constructs a `MediaItemTrack` and then asserts about files on
disk, set `media`. Sabotage-verify the deletion itself — see [[chronicle-sabotage-rerun-tasks]].

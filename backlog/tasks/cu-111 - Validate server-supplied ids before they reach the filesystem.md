---
id: cu-111
title: Validate server-supplied ids before they reach the filesystem
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-02'
labels: [R1, security, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found in the 2026-09-02 branch review. Two problems with one root cause: **a Plex `ratingKey` is
used unvalidated**, and it is used inconsistently.

### 1. Path traversal into the download destination

`MediaItemTrack.getCachedFileName()` is `"$id.${File(media).extension}"`, and `id` is
`networkTrack.ratingKey` — a plain `String` since the cu-71 retype, validated nowhere. It flows into
`File(cachedFilesDir, track.getCachedFileName())` (`CachedFileManager.kt:194`) and then into Fetch2.
`File(parent, child)` does **not** normalize, so a server returning
`ratingKey = "../../../../databases/BookDatabase"` writes attacker-controlled bytes outside the
cache directory — next to the Room DBs and `ChronicleAuth.xml`.

Scoped honestly: the attacker must already **be** the Plex server, since the app refuses cleartext
app-wide and so MITM is blocked. This is server-compromise escalation, not a remote primitive.
That is why it is high rather than critical.

### 2. The cache-file pattern contradicts cu-71

`MediaItemTrack.cachedFilePattern` is `Regex("""\d+\.[^.]+""")` — **digits only**. cu-71 retyped
ids to `String` specifically so a non-numeric backend can be represented (decision-11,
[[cu-33.1]] Audiobookshelf). A non-numeric track id would download fine and then be **invisible to
the cache scan** (`CachedFileManager.kt:242,407`, `MoveSyncLocationWorker.kt:95`): the file exists,
the DB says uncached, so it gets deleted and re-downloaded forever.

`CachedFilePatternTest` currently asserts this as *correct* (`a non-numeric name does not match`,
guarding `cover.jpg`), so the regex is doing two jobs — rejecting stray files, and constraining id
format — and only the first is intended.

## Acceptance Criteria

- [x] An id containing `/`, `\`, `..`, or a path separator is rejected — at the *container*
      mappers (`asAudiobooks`/`asTrackList`/`asCollections`) rather than in each `from`, since all
      three funnel through there; the item is dropped and logged with its id and title
- [x] A defensive `canonicalPath.startsWith(...)` assertion at the write site
      (`CachedFileManager.kt`), so a future path cannot reintroduce it
- [x] `cachedFilePattern` accepts a non-numeric id and still rejects `.nomedia`, `.DS_Store` and
      `3001.mp3.part`. **`cover.jpg` now matches** — see the notes; it is shaped exactly like a
      track whose id is `cover`, and every consumer is safe against that
- [x] `CachedFilePatternTest`'s "non-numeric does not match" case replaced; a non-numeric id
      round-trips, and the tolerated-stray-file case is documented with why it is safe
- [x] A test that no accepted id produces a path outside the cache directory, plus its converse
      (the rejected ones really do escape) so it cannot pass vacuously
- [x] Verified on device (Phh-Treble GSI, API 32) via the mock server: all three fixture books
      render with titles, authors and a chapter name, no rejections logged, no crash

## Implementation Notes

Two problems, one root cause: a server-supplied `ratingKey` was used unvalidated, and it was used
*inconsistently* — too permissively where it became a filename, too restrictively where files were
read back.

### The validator

`MediaId` (new, `data/model/`) is a deny-list, not an allow-list of digits: cu-71 made ids `String`
precisely so a non-numeric backend can be represented (decision-11), so "must be numeric" is the
wrong rule. It rejects blank ids, `.` and `..` entire, anything *containing* `..`, and any id
carrying `/`, `\` or NUL.

Applied at the **container mappers** (`asAudiobooks`, `asTrackList`, `asCollections`) rather than in
each `Audiobook.from`/`fromPlexModel`, because all three funnel through there — one chokepoint
instead of three call sites to remember. An offending item is **dropped and logged**, never
repaired: a rewritten id would not match the server's on any later request, so the book would break
in a subtler way. Dropping one item keeps the rest of the library working, which is the right
trade when the only way to reach this is a hostile or buggy server.

A `canonicalPath.startsWith` assertion at the download write site is defence in depth. It is
redundant today and deliberately so — that line is where bytes actually hit the filesystem.

### The pattern was too narrow, and that was the *worse* bug

`cachedFilePattern` was `\d+\.[^.]+` — digits only — while ids are `String`. An Audiobookshelf id
would download fine and then be invisible to the cache scan: file present, DB says uncached, so it
is deleted and re-downloaded forever. It is now `[A-Za-z0-9_~:@+-]+\.[^.]+`.

**The consequence, stated plainly:** `cover.jpg` now matches, because it is shaped exactly like a
track whose id is `cover`. The pattern cannot tell them apart and does not need to — verified at
every consumer:

- the cache scan looks the id up in the DB and requires the file length to equal the stored size,
  so an unknown id is dropped a moment later (`refreshTrackDownloadedStatus`);
- the delete path is gated on an exact match against DB-derived filenames, so a stray file is never
  deleted (`uncacheAllInLibrary`);
- `MoveSyncLocationWorker` would move a stray file within the app's own directory — cosmetic.

`CacheScanOutcomeTest` had a case asserting the old behaviour; it is updated with that reasoning
recorded, so if a future change removes one of those guards the note explains why it mattered.

### Two overclaims my own tests caught

Worth recording, because both were plausible and wrong:

1. **`a/../b` does not escape.** It canonicalizes back to `<cacheDir>/b`. It is still refused, but
   for letting the server choose the on-disk layout, not for escaping.
2. **`/etc/passwd` does not escape either.** Java's `File(parent, child)` treats a leading `/` as
   *relative*, unlike a naive path join — so it lands at `<cacheDir>/etc/passwd`. I asserted the
   opposite twice before checking against the JVM directly.

The "if it does not, this test proves nothing" converse test is what caught both. Only `..`-prefixed
ids actually escape. Overstating an exploit is its own failure: it hides which half of a guard is
load-bearing.

### Verification

`./verify.sh` green, 7 stages. 15 `MediaIdTest` cases; the property test resolves real paths rather
than asserting on the predicate. On device (Phh-Treble GSI, API 32, mock server): all three fixture
books render with titles, authors and a chapter title; no rejections logged; no crash.

### Follow-up

The book/track/collection mappers now each carry the same one-line filter. If a fourth entity type
appears, the filter should move into a shared helper rather than be copied again.

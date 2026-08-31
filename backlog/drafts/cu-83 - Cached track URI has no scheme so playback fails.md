---
id: cu-83
title: Cached track URI has no scheme, so downloaded books fail to play
status: To Do
labels: [R1, trust, bug]
dependencies: []
priority: critical
---

## Description

Owner-reported (2026-08-31): *"after downloading the book, playback throws an unsupported format
error. I have to put it in offline mode or redownload the cache to unblock it. Sometimes quitting
and coming back also works."*

`MediaItemTrack.getTrackSource()` returns a **bare filesystem path** for a cached track:

```kotlin
fun getTrackSource(): String =
  if (cached) File(prefsRepo.cachedMediaDir, getCachedFileName()).absolutePath
  else plexConfig.toServerString(media)
```

That string becomes `METADATA_KEY_MEDIA_URI`, and `MediaMetadataCompat.mediaUri` does
`getString(...).toUri()`. **Verified under Robolectric:**

```
"/data/user/0/app/files/3001.mp3".toUri()  ->  scheme=null
File(...).toURI().toString().toUri()       ->  scheme=file
```

A schemeless URI reaching `ProgressiveMediaSource`/`DefaultDataSource` is not recognised as a local
file, which surfaces as an unsupported-format/source error. The server branch is unaffected because
`toServerString` produces a proper `https://` URL — which is why only *downloaded* books break.

It also explains the workarounds: offline mode and a fresh launch take different paths through
track resolution, and a redownload re-runs the cache scan.

## Fix

Return `Uri.fromFile(file).toString()` (or build the `MediaItem` from a `Uri` rather than a String)
so the scheme is explicit. Prefer fixing it at `getTrackSource()`, which is the single place both
branches converge.

## Acceptance Criteria

- [ ] A cached track's source URI has scheme `file`, covered by a test that fails on a bare path
- [ ] The uncached branch still yields the `https` server URL
- [ ] Round-trip test: the string `getTrackSource()` returns parses back to a URI whose scheme is
      non-null in both branches
- [ ] Live check added to [[cu-73]]: download a book, force-quit, relaunch, play offline — no
      format error
- [ ] Verify loop green

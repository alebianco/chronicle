---
id: cu-12
title: Download rebuild on Media3 DownloadManager
status: In Review
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-7, cu-16]
priority: high
milestone: m-1
---

## Description

HTTP-Range resumable, chunked-to-disk (kills 2GB OOM #83), per-book cache status for offline play (PR #114 concept modernized). Replaces Fetch2.

## Investigation (2026-08-31)

Owner asked for diagnosis before committing to a rewrite. **Recommendation: do not migrate to
Media3 `DownloadManager`.** The task's premise does not survive contact with the code, and the
real defects are cheaper to fix where they are.

### Why not Media3 DownloadManager

**It would break a working user-facing feature.** Downloads are stored as plain files at
predictable paths (`<cachedMediaDir>/<trackId>.<ext>`), and **7 files depend on that layout** —
including `MoveSyncLocationWorker`, which moves files between SD card and internal storage when
the user changes their sync location. Media3's `SimpleCache` uses an opaque internal layout
with its own index, so adopting it means either rewriting that feature or dropping it, plus a
migration for every already-downloaded book or a forced re-download.

**The stated justifications do not hold:**

| Claim in the task | What the code shows |
|---|---|
| "kills 2GB OOM #83" | Nothing in the app reads a file into memory. The only `toByteArray()` is on a signing certificate. Fetch2 streams to a destination path. **No OOM mechanism is present in our code** — the claim is either about Fetch2 internals or is stale. |
| "Replaces Fetch2" (implying it is abandoned) | Fetch2 is maintained: **3.4.1** is current and the app is pinned to **3.3.0**. |
| "HTTP-Range resumable, chunked-to-disk" | Fetch2 already streams to disk and supports resume. What is missing is the app *asking* it to. |

Media3's downloader is the better long-term home if downloads ever need to share ExoPlayer's
cache (byte-range streaming of partially-downloaded books, say). That is not a current
requirement, and it is not worth breaking storage-location support to pre-buy.

### The four real defects

1. **A truncated download is indistinguishable from a complete one — the worst of these.**
   `refreshTrackDownloadedStatus` scans `cachedMediaDir` and marks any file matching
   `cachedFilePattern` (`\d*\..+` — *any* `<id>.<ext>`) as `cached = true`, **with no size
   check**. `MediaItemTrack.size` is populated from Plex (`media[0].part[0].size`) and stored
   in Room, and is then **never read anywhere in the app**. So a Wi-Fi drop mid-download leaves
   a partial file that the next launch promotes to "downloaded", and the book plays truncated
   or fails to decode. This is the substance of the "survives Wi-Fi drops" criterion, and it
   has nothing to do with which downloader is used.
2. **Nothing resumes an interrupted download.** `setAutoRetryMaxAttempts(1)` gives one retry,
   `onError` is wired to the same handler as `onCompleted` (both call `onFinished`), and no code
   path calls `fetch.resume`/`retry` on launch. After one failed retry the download is simply
   over, silently.
3. **Downloads bypass the app's entire HTTP stack.** `setHttpDownloader(OkHttpDownloader(...))`
   is **commented out** in `AppModule.fetchConfig` with a `TODO: this was broken when I set up
   Fetch`, so the injected `okHttpClient` parameter is unused and Fetch2 uses its own client.
   That means downloads get none of: `PlexInterceptor`'s headers, cu-10's 401 re-auth, cu-11's
   connection tiering, cu-42's cleartext policy. Instead the token is pasted per-request in
   `makeDownloadRequest`.
4. **Pinned to 3.3.0 while 3.4.1 fixes a bug we should have.** 3.4.0/3.4.1 address "slow
   download start on Android 14+" and remove a broadcast-receiver dependency. This app is
   `targetSdk 36`.

### Recommendation

Reframe the task as fixing 1–4 on Fetch2, in that order. Defect 1 alone likely closes the
"survives Wi-Fi drops" criterion; it is a size comparison against a field already in the
database. Defect 3 is the one that quietly undoes three earlier tasks' work.

Filed as [[cu-76]] so this task can close with its findings intact rather than staying open
against a plan that should not be executed.

### What was verified, and how

- Fetch2 surface: 13 references across 10 files, reaching into the `MediaSource` seam.
- Media3's `DownloadManager`, `ProgressiveDownloader`, `DefaultDownloadIndex`, `SimpleCache`
  and `CacheWriter` are all present in artifacts the app already depends on — the migration is
  *possible*, which is why the argument above is about cost rather than feasibility.
- Fetch2 contributes no manifest components; the foreground service hosting downloads is
  WorkManager's `SystemForegroundService`, via `DownloadNotificationWorker`'s
  `setForegroundAsync(FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. An earlier reading of "no manifest
  components" as "no service" was wrong and is corrected here.

## Acceptance Criteria

- [x] Investigation complete; a recommendation with evidence rather than a rewrite
- [ ] ~~2GB m4b survives Wi-Fi drops and app kill~~ → [[cu-76]] defects 1 and 2
- [ ] ~~Offline books play with server unreachable~~ → already works: `getTrackSource()` returns
      a local path for a cached track, bypassing network and token (verified in cu-10). The real
      risk is defect 1 marking a *partial* file as cached, which [[cu-76]] fixes
- [ ] ~~No OOM~~ → no OOM mechanism found in app code; needs a reproduction against a real 2GB
      book before it can be treated as live ([[cu-73]])

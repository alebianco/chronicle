---
id: cu-76
title: Fix download integrity and resume on Fetch2
status: To Do
assignee: []
created_date: '2026-08-31'
labels: [R1, trust]
dependencies: [cu-12]
priority: high
milestone: m-1
---

## Description

Replaces the rewrite [[cu-12]] proposed. Its investigation found that Media3
`DownloadManager` would break storage-location support for no current benefit, and that the
actual defects are four specific bugs in how the app *drives* Fetch2. Fix those instead.

Ordered by how much user harm each causes.

### 1. A truncated download is marked as complete — **DONE**

Fixed in the cu-12 investigation commit, because it was the worst defect and needed no device:
`isCompleteDownload(file, expectedSize)` compares `File.length()` against
`MediaItemTrack.size`, and `refreshTrackDownloadedStatus` now skips files that fail it. A
mismatch in *either* direction is rejected — a longer file means metadata and bytes disagree,
and trusting it would hide whichever is wrong. `size == 0L` (Plex reported none) falls back to
"non-empty", preserving old behaviour for those tracks without making them un-cacheable.
7 tests, verified to bite (defeating the check fails 6 of them).

The original problem, for the record: `CachedFileManager.refreshTrackDownloadedStatus` scans `cachedMediaDir`
and marks every file matching `MediaItemTrack.cachedFilePattern` (`\d*\..+` — any
`<id>.<ext>`) as `cached = true`. There is **no size check**.

`MediaItemTrack.size` is populated from Plex (`media[0].part[0].size`) and persisted in Room,
and is currently **read nowhere in the app**. So:

- Wi-Fi drops at 40% → a partial file sits in `cachedMediaDir`
- next launch → the scan promotes it to "downloaded"
- the book plays truncated, or fails to decode, and the UI insists it is available offline

Still open here: `cachedFilePattern` (`\d*\..+`) is loose enough to match unrelated files, and
a rejected partial file is currently left on disk — deciding between deleting it and keeping it
for resume belongs with item 2.

### 2. Nothing resumes an interrupted download

- `setAutoRetryMaxAttempts(1)` — one retry, then done.
- `FetchFinishedListener.onError` and `FetchGroupStartFinishListener.onError` both call
  `onFinished`, so a failure is indistinguishable from success to every observer.
- No code path calls `fetch.resume` or `fetch.retry` on launch or on network return.

Fix: raise the retry count, distinguish error from completion in the listeners, and resume
incomplete downloads when the app starts or connectivity returns. Fetch2 supports HTTP-Range
resume — the app simply never asks.

### 3. Downloads bypass the app's whole HTTP stack

`AppModule.fetchConfig` has:

```kotlin
// TODO: this was broken when I set up Fetch, maybe figure it out at some point?
//            .setHttpDownloader(OkHttpDownloader(okHttpClient))
```

The `okHttpClient` parameter is therefore **unused**, and Fetch2 uses its own HTTP client. So
downloads get none of:

- `PlexInterceptor`'s headers (the token is instead pasted per-request in `makeDownloadRequest`)
- **cu-10's 401 re-auth** — a rotated server token kills downloads with no recovery
- **cu-11's connection tiering** — downloads may take a relay while playback takes LAN
- **cu-42's cleartext policy** — the network security config still applies at the platform
  level, but nothing else does

Fix: wire `OkHttpDownloader`. Find out *why* it was broken rather than re-disabling it — the
`fetch2okhttp` artifact may simply have been missing.

### 4. Pinned to 3.3.0; 3.4.1 fixes an Android 14+ bug

3.4.0/3.4.1 address "slow download start on Android 14+" and remove a broadcast-receiver
dependency. This app is `targetSdk 36`. Cheap upgrade, plausibly a live symptom.

## Notes

- Do **not** migrate to Media3 `DownloadManager` without revisiting [[cu-12]]'s reasoning:
  7 files depend on the plain-file layout, and `MoveSyncLocationWorker` moves those files
  between SD card and internal storage.
- Item 1 is testable without a device: `refreshTrackDownloadedStatus` takes its directory from
  `prefsRepo`, so a temp dir with a short file and a matching `size` in a fake repository
  exercises it.
- Items 2–4 need a real download to verify properly; add them to [[cu-73]].

## Acceptance Criteria

- [x] A file shorter than `MediaItemTrack.size` is never marked cached; covered by a test
      using a temp directory
- [x] `size == 0L` (Plex reported none) handled explicitly rather than accidentally
- [ ] `cachedFilePattern` tightened — still `\d*\..+`, which matches more than it should
- [ ] Interrupted downloads resume on launch or on network return, with the retry count raised
- [ ] `onError` distinguishable from `onCompleted` in both listeners
- [ ] `OkHttpDownloader` wired, so downloads inherit re-auth, tiering and interceptors — or a
      written reason why it cannot be
- [ ] Fetch2 on 3.4.1
- [ ] Verify loop green; live-download checks added to [[cu-73]]

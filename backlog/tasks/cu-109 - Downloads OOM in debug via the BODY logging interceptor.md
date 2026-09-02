---
id: cu-109
title: Downloads OOM in debug via the BODY logging interceptor
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-02 10:45'
labels:
  - R1
  - trust
  - bug
dependencies: []
priority: high
milestone: m-1
ordinal: 4600
---

## Description

Found by the [[cu-73]] live pass on 2026-09-02, session 3. **Downloading a book crashes a debug
build with `OutOfMemoryError`**, and this is the answer to issue **#83**, which [[cu-12]] left open
as "either in Fetch2's internals or stale".

It is neither: it is our own Dagger wiring.

### Evidence

Tapped Download on a real 293 MB m4b (*Malleus*, 10h13m) over the LAN route. The request started
correctly — `206 Partial Content` in 74 ms — then RSS climbed and the process died:

```
PSS: 248313 KB -> 302301 KB -> 349786 KB -> process gone
```

```
E AndroidRuntime: FATAL EXCEPTION: LibGlobalFetchLib
E AndroidRuntime: Process: io.github.mattpvaughn.chronicle.debug, PID: 24810
E AndroidRuntime: java.lang.OutOfMemoryError: OutOfMemoryError thrown while trying to throw an exception; no stack trace available
E AndroidRuntime: FATAL EXCEPTION: Okio Watchdog
E AndroidRuntime: java.lang.OutOfMemoryError
```

**Zero bytes** reached disk — the download directory stayed empty. The crash lands on
`LibGlobalFetchLib`, Fetch2's own download thread, ~50 s after enqueue.

### Mechanism

Two individually reasonable decisions that are wrong together:

1. `AppModule.loggingInterceptor()` installs `HttpLoggingInterceptor` at **`Level.BODY`** whenever
   `LOG_NETWORK_REQUESTS` (= `BuildConfig.DEBUG`) is set, and adds it to the
   `OKHTTP_CLIENT_MEDIA` client (`AppModule.kt:237`).
2. [[cu-76]] then routed **downloads** through that same client:
   `.setHttpDownloader(OkHttpDownloader(okHttpClient))` (`AppModule.kt:201`), deliberately, so
   downloads would inherit cu-10's 401 re-auth and cu-11's tiering. That part is right and worth
   keeping.

`Level.BODY` **buffers the entire response body in memory** in order to log it. So since cu-76,
every download tries to hold the whole audiobook in RAM. 293 MB OOMs on this device; a 2 GB m4b —
the exact case the cu-73 checklist wants tested — cannot possibly work.

Note this also explains why the API-level reasoning in cu-12 found nothing: there is no OOM
mechanism in *app* code, and none in Fetch2 either. It is in the client Fetch2 was handed.

### Severity

**Debug builds only.** `LOG_NETWORK_REQUESTS = BuildConfig.DEBUG`, so release gets `Level.NONE` and
streams normally. Users are unaffected and #83 is *not* a shipping bug.

But it matters more than "debug only" suggests, because it makes **downloads untestable in the only
build an agent or the owner can instrument**. It currently blocks six [[cu-73]] checklist items:
the 2 GB completion check, the Wi-Fi-drop resume, the kill-and-relaunch resume, offline playback of
a downloaded book, the download-route check, and the range-resume byte-count check. For a repo whose
first principle is that an agent must be able to close the loop, that is a real defect.

## Acceptance Criteria

- [x] A debug build downloads a 293 MB book to completion without an OOM, with bytes landing on disk
- [x] Downloads still go through OkHttp, keeping cu-10 re-auth and cu-11 tiering ([[cu-76]]'s gain
      must not be reverted to fix this)
- [x] Request/response **headers** for downloads remain loggable in debug — the diagnostic value of
      seeing a download's 206 and its `Content-Range` is the whole reason the interceptor is there
- [x] A test pins the property, so a future `Level.BODY` cannot silently reintroduce it. Asserting
      on the interceptor's configured level is enough; do not try to assert on memory
      — went further: the tests build the **real** client from the real module and inspect what it
      actually carries, so they also catch a fix that drops the inherited interceptors
- [x] Memory watched across a large download and recorded in [[cu-73]], since that was #83's
      original question

## Notes on likely shape

Preferred fix is a **separate downloader client**: clone the media client and either omit the
logging interceptor or set `Level.HEADERS`. That keeps full `BODY` logging for the small
metadata/timeline calls where it is genuinely useful (it is how cu-9's `time=0` and the scrobble
storm were both caught) while never buffering a media body.

`Level.HEADERS` on the shared client is the one-line alternative, but it would remove body logging
from the API calls too, which would have made several cu-73 findings much harder to see. Prefer
keeping both, not trading one for the other.

**Playback is not exposed — checked, not assumed.** Media3 streams through
`DefaultHttpDataSource` (`AudiobookMediaSessionCallback.kt:53`, wrapped in a `DefaultDataSource`
factory at :441), which is Media3's own HTTP stack and never touches the OkHttp media client. That
matches the observation: 4.5 minutes of continuous streaming of the same 293 MB file caused no
memory growth, while merely *downloading* it died in 50 s. So the fix is confined to the download
client.

## Implementation Notes

Fixed 2026-09-02, on the tablet that found it.

### The change

A third OkHttp client, `OKHTTP_CLIENT_DOWNLOADER`, **derived from the media client** with
`newBuilder()` rather than built in parallel:

```kotlin
mediaClient.newBuilder()
  .apply {
    val survivors = interceptors().filterNot { it is HttpLoggingInterceptor }
    interceptors().clear()
    interceptors().addAll(survivors)
    addInterceptor(HttpLoggingInterceptor().setLevel(downloadLogLevel()))
  }
  .build()
```

`newBuilder()` is the load-bearing choice. A second `OkHttpClient.Builder()` would have been a copy
to keep in sync forever, and would silently drop `plexMediaInterceptor`, cu-10's
`PlexTokenAuthenticator` and the cu-11 timeouts — which is exactly cu-76's gain. Deriving means
downloads inherit everything by construction and only the logger is replaced.

Filtering by **type** rather than removing a known instance, because the media client could grow a
second logger later; `interceptors()` on the builder is a mutable view with no "replace", hence the
clear-and-refill.

`downloadLogLevel()` is capped at `HEADERS` in debug (`NONE` in release), not `NONE` throughout: a
download's status line and `Content-Range` are how you tell a resume from a restart, which is a live
[[cu-73]] item. Body logging stays on the **media** client, where bodies are small and where it is
genuinely valuable — it is how cu-9's `time=0` and the `/:/scrobble` storm were both caught. Keeping
both was the point; `Level.HEADERS` on the shared client would have been one line but would have
cost those diagnostics.

### Verification

**Device, same 293 MB book that OOM'd:**

| | before | after |
|---|---|---|
| bytes on disk | **0** | **293,768,919** (complete) |
| peak PSS | 350 MB, then dead | **162 MB** |
| outcome | `OutOfMemoryError` ~50 s in | `CachedFileManager: COMPLETED` |

162 MB is *below* the 248 MB baseline before the download started, which is the signature of
streaming rather than buffering. Zero `OutOfMemory` entries in the crash buffer.

**Integrity checked, not assumed:** the downloaded file is byte-identical to the server's copy —
`cmp` exit 0, both `md5 db0e9818e35331444f27679f45a8eb21`. (A first comparison appeared to mismatch;
that was my own error, hashing on-device while the file was still being written. Worth noting because
"same size, different hash" is alarming enough to act on, and the fix is to let the write settle.)

**Then the items it unblocked**, both immediately: the book is marked `cached=true` /
`isCached=true`, and with **aeroplane mode on** it plays from
`file:///storage/.../151313.m4b` — scheme intact, confirming cu-83 — with `AudioFlinger` actively
mixing and no `ExoPlaybackException`.

### Tests

`DownloadLogLevelTest`, 7 tests, Robolectric (the module needs an `Application`). Unlike
`ConnectionTimeoutTest`, which can only pin constants and says so, these construct the real client
and inspect it.

**Sabotage-verified three ways**, each caught by a different subset:

1. level back to `BODY` -> 3 fail, including `the download client never logs bodies`
2. forget to strip the inherited logger -> 3 fail, including `the media client's body logger is not
   carried over` (the actual real-world mechanism)
3. `OkHttpClient.Builder()` instead of `newBuilder()` -> 2 fail: `every non-logging interceptor
   survives` and `the authenticator and timeouts are inherited`

Sabotage 3 matters most: it is the *tempting* fix, and it silently reverts cu-76.

Coverage 28.39% -> 28.44%; 562 unit tests, 0 failures.

### The `HEADERS` choice paid off within the hour

Keeping the level at `HEADERS` rather than dropping to `NONE` was argued on diagnosability grounds
before there was a concrete use for it. The very next [[cu-73]] item needed exactly that output: to
tell a *resume* from a *restart* after killing the app mid-download, the evidence is

```
Range: bytes=966369280-
Content-Range: bytes 966369280-1639241763/1639241764
```

which `NONE` would have hidden entirely. Recorded because it is a concrete argument against the
one-line alternative, not just a preference.

Also verified on a **1.64 GB** book, not only the 293 MB one: memory stayed flat at 141–144 MB
across a climbing byte count and finished at 135 MB. Flat memory against rising bytes is the actual
streaming proof; a completion check alone cannot distinguish streaming from a lucky buffer.

**One measurement trap:** Fetch2 preallocates, so `stat` reports the file at full size within a
second of starting. Progress lives in `_written_bytes` in `databases/LibGlobalFetchLib.db`.

### Follow-up

The **playback** path was checked and is not exposed: Media3 uses `DefaultHttpDataSource`, its own
HTTP stack, never this client. Consistent with 4.5 min of clean streaming of the same file while a
mere download died in 50 s.

---
id: cu-109
title: Downloads OOM in debug via the BODY logging interceptor
status: To Do
assignee: []
created_date: '2026-09-02'
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

- [ ] A debug build downloads a 293 MB book to completion without an OOM, with bytes landing on disk
- [ ] Downloads still go through OkHttp, keeping cu-10 re-auth and cu-11 tiering ([[cu-76]]'s gain
      must not be reverted to fix this)
- [ ] Request/response **headers** for downloads remain loggable in debug — the diagnostic value of
      seeing a download's 206 and its `Content-Range` is the whole reason the interceptor is there
- [ ] A test pins the property, so a future `Level.BODY` cannot silently reintroduce it. Asserting
      on the interceptor's configured level is enough; do not try to assert on memory
- [ ] Memory watched across a large download and recorded in [[cu-73]], since that was #83's
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

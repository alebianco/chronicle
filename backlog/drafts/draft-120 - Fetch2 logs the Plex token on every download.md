---
id: DRAFT-120
title: Fetch2 logs the Plex token on every download
status: Draft
assignee: []
created_date: '2026-09-02'
labels: [R0, security, downloads]
dependencies: []
priority: high
milestone: m-0
---

## Description

Found during the cu-73 live pass (session 4), on the first download against the real server.

`AppModule` builds the Fetch2 configuration with logging on unconditionally:

```kotlin
.setHttpDownloader(OkHttpDownloader(okHttpClient))
.enableLogging(true)          // <- not gated on BuildConfig.DEBUG
.build()
```

Fetch2 logs `DownloadInfo` via its `toString()`, which includes the **whole headers map**. So
every enqueue/queue/progress transition writes the account's server token to logcat:

```
D LibGlobalFetchLib: Enqueued download DownloadInfo(id=..., url='https://192-168-1-54....plex.direct:32400/library/parts/296922/1786560717/file.m4b?download=1',
  headers={X-Plex-Token=<a working token>}, ..., tag=This Inevitable Ruin, ...)
```

Observed **three times** for a single download (`Enqueued`, `Added`, `Queued`), before any bytes
transferred.

### Why this matters, and why the existing guard missed it

The repo already treats this as a hard rule — CLAUDE.md: *"Never log an auth token."* But
`TokenLoggingTest` scans **`Timber.x(...)` calls in our own sources**. It cannot see a third-party
library's internal logging, so this was invisible to the gate. The rule was enforced only where we
happened to be the caller.

Worse, `enableLogging(true)` is not gated, so this happens in **release builds** as well, where
`Timber` plants no tree and our own logging goes quiet. Logcat persists across the session and is
routinely pasted wholesale into bug reports — the same reasoning that motivated `TokenLoggingTest`
applies verbatim here.

Note the same file gates other things on `BuildConfig.DEBUG` (the OkHttp log level, a few lines
above), so this reads as an oversight rather than a deliberate choice.

### Fix sketch

1. Gate it: `.enableLogging(BuildConfig.DEBUG)`. Cheapest, and removes the release exposure.
2. Better, because it keeps debug logs useful without the credential: pass a custom
   `com.tonyodev.fetch2.Logger` that redacts the `headers` map (or logs only id/status/progress).
   Fetch2 accepts one via `FetchConfiguration.Builder.setLogger(...)`.
3. Consider whether the token belongs in a *header* on the download request at all, versus the
   `X-Plex-Token` query parameter already present on the URL — the URL is logged too, so if the
   query param is what actually authenticates, the header is redundant exposure. Worth checking
   before assuming both are needed.

Prefer (2) over (1) alone: debug builds are where these downloads get diagnosed, and the download
items still open on [[cu-73]] need readable logs.

## Acceptance Criteria

- [ ] No Fetch2 log line contains `X-Plex-Token` (or any token value), in debug **or** release
- [ ] Verified by grepping logcat across a full enqueue → progress → complete cycle on a real
      download, not just by reading the config
- [ ] A guard that can actually catch this class of leak — extend `TokenLoggingTest`'s reasoning
      to cover third-party logging, or assert on the configured Fetch2 `Logger`. A fix with no
      guard will regress the next time the config is touched.
- [ ] Decide (3): whether the download request needs the token in both the header and the URL

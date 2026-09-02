---
id: cu-120
title: Fetch2 logs the Plex token on every download
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-03'
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

- [x] No Fetch2 log line contains `X-Plex-Token` (or any token value), in debug **or** release
- [x] Verified by grepping logcat across a full enqueue → progress → complete cycle on a real
      download, not just by reading the config
- [x] A guard that can actually catch this class of leak — extend `TokenLoggingTest`'s reasoning
      to cover third-party logging, or assert on the configured Fetch2 `Logger`. A fix with no
      guard will regress the next time the config is touched.
- [ ] Decide (3): whether the download request needs the token in both the header and the URL
      — **deferred, see notes.**

## Implementation Notes

**Fixed by redacting, not by silencing.** `RedactingFetchLogger` implements Fetch2's `Logger`
interface (4 methods) and routes every line through Timber after stripping token values;
`AppModule` keeps `.enableLogging(true)` and adds `.setLogger(RedactingFetchLogger())`.

Silencing was the cheaper option and was rejected: these lines are how the download path is
diagnosed — cu-109's OOM inside Fetch2's own thread was found by reading them, and cu-73's
remaining download items still need them. The redacted line keeps id, namespace, url, file path,
status and tag; only the token value is replaced with `<redacted>`, and the *key* stays visible so
a reader can still see a token was attached.

**The regex matches both shapes the token appears in** — the headers map
(`headers={X-Plex-Token=…}`) and the request URL (`?download=1&X-Plex-Token=…`) — case-insensitively,
because the header's case is set by the interceptor and a future rename must not silently reopen
the leak.

**Verified on the device, not just in tests.** A real download of *Hell Divers* on the tablet
(API 32) against the live server, logcat cleared first:

- `X-Plex-Token=<8+ chars>` → **0 occurrences**
- `X-Plex-Token=<redacted>` → **5 occurrences**
- Fetch2 lines still present (8), still carrying id/url/file/status

Before the fix, this same flow wrote the token three times before a single byte transferred.

**Tests: 10, and they can fail.** Deleting the redaction (making `redact` the identity function)
fails **6 of 10** — checked deliberately, because a guard that cannot fail proves nothing. The
strings under test are real shapes copied from logcat, not invented ones. One test was added
during self-review after questioning whether the value-boundary character class handled a token
ending a single-quoted url; it does, so no fix followed — but the case is now pinned.

Suite 654 → 663 unit tests (the 10th arrived after the count), coverage ratchet rose
29.20 → 29.25.

**Deferred, deliberately:** whether the download request needs the token in *both* a header and
the URL query parameter. Both are now redacted, so the leak is closed either way, and removing one
is a change to request shape that wants its own task and its own live verification. Filed as a
follow-up rather than bundled in a security fix.

**Note on `TokenLoggingTest`'s scope.** It still scans only our own `Timber` calls, by design. The
new coverage for third-party logging is `RedactingFetchLoggerTest`, which asserts on the redactor
itself. Any *future* library that logs credentials will need the same treatment — the general
lesson is that "never log a token" is only enforced where we are the caller.

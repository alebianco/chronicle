---
id: DRAFT-129
title: Decide whether downloads need the token twice
status: Draft
assignee: []
created_date: '2026-09-03'
labels: [R2, security, downloads, cleanup]
dependencies: [cu-120]
priority: low
milestone: m-2
---

## Description

Split out of [[cu-120]], which closed the actual leak. This is the leftover question, deliberately
not bundled into a security fix.

A Fetch2 download request currently carries the Plex token **twice**:

- as a header, `X-Plex-Token: …`, added by `PlexConfig.plexMediaInterceptor`
- in the URL query string, `…/file.m4b?download=1&X-Plex-Token=…`

Both are redacted in logs now, so nothing leaks either way. But if only one of them actually
authenticates the request, the other is redundant exposure — it travels in more places (a URL ends
up in more logs, proxies and crash reports than a header does) for no benefit.

## What to work out

1. **Which one Plex actually honours** for `/library/parts/…?download=1`. Test against the real
   server: request with only the header, then with only the query parameter.
2. If the header suffices, **why the query parameter is there** — check whether it came from
   upstream, or from the cu-76/cu-109 work that moved downloads onto the app's OkHttp client.
   `PlexConfig` builds both; it may be that the `?download=1` URL is constructed independently of
   the interceptor.
3. Whether removing one breaks **resume**: Fetch2 re-issues the stored URL on a retry
   (`Range: bytes=…`), so a URL that authenticated at enqueue time must still authenticate later.
   A stored URL with an embedded token is also a stored *credential* on disk, in
   `LibGlobalFetchLib.db` — worth noting independently of the logging question.

That last point may be the real finding here: the download database persists the URL, so if the
token is in the URL it is also at rest in a SQLite file, which no amount of log redaction covers.

## Acceptance Criteria

- [ ] Established which token placement Plex requires for a download request, by testing both
      against the live server
- [ ] The redundant one removed, or a note recorded explaining why both are needed
- [ ] Resume-after-retry still works (Fetch2 re-issues the stored URL with a `Range` header)
- [ ] If the token stays in the URL, decide whether storing it in `LibGlobalFetchLib.db` is
      acceptable, or whether the URL should be rebuilt at request time instead

## Related

- [[cu-120]] — closed the logging leak; this is the follow-up it deferred
- [[cu-76]] / [[cu-109]] — the work that put downloads on the app's OkHttp client
- [[cu-108]] — credentials at rest live in their own prefs file; the same instinct applies here

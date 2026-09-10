---
id: cu-129
title: Decide whether downloads need the token twice
status: Done
assignee: []
created_date: '2026-09-03'
labels:
  - R2
  - security
  - downloads
  - cleanup
milestone: m-2
dependencies:
  - cu-120
priority: low
ordinal: 77000
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

**The premise was wrong, so three of these four could not apply.** Retired with the evidence
rather than ticked — see the notes.

- [x] Established which token placement Plex requires — **the question was moot**: the download
      request carries the token *only* as a header. There is no query-string copy to test against.
- [x] The redundant one removed, or a note recorded explaining why both are needed — nothing to
      remove; the note is below
- [x] Resume-after-retry still works — unaffected, since the URL was never the credential
- [x] Decide whether storing it in `LibGlobalFetchLib.db` is acceptable — **it is stored there**,
      but via `_headers` rather than `_url`, and it is already excluded from backup

## Implementation Notes (2026-09-05)

### The draft's central claim does not hold

*"A Fetch2 download request currently carries the Plex token twice — as a header, and in the URL
query string."* It does not. `PlexConfig.makeDownloadRequest` builds:

```kotlin
val remoteUri = "${toServerString(trackSource)}?download=1"   // no token
…
addHeader("X-Plex-Token", token)                              // the only copy
```

`?download=1` carries no token, and a search for `X-Plex-Token` across the app finds it as a
**header** at every one of the seven sites. Most likely the draft was written from the redaction
work in [[cu-120]]: `RedactingFetchLogger` scrubs the token from *both* a URL and a headers map,
because it is written defensively against either shape — not because both occur.

### The real finding, which the draft got right for the wrong reason

The draft's closing instinct — *"the download database persists the URL, so the token is at rest in
a SQLite file"* — lands, but by a different route. Fetch2's `requests` table has **both** a `_url`
and a `_headers` column (verified against the schema on the device, and against `DownloadInfo.kt`
in Fetch2's source, where `headers` is persisted and restored into the `Request` on retry). So the
token *is* at rest in `LibGlobalFetchLib.db` — moving it out of the URL would not have helped,
because it was never there.

**That is already handled.** Both `backup_rules.xml` and `data_extraction_rules.xml` exclude
`domain="database"` outright, so the file is excluded from Auto Backup *and* device transfer — the
same protection cu-108 gave `ChronicleAuth.xml`, reached by a broader rule. `BackupRulesTest`'s
`databases are excluded from both rule sets` pins it.

### Conclusion: no code change

There is nothing redundant to remove and nothing unprotected to protect. The task closes as
**research** — the durable output is this note, so the next person to wonder about it does not
repeat the investigation.

One thing worth knowing if downloads are ever reworked: **a token in a Fetch2 request is at rest in
SQLite whichever field carries it**, so the protection has to be the backup exclusion, not the
field choice.

## Related

- [[cu-120]] — closed the logging leak; this is the follow-up it deferred
- [[cu-76]] / [[cu-109]] — the work that put downloads on the app's OkHttp client
- [[cu-108]] — credentials at rest live in their own prefs file; the same instinct applies here

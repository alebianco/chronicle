---
id: cu-160
title: toServerString doubles the slash when both sides have one
status: Done
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - bug
milestone: m-2
dependencies:
  - cu-33
priority: low
ordinal: 76000
---

## Description

`PlexConfig.toServerString` is the join every Plex URL in the app is built from, and its KDoc says
it accounts for "trailing/leading `/`s". For three of the four slash combinations it does. For the
fourth — base ends with `/` **and** path starts with `/` — it strips the path's slash and then adds
one back:

```kotlin
if (baseEndsWith && pathStartsWith) {
  "$url/${relativePath.substring(1)}"   // "https://server:32400/" + "/" + "library/..."
}
```

so the result is `https://server:32400//library/sections`. That branch exists specifically to
*prevent* the doubled slash and instead guarantees it.

Found while writing `PlexConfigUrlTest` during [[cu-33]] — the class was untestable before that
carve because it fetched its `Context` from the service locator.

**Not fixed there deliberately**: cu-33 is a DI refactor, and changing how every Plex URL in the app
is constructed is not something to smuggle into one. `PlexConfigUrlTest` characterises the current
behaviour with a test named `a trailing slash and a rooted path currently double the slash`, so the
fix will fail loudly against a test that says what it is doing.

## Impact — unknown, establish before fixing

Whether this bites at all depends on whether any caller passes a base URL with a trailing slash.
`ConnectionChooser` builds `url` from `/api/v2/resources` connection `uri` values, which Plex
appears not to terminate with `/`. So this may be latent. Establish that first: if no caller can
reach the branch, the honest fix may be to delete it rather than correct it.

Note a doubled slash is **not** cosmetic to Plex — a path is matched, not normalised — so if any
route does reach it, requests on that route are failing today.

## Acceptance Criteria

- [x] Determine whether any live caller supplies a base URL with a trailing slash (log or assert
      across a real refresh, not by reading) — **none does**, verified against the live server
- [x] Either fix the branch to emit a single slash, or delete it as unreachable — with the evidence
      for which, recorded in the notes — **fixed, not deleted**; reasoning below
- [x] `PlexConfigUrlTest` updated: the characterisation test becomes an assertion of the intended
      behaviour, and the "only the both-slashes case diverges" test collapses to all four agreeing
- [x] Verify loop green

## Implementation Notes

### Reachability: measured, not read

A temporary probe logged `url` and whether the both-slashes branch was taken, across a real refresh
against the household ANTARES server (Plex 1.43.3). **Zero hits.** The `/api/v2/resources` response
carries three connections — LAN, direct WAN and relay — and **none of their `uri` values ends in
`/`**:

```
https://192-168-1-54.<hash>.plex.direct:32400   (local)
https://87-15-17-44.<hash>.plex.direct:32400    (WAN)
https://172-104-247-122.<hash>.plex.direct:8443 (relay)
```

`ConnectionChooser` selects one of those verbatim, and the only other writer of `url` is
`PLACEHOLDER_URL`, which has no trailing slash either. So the branch was unreachable in practice —
the suspicion in the task description was right.

The fixtures agree (0 of 4 `uri` values have a trailing slash), but they were checked *second*: per
the cu-24 rule, a fixture written to match the code proves nothing, so the live server is the
authority here.

### Fixed rather than deleted, and why

The task offered deletion as the honest alternative if the branch proved unreachable. It is fixed
instead, because **`PlexConfig.url` is a public `var`**. Nothing writes a trailing slash today, but
the type permits it and the tests already set it directly — so deleting the normalisation would
leave a silently broken path (a doubled slash 404s; Plex matches a path rather than normalising it)
for a hole that costs one line to close.

The four-branch conditional collapsed to one expression:

```kotlin
fun toServerString(relativePath: String): String =
  "${url.trimEnd('/')}/${relativePath.trimStart('/')}"
```

This also handles repeated slashes on either side, which the branching version did not — a test
covers it.

### Verification

`PlexConfigUrlTest` is 4 cases and **sabotage-verified**: replacing the body with a naive
`"$url/$relativePath"` fails all four. On-device against the live server after the change: 13 book
covers rendered, **0 responses with a doubled slash, 0 404s** — every Plex URL in the app is built
by this function, so a regression here would have emptied the library.

**Closed `Done`**: no screen changed and no product choice was made. The proof is four tests plus a
reproducible on-device check.

---
id: cu-160
title: toServerString doubles the slash when both sides have one
status: To Do
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - bug
milestone: m-2
dependencies:
  - cu-33
priority: low
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

- [ ] Determine whether any live caller supplies a base URL with a trailing slash (log or assert
      across a real refresh, not by reading)
- [ ] Either fix the branch to emit a single slash, or delete it as unreachable — with the evidence
      for which, recorded in the notes
- [ ] `PlexConfigUrlTest` updated: the characterisation test becomes an assertion of the intended
      behaviour, and the "only the both-slashes case diverges" test collapses to all four agreeing
- [ ] Verify loop green

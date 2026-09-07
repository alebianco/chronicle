---
name: plex-integration
description: Use when touching Plex API calls, PlexService, auth tokens, connection selection, metadata parsing (narrator/series/genre tags), the series index parser, search, or the mock/fake fixture servers. Covers the Style/Mood convention hack and fixture routing traps.
---

# Plex integration

Plex's audiobook support is a **convention hack**, not a first-class feature. Treat every endpoint
below as community-documented and keep it wrapped behind repositories / the `MediaSource` seam:
`/:/timeline`, scrobble and websockets are not guaranteed.

## Auth tokens

**Resolved in one place, and empty counts as absent.** `PlaybackSession.authToken` is the
only statement of the precedence — **server access token, then the profile's, then the account's**.

It was written out **twice** before (`AudiobookMediaSessionCallback`, `ServiceModule.plexDataSourceFactory`)
and both were wrong the same way: they used `?:`, but neither `ServerModel` nor `PlexUser` stores
null for a missing token — `asServerModel` writes `accessToken = this.accessToken ?: ""`. So a
server reporting no token of its own — **the ordinary case for a server the user owns** — stored
`""`, won the elvis, and authorized every media request with an *empty* `X-Plex-Token` while a good
account token sat unused.

`PlaybackSession` also owns the `/playQueues` session handshake; its failure is logged and
swallowed on purpose (the media is already resolved, and the endpoint is unofficial).

**Never log a token.** `TokenLoggingTest` fails the build on any `Timber` call that interpolates
one — it caught three live leaks, including one logging *two* tokens per media item. Logging
*presence* (`token.isNotEmpty()`) is fine.

### 401 re-auth covers the server token only

`PlexTokenAuthenticator` re-fetches the server access token from `/api/v2/resources` and retries
**once**. It cannot recover an *account* token: Plex has no refresh token, and a new one needs a
human approving an OAuth PIN in a browser. A 401 that survives the retry means the account is
signed out — the app says so and keeps playing cached files.

**Don't add a retry loop here** — most of that class's tests assert it gives up, because looping
would hammer plex.tv. Plex tokens never expire on a timer; they are invalidated by an event
(password change with "sign out connected devices", server re-claim).

### Account state is three-way (decision-17)

`AccountAuthState` is `Authenticated` / `Unknown` / `Revoked` — a boolean could not tell "known
fine" from "could not check".

**Only a successful, parseable negative answer may set `Revoked`.** A timeout, 5xx, offline or
malformed body is `Unknown`, or the app nags users on trains.

Two traps: Plex invalidates **no token** when a device is removed at plex.tv, so nothing reactive
can notice it — the check is `GET /api/v2/devices` matched on this install's own
`X-Plex-Client-Identifier` (`/api/v2/resources` cannot answer; its `clientIdentifier` is the
*server's*). And **every login mints a new identifier**, so several rows share a device name and
matching on name is wrong.

A revoked account stays `LOGGED_IN_FULLY` on purpose — `NOT_LOGGED_IN` routes through
`Navigator.showLogin()`, which calls `plexConfig.clear()` and wipes server, library and
connections, so an expired token used to cost the user their whole configuration.

### Credentials file split

All three secrets (account token, server access token, serialized user) go through
`credentialString` / `putCredential` / `removeCredential`, which read `ChronicleAuth.xml` first
with a legacy `Chronicle.xml` fallback and purge the old copy on write.

Auto Backup excludes `ChronicleAuth.xml` and *not* `Chronicle.xml` (`data_extraction_rules.xml` +
`backup_rules.xml`, one per API level — keep them in agreement; `BackupRulesTest` enforces it by
parsing `path=`, not by substring).

## Connections are tiered, not raced

`ConnectionChooser` tries **LAN → direct WAN → relay**, each tier getting a 1.5s budget before the
next also starts (earlier attempts keep running, so a slow LAN address can still win). The **last**
tier is awaited for a real answer, which is what keeps a LAN-only server working.

`Connection.relay` comes from `/api/v2/resources` and is checked *before* `local`, because Plex can
report a relay route with `local = 1`.

**Don't reorder `ConnectionTier`** — its declaration order *is* the preference order.

## Metadata is a tag convention

**Narrator = `Style` tags, series = `Mood` tags.** Never treat these as music semantics. The
convention is **Audnexus's, not seanap's** — seanap's guide is an *ID3* convention (`TCOM` =
narrator, `TPE1` = author/narrator) and never touches Plex's Style/Mood fields; only the
Audnexus.bundle agent writes them.

**`Mood` carries authors as well as series.** `add_series_to_moods` writes `"Series: <name>"`
unconditionally, while `add_authors_to_moods` writes a **bare** author name, gated on the agent's
`store_author_as_mood` preference (verified in `Contents/Code/update_tools.py`). Plex returns moods
alphabetically, so taking the first non-empty tag filed any book whose author sorts before its
series under a series named after the author. `seriesName()` prefers a **prefixed** tag and falls
back to an unprefixed one only when nothing is labelled. It reproduces only on servers with that
preference enabled — which is why fixtures written to match the code never showed it.

**Both are detail-only:** `/library/metadata/{id}` carries them;
`/library/sections/{id}/all` does **not** — verified against fixtures captured from a real Plex
1.43.3 server, and there is no `includeFields`/`includeTags` that would add them.
`FacetList.unknownCount` exists so the UI is obliged to say how partial the index is.

### Tag index seeding

`TagIndexSeeder` enumerates a tag filter's distinct values
(`/library/sections/{id}/style?type=9`) and lists the books carrying each
(`/all?type=9&style={tagKey}`) — `1 + N` requests per field instead of one per book.

- Seeding runs **after** `Audiobook.merge` and **never overwrites a non-empty field**: the detail
  response is precise and this index is coarser, so overwriting would blank correct metadata on
  every refresh.
- Failure is per value and never fatal.
- **Routing trap:** `/library/sections/{id}/style` contains neither `/all` nor a query, so in both
  fixture servers it fell through to the bare-section rule and answered `libraries.json` — the
  seeder would have read a library list as a list of narrators. Both routers match the tag paths
  first now.
- Verified in python-plexapi's source and against fixtures, **not against a real server** — in
  particular whether a live Plex returns `key` as `/library/sections/1/style/301`.
- The *multi-id* route `/library/metadata/{id1},{id2},…` would be cheaper but is **spec-verified
  only**. There is no comma-joined metadata read anywhere in python-plexapi (a 2026-09-05 review
  claiming four call sites was wrong — those are `/library/sections/{id}/common` and a PUT, passing
  ids as an `id=` query parameter to different endpoints). Don't re-derive this.

### `@Json` names must be checked against a captured response

`plexGenres` carried **no** `@Json(name = "Genre")` for the life of the project, so Moshi looked for
a key literally called `plexGenres` and `Audiobook.genre` was empty against every real server —
while every test passed, because the hand-written fixtures were written to match the *code*.

The `*-real-shape.json` fixtures are captured from a real server and are the **authority**; pin new
parsing tests against those.

## The series index parser (decision-18)

Parsed from anywhere in `titleSort`, in **hundredths** — **not** from Plex's `index` (the album
ordering index, 1 for nearly every audiobook) and **not** from `Mood` (which carries the series
name without a number).

The parser was once **end-anchored** while both dominant taggers put the number at the *front*
(Audnexus writes `"<Series>, Book <n> - <Title>"`, seanap prescribes `"<Series> <n> - <Title>"`) —
so it read **1 of 8** real formats, the one being our own fixture.

- `SERIES_INDEX_PATTERNS` holds eight patterns tried **most specific first**, and that order is
  load-bearing: `audnexus` must precede `label-first`, or `"Book 2 of the Saga, Book 5"` reads 2.
- `audnexus_subseries` reads `<Series>, Book <n>, <Subseries> - <Title>` where the number
  is terminated by a comma. It must follow `audnexus`, and the `Book`/`Vol` label stays
  **required** — that requirement is the only thing stopping `"Warhammer 40,000"` from reading as
  book 40000, sabotage-verified.
- Values are hundredths (`SERIES_INDEX_SCALE`, so book 2 is `200`) because a novella genuinely sits
  at 1.5 — but they stay `Int`, because `NO_SERIES_INDEX` (0) is compared for equality and float
  equality against a sentinel is unreliable.
- `Book 0` reads as **unknown** on purpose (0 is the sentinel), so a prequel numbered zero sorts
  last. A test says so.
- Don't remove `Bk` or the loosest `comma-trail` pattern — `"Mistborn, Bk 2"` and `"Mistborn, 2"`
  were silently dropped when un-anchoring and restored after `BookFacetsTest` failed.

**The rules are data, not constants.** They live in `data/model/SeriesIndexPatterns.kt` as named
`SeriesIndexPattern`s with named capture groups; `installSeriesIndexPatterns(patterns, order)` lets
a user's rules go **before**, **after** or **instead of** the built-ins. Modelled on tvnamer but
deliberately avoiding three of its failure modes (a user config there replaces every built-in, its
issue #191; required groups validated only after a match; no way to see why a rule did not match,
its #216 — hence `SeriesIndexPatternSet.explain()`).

Two traps:
- **Do not use `RegexOption.COMMENTS`** — like Python's `re.VERBOSE` it strips literal spaces.
- **`MatchResult.groups["name"]` throws** for a group the *matching* pattern never declared, rather
  than returning null — four of the eight built-ins declare no `series` group and crashed every
  match until every named read went through `namedGroupOrNull`.

User rules live in `series-index-rules.json` in the app's files directory:
`{version, order, rules:[{name, pattern, description}]}`, absent by default. **Every** failure
degrades to the built-ins (malformed JSON, newer version, unknown order, nameless rule,
uncompilable regex). `order` is parsed as a *string*, not a Moshi enum, since an unknown constant
would make Moshi reject the whole file and take the valid rules with it. The load runs off the main
thread (StrictMode penalises a disk read in `Application.onCreate`) and is launched, not awaited.

**The tester UI is not optional polish** — tvnamer's #216 is a user who could not tell
whether their pattern or the tool was wrong.

## Search is local, not `/hubs/search`

`BookSearch.kt` scans the synced library in memory over four fields (title, author, narrator,
series). `/hubs/search` cannot be the foundation: its results **omit `Style`/`Mood`** so it cannot
answer a narrator or series query at all; it is unavailable in offline mode; its `sectionId` only
*re-orders* rather than filtering; and `limit` defaults to **3 per hub**. It is still worth adding
as a *complement* for unsynced books.

Two matching traps: it is **Damerau**-Levenshtein because plain Levenshtein charges 2 for a
transposition (the commonest typo), and the cheap prefilter counts **characters, not bigrams** — a
transposition rewrites every adjacent pair, so a bigram prefilter discards the very matches the
fuzziness exists for. Fuzzy matching is floored at 4 characters.

**Matching runs over a projection:** `searchGrouped` reads five columns
(`BookDao.searchProjection`), matches, then fetches only the hits by id — **196.7 → 75.4 ms at
10,000 books**. `withRealBooks` swaps the real rows back. **Do not make the matching generic** — the
first attempt did, and `GroupedSearchResults<T>` leaked into every UI call site for no benefit.

Two measurement traps: a fixture of `"Series ${i % 200}"` makes any query fuzzy-match hundreds of
neighbours and reported the change as 2% *worse*; the rewritten fixture then measured `hits=0`.
Measure with a query matching a realistic slice, and **print the hit count beside the timing**.
`SearchReadCostTest` re-runs it in one command.

## Fixture routing

**`retrieveAlbum` and `retrieveChapterInfo` are the same URL** —
`/library/metadata/{id}?includeChapters=1` — so nothing in the *request* says whether an album or a
track is expected back.

Both fixture servers (`MockPlexServer` for the debug app, `FakePlexServer` for unit tests) once
routed every `/library/metadata/*` to `track-with-chapters.json`. An album request therefore got
tracks, and since `bookDao.update` is `@Insert(REPLACE)` a **track was inserted into the
`Audiobook` table** and appeared on home shelves as a phantom book.

- Both routers key on the **id** now: `album-<id>.json` per book, one album each — a file listing
  all of them would make `fetchBookAsync`'s `firstOrNull()` answer the same book for every request.
- Each track has its own `track-<id>-chapters.json`: one fixture holding all three tracks answered
  *track 2001's* chapters for every track, and the player read "Ch 1 of 9" for a 7-chapter book.
- A chapter spanning a track boundary legitimately appears on **both** tracks, so a count above the
  distinct-chapter count is correct.
- `PlexFixtureContractTest` pins this, because the routing exists **twice** and both copies had the
  same defect.
- `asAudiobooks()` refuses a *known* non-album `type`; an absent or unrecognised one is **accepted**
  deliberately, since Plex does not guarantee the field and a strict check would empty the library
  of a server that omits it.

**The fixture trap, generally:** a fixture written to match the code proves the code matches
itself. This has now bitten in four separate fields (genre, series index, source id, moods).

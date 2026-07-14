# Chronicle Audiobook Player — Ownership, Modernization & Feature-Gap Study

*Research date: 2026-07-05. Method: multi-agent research (repo audit, live GitHub API fork/issue mining, web research on competitors and the Plex/Audiobookshelf/Jellyfin APIs), synthesized by the lead orchestrator. Every claim is tagged **[V]** verified (seen in code/diff/API/docs by an agent) or **[I]** inferred. Nothing below rests on the stale `other-forks.txt` — all fork data was re-pulled live from GitHub.*

---

## 1. Executive summary

**Chronicle is worth investing in, and the starting position is better than the quest brief assumed.** Upstream (`mattttvaughn/chronicle`) is not abandoned: its last commit (2025-11-16) was a large Android 14 + Media3 refactor, and our fork is fully synced with it (0 ahead / 0 behind) [V]. The codebase is already on Kotlin 2.1.20 / AGP 8.13.1 / Media3 1.3.0 [V]. The real debt is narrower and more actionable than "modernize everything."

**The five highest-leverage moves:**

1. **Ship the SDK-36 compliance sprint now — if Play distribution is the goal.** Google Play requires targetSdk 36 by **2026-08-31** — 8 weeks away — and we target 34; the 16KB page-size requirement also becomes binding with that SDK bump (check Fresco). Caveat: the existing Play listing belongs to upstream (#124); our fork shipping to Play means a new listing, so confirm distribution intent first (§10.1). Independent of Play: Room is an **alpha** build in production and should go stable regardless.
2. **Harvest `fabiogermann/chronicle` ("Chronicle Epilogue")** — a 214-commits-ahead, actively-released continuation fork (v0.60.4→v0.62.0, Jan–Jun 2026) that has already fixed our worst issue clusters: progress-reporting (the #88/#112/#68 position-loss family), Plex connection resiliency (#110/#103/#98), offline playback, Android Auto. Port module-by-module; do **not** rebase (it raised minSdk to 33 and jumped a toolchain generation) [V].
3. **Fix the "client mishandles data the API already provides" bugs.** Chronicle already fetches chapters (`includeChapters`), collections, and per-track progress; several top issues (#119/#76/#12 chapters, #113 cover art) are mapping/UI bugs, not API gaps — small, high-visibility wins [V].
4. **Build the metadata & continue-listening layer Plex won't give us.** Plex's on-deck/continue-listening hubs are not populated for music-type libraries [V]; narrator lives in `Style` tags and series in `Mood` tags (Audnexus convention) and Chronicle surfaces neither. A client-built "Continue Listening" row + narrator/series facets closes most of the gap to Prologue.
5. **Carve the backend seam before any second backend.** A dead `MediaSource` abstraction already exists in the code (never wired, all `TODO()`) [V]. Making it real with Plex as sole implementation is a medium (2–4 wk) refactor; an Audiobookshelf adapter afterwards is small-medium (1–2 wk). Doing ABS first would roughly double the cost.

**Strategic caveat:** fabiogermann's fork is the de-facto active community hub (own site, subreddit, 21 stars, forks of its own). The collaborate-vs-harvest-vs-differentiate choice (§10.2) is a **gate before Phase 1**, since Phase 1 assumes harvesting.

**The owner's three known Plex pain points, referenced throughout as pain #1/#2/#3:** **#1** offline-download reliability, **#2** play-tracking/progress fidelity, **#3** cross-device sync.

---

## 2. Current-state fact sheet & upstream health

### Our tree (`alebianco/chronicle`, branch `feature/agentic-dev`) [V]

| Area | State |
|---|---|
| Toolchain | Gradle 8.13, AGP 8.13.1, Kotlin 2.1.20, Kotlin DSL + version catalog |
| SDK | minSdk 27 · target/compileSdk **34** |
| Playback | **Media3 1.3.0** (ExoPlayer + MediaSession + Cast) — already migrated off standalone ExoPlayer |
| DI / async | Dagger 2.54 (hand-rolled components) · Coroutines 1.7.3 · LiveData throughout (196 uses; ~no Flow) |
| Data | Room **2.7.0-alpha12** (alpha in prod) · Retrofit 2.11 / OkHttp 4.12 · Moshi 1.15.2 (reflection mode) |
| Downloads | Fetch 3.3.0 (`com.tonyodev.fetch2`; 3.4.1 exists upstream, single-maintainer) |
| Billing | Google-IAP 1.7.0 — upstream's Play listing sells a single `premium` SKU gating **offline downloads + playback speed** (`PREMIUM_IAP_SKU` in Constants.kt; strings.xml: "Premium is required for offline playback") [V]. fabiogermann's fork keeps the plumbing but the purchase listener is commented out and releases state premium features ship free [V]. See §10.5 |
| UI | Views + DataBinding (30/32 layouts), Material 1.9.0, single-activity + fragments, custom `Navigator.kt` |
| Features working | Plex stream/download (mp3/m4a/m4b), speed, auto-rewind, sleep timer, skip silence, offline, progress sync, Android Auto (playback only) |
| In-flight local work | R8/ProGuard hardening (~150 rules), `test_release_build.sh`, release-build docs in CONTRIBUTING.md |

### Upstream health verdict: **semi-active** [V]

- `mattttvaughn/chronicle`: 241 stars, 67 forks, 63 open issues, last commit 2025-11-16 ("Massive refactor for Android 14 + modern APIs (androidx.media3)"). Two open PRs: **#129** (playback stop / progress-save fixes) and **#114** (offline play = `elmerohueso:offline_play`).
- The Nov-2025 refactor commit closely matches fabiogermann's "#126 massive refactor" — upstream's recent activity likely absorbed fork work [I]. Expect slow upstream review cycles; plan to carry patches ourselves.

### Issue-backlog themes (open issues, tagged) [V]

| Theme | Count | Representative issues |
|---|---|---|
| Playback reliability | 14 | #112/#88/#68 position loss · #67 can't pause · #97 headphone controls · #74 lock screen · #32 random pauses |
| Metadata / chapters | 13 | #119 duplicate chapter names · #76 multi-file chapters · #12 wrong chapter shown · #113 cover art · #99 Auto metadata |
| Auth / connectivity | 9 | #110 stuck on login · #103 LAN-only unsupported · #98 wifi-only |
| Downloads / offline | 8 | #77 downloads disappear · #80 button unclickable · #107 offline play · #122 |
| Progress sync | 5 | #43 sync inconsistency · #17 force-sync UX |
| Library performance | 3 | #83 OOM on ~2GB files · #16 memory leak · #105 list view |

---

## 3. Market & product comparison

Sources: Prologue site/App Store, Kiesa iPhone audiobook shootout (kiesa.festing.org, 2025-03), seanap/Plex-Audiobook-Guide, Play Store / F-Droid survey. **Per-cell competitor claims are [I]-grade** (single-agent web summaries of listings/reviews); the Chronicle column is [V] from code/issues.

| Capability | Chronicle today | Prologue (iOS, Plex) | BookPlayer / MP3 Books (iOS) | Smart AudioBook Player | Voice/Listen (Android) | Audiobookshelf app | Symfonium |
|---|---|---|---|---|---|---|---|
| Backend | Plex only | Plex | Local + ABS/Jellyfin sync (BookPlayer) | Local | Local / Plex+ABS (Listen) | ABS | **Plex+Jellyfin+ABS+Navidrome** |
| Smart/auto rewind | ✅ auto-rewind | ✅ signature feature | ✅ | ✅ | ✅ | ✅ | ✅ |
| Per-book speed memory | ❌ (global) [I] | ✅ | ✅ | ✅ best-in-class | ✅ | ✅ | ✅ |
| Silence skip | ✅ fixed | — | — | ✅ configurable 0.1–0.9s | — | — | — |
| Bookmarks + notes | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Chapter-aware progress ("Ch 8/14 · 62%") | partial, buggy (#119/#12) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Continue-listening row | ❌ | ✅ (client-built) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Narrator / series facets | ❌ | ✅ | n/a | ~ | ~ | ✅ | ✅ |
| Stats / streaks | ❌ | ✅ | ✅ (MP3 Books) | — | ✅ (VoicePlus) | ✅ dashboard | ✅ |
| Material You / dynamic theming | ❌ | ✅ (cover-art theming) | — | — | ✅ | ✅ | ✅ |
| Multi-device sync robustness | weak (#88/#43) | ✅ | ✅ | n/a | ✅ | ✅ (websocket) | ✅ |
| Android Auto / CarPlay | ✅ basic (#106/#99 issues) | ✅ CarPlay | ✅ full-feature | ✅ | ✅ | ✅ | ✅ |
| Widgets / Wear OS | ❌ | ✅ widgets | — | — | ✅ widgets | third-party Wear | ✅ native Wear |
| Voice boost / EQ | ❌ | ✅ | ✅ | ✅ | — | — | ✅ |

**Where Chronicle leads:** it is still the only *native, purpose-built, open-source Plex audiobook player on Android* (fabiogermann's fork aside). Skip-silence and auto-rewind already exist. **Where it lags:** everything in the "listening comfort + organization" band — per-book speed, bookmarks/notes, chapter-aware UX, continue-listening, stats, theming — plus sync robustness. **The bar:** Prologue for Plex UX; Symfonium is proof that multi-backend (our Phase-3 ambition) is the emerging Android norm.

### 3.1 Design-language & taste evaluation

*Method: actual screenshots of each app were collected and reviewed side-by-side (2026-07-05): Chronicle (home + player, upstream Play/README shots), Prologue (player), Plexamp (player + browse), Pocket Casts (player + episode detail), Smart AudioBook Player (light+dark player), Audible (home), Libby (library home). Assessments below are direct visual inspection [V-by-inspection], not second-hand descriptions. The reviewed screenshots are preserved in `docs/design-references/` (uncommitted — they're third-party app screenshots; keep them out of any public repo). This section is the design brief for Phase 2 — specs should trace back to it.*

**Chronicle today — "engineer's UI: everything present, nothing composed."**
- One flat navy fill for every surface; no elevation, layering, or tonal variation; ~2018 Material 2 defaults with a stock cyan accent. No brand personality.
- Home: all-caps grey section headers; rigid grid with wasted space (a single offline book leaves the row empty); titles ellipsized at ~10 chars ("Sherlock H…"); low-contrast lavender author text (the WCAG issue LostQuasar/ZacharyCoumont fixed); bottom nav of three unlabeled, ambiguous icons.
- Player: raw `3:54:26/10:56:07` duration string; chapter title and book context get identical visual weight; cover art floats unused on flat navy (no color extraction, no blur); stock thin seekbar; un-Android "X" close button; bare text rows for the track list.

**Reference points and what each proves:**
- **Plexamp** — point of view: *"the content is the interface."* Full-bleed gradients extracted from album art make every screen feel custom; the waveform seekbar is a signature element that is decorative *and* functional; tight type hierarchy; controls recede. Lesson: restraint + one memorable signature element.
- **Prologue** — point of view: *"this is a book, not a song."* Near-black surface, warm paper-amber accent matched to cover warmth; display **serif** title over sans metadata (editorial pairing); pill chips for *Now Playing / Chapters (5 of 19) / Bookmarks*; progress labeled with chapter name + "8h 22m remaining" + negative countdown. Lesson: audiobook-native information hierarchy, bookish warmth.
- **Pocket Casts** (owner's daily driver) — *frequency layering*: transport controls central and oversized; weekly-use utilities (sleep, cast, share) demoted to a small bottom tray; rare settings behind overflow. **Labels under every ambiguous icon** ("Up Next", "Mark Played", "Archive"). One saturated accent (red) over neutral surfaces carries the whole identity. Lesson: the player layout blueprint + icon labeling.
- **Smart AudioBook Player** — visually poor (mismatched icon styles crammed in two toolbar rows) but the **best information design** in the study: book-level progress ("Read 1:56:48 of 6:33:57 · 30% · Left 5:46:26") *and* per-file progress with countdown always visible; explicit 1m/10s labeled skips; pocket-lock. Lesson: steal the two-level progress model, dress it with Prologue's typography. Also the standing proof that features without composition = "usable but not nice" — Chronicle's exact current failure mode.
- **Audible** — storefront density (credits, merchandising carousels) is a *counter-example* for a self-hosted library; but it normalized chapters-one-tap-from-player, car mode, and clip/bookmark on the transport bar.
- **Libby** — the Android proof that bookish warmth works: paper-cream surfaces, humanist serif headers, rounded cards, editorial curated shelves introduced by human-written blurbs, filter chips with result counts ("available now · 18k").

**The convergent grammar (patterns every well-liked audio app agrees on):**
1. Art-dominant player; chrome recedes; background neutral-dark or tinted from artwork.
2. One big central play + two labeled skip buttons; everything else in a bottom tray or popover — never toolbar icon soup.
3. Two-level, human-formatted progress: chapter position *and* time-left-in-book; never raw `h:mm:ss/h:mm:ss`.
4. Chapters/queue exactly one gesture from the player.
5. Labels on non-universal icons.
6. Speed/effects consolidated into one popover, with per-book override and trim-silence toggle.
7. Home = horizontal shelves (Continue Listening / Recent / New), never a half-empty monolithic grid; editorial warmth optional but distinctive.
8. Identity = one accent + one type personality (PC red/neutral; Libby teal-on-cream serif; Prologue amber serif).

**Design direction for Chronicle (priority order — feeds Phase 2 specs):**
1. **Cover-art color extraction** (AndroidX `Palette`; tinted player surfaces, optionally blurred-art backdrop) — the single biggest lever; cheap; what makes Plexamp/Prologue feel premium.
2. **Audiobook-native hierarchy**: chapter name + time-remaining on the progress bar; chips for Chapters/Bookmarks (steal Prologue's information design wholesale); adopt SABP's two-level progress content.
3. **Player layout per Pocket Casts' frequency layering**: transport central, utility tray (sleep/speed/cast/bookmark) at bottom, labels on icons; speed+trim-silence in one popover with per-book override.
4. **Typography**: display serif for book titles, real weight/size hierarchy, two-line titles instead of hard ellipsis.
5. **Material 3 + tonal surfaces**: LostQuasar's Material You cherry-pick as the on-ramp; rounded cards, elevation layering; WCAG-AA text colors.
6. **Library screen**: horizontal shelves + Libby-style filter chips with counts; author/series/narrator facets as chips.
7. **Signature element** (own something): a **chapter-segmented progress bar** (tick marks at chapter boundaries) — distinctive, genuinely functional, and no competitor owns it.
8. **Adaptive layouts** (owner requirement, 2026-07-05): landscape player and dedicated large-screen layouts via Window Size Classes — two-pane library (list + detail) and two-pane player (transport + chapter list) on expanded widths; never a stretched phone UI on tablets.

All of the above is implementable in the current View/Fragment stack — none of it depends on a Compose migration.

---

## 4. Feature-gap analysis

Effort: S ≤ 1 wk · M ≈ 1–3 wk · L > 3 wk (single-dev equivalents). Impact vs daily-driver goal.

| Gap | Objective | Evidence / source | Effort | Impact |
|---|---|---|---|---|
| Progress-sync reliability (position loss, pause not reported) | Playback / Plex | Issues #88/#112/#68/#67; fabiogermann PlexProgressReporter fixes it | M (port) | **H** |
| Connection resiliency (LAN/WAN/relay tiering, 401 re-auth) | Plex / Stability | #110/#103/#98; Chronicle has **no** 401 re-auth (MainActivity.kt:268) [V]; fabiogermann ConnectionRefreshCoordinator | M | **H** |
| Chapter handling correctness (multi-file, naming, current-chapter) | Metadata | #119/#76/#12; data already fetched via `includeChapters` [V] — client bug | S–M | **H** |
| Resumable, chunked downloads (fix disappearing/OOM) | Plex / Stability | #77/#80/#83/#107; `/library/parts/{id}/file` supports HTTP Range [V] | M–L | **H** |
| Continue-listening home row | UX / Plex | Plex on-deck not populated for music → must be client-built [V]; every competitor has it | M | **H** |
| Narrator + series browse/display (Style/Mood tags) | Metadata | seanap guide + Audnexus convention [V]; Prologue/ABS do it | M | **H** |
| Per-book speed memory | Playback / UX | Smart AudioBook Player, Prologue | S | M |
| Bookmarks with notes | UX | Prologue, BookPlayer, Voice | M | M |
| Chapter-aware progress display | UX / Metadata | Listen, Sirin; lks-nbg fork attempted (incomplete) | S–M | M |
| Sleep-timer polish (auto-restart on resume, shake, end-of-chapter) | UX | BookPlayer auto-restart; #121, #101 | S | M |
| Material You + WCAG palette | UX | LostQuasar fork has both (low-effort cherry-pick) [V] | S | M |
| Listening stats / streaks | UX | MP3 Books, VoicePlus, ABS; Plex session-history API could feed it | M | M |
| Android Auto quality (metadata, presence, refresh) | UX / Playback | #106/#99/#104; fabiogermann Auto refactor | M | M |
| Library list view + author browse | UX / Metadata | #105, #72; Artist-tier queries already available [V] | S | M |
| Playlists support | Plex | #14; `/playlists` endpoint unused [V] | M | L–M |
| Multi-account / Plex Home switching | Plex | fabiogermann; partial code exists (PlexService.kt:21-29) [V] | M | L–M |
| Second backend (Audiobookshelf) | Backends | Symfonium/Abookio precedent; see §6 | L (M+S/M staged) | M (audience-dependent) |
| Widgets / Wear OS | UX | Listen, Symfonium | M / L | L |
| Voice boost / EQ | Playback | Prologue, MP3 Books | M | L |

---

## 5. Plex-API opportunity table

**What Chronicle already calls** (code-verified): `/:/timeline` (PlexService.kt:101-112), `/:/scrobble`+`/:/unscrobble` (:84-95), `POST /playQueues` (:122-130), `/api/v2/pins` (:12-18), `/api/v2/resources` (:31-35), `includeChapters` (:60,66,129), direct part download `?download=1` via Fetch2 (CachedFileManager.kt), collections + container paging (:145-155), stable `X-Plex-Client-Identifier` (PlexInterceptor.kt:41). **Absent:** `/status/sessions`, `/hubs`, websocket/eventsource, audio transcode fallback, any 401 re-auth (401 is only mapped to a UI error string in MainActivity.kt:~271). [V]

| Friction point (source) | Endpoint(s) | What's possible | Effort | Objective |
|---|---|---|---|---|
| Position loss; duration*2 bug (issues #88/#112/#68; fabiogermann fix) | `/:/timeline` | Correct duration param + cadence; report immediately on pause, retry via WorkManager | S–M | Playback |
| Stuck login / silent token death (#110) | `/api/v2/pins` + 401 interceptor | Silent re-auth on 401 instead of error string | M | Stability |
| LAN-only / wifi-only servers (#103/#98) | `/api/v2/resources` connections | Tiered LAN→WAN→relay attempts, relay deprioritized (relay is ~2 Mbps-capped) | M | Plex |
| Download failures + 2GB OOM (#77/#80/#83) | `/library/parts/{id}/file` + HTTP Range | Resumable ranged downloads, streamed to disk in chunks | M | Stability |
| Multi-file book progress fidelity (owner pain #2) | per-track `viewOffset`; requires Music-library "Store Track Progress" ON | Track-level offsets as sync unit; document the server setting for users | S–M | Plex |
| Cross-device sync lag (owner pain #3; #43/#17) | `WS /:/websockets/notifications` (unofficial) `PlaySessionStateNotification` | Real-time push sync replacing poll-only; fall back to `/status/sessions` drift-check | L (WS) / S–M (sessions) | Plex |
| Continue-listening (no on-deck for music [V]) | client-side over `viewOffset` + `lastViewedAt` | Home "Continue Listening" row like Prologue | M | UX |
| Chapter bugs (#119/#76/#12) | `includeChapters=1`, `chapterSource` | Fix client mapping; prefer embedded m4b chapters, expose source | S | Metadata |
| Wrong cover for current book (#113) | `Album.thumb` vs `Track.thumb` | Bind album art, use `/photo/:/transcode` for density-exact sizes | S | Metadata |
| Narrator/series invisible | `styles` / `moods` tags (Audnexus convention) | Narrator facet + series shelves; collections as series alternative | M | Metadata |
| Search is basic | `/hubs/search` | Fuzzy, spell-checked, grouped search | S–M | UX |
| Stats (nobody asked) | session history `/status/sessions/history/all` | Listening stats/streaks dashboard | M | UX |
| Up Next (nobody asked) | `playQueues` (already called!) | Real queue UI from the playQueue Chronicle already creates | M | UX |
| "Playing elsewhere" (nobody asked) | `/status/sessions` | Show other devices' sessions; handoff affordance | S | UX |
| Streaming fallback on poor codecs/bandwidth | `/music/:/transcode/universal/start` | Optional transcode path (not resumable — streaming only) | M | Playback |

*Endpoints marked unofficial (`/:/timeline`, scrobble family, websockets) are community-documented and used by every major client (plexapi.dev, python-plexapi), but are not formally guaranteed by Plex.*

### 5.1 Metadata enrichment beyond Plex (external sources)

Plex's music schema carries author (Artist), title/summary/year/publisher/rating/genres (Album), and — only by Audnexus tagging convention — narrator (`Style` tags) and series+index (`Mood` tags + `ALBUMSORT`, index-as-string). **Moods, themes, characters, pacing, content warnings, and setting do not exist anywhere in Plex**, and the `Mood` field is already occupied by the series hack. Kindle-X-Ray-style character data was Amazon-proprietary. Richer categorization/search therefore requires an external enrichment layer. Survey of candidate APIs (fetched 2026-07-05):

| Source | Gives us | Key | Access | GPL viability | Verdict |
|---|---|---|---|---|---|
| **Audnexus** (api.audnex.us) | narrator, genres, art, rating | **ASIN** | public, ~100 req/min, self-hostable (GPL-3.0) | server GPL; data Audible-derived (ToS gray zone; Audiobookshelf precedent) | **use — primary narrator/genre backfill** |
| **Wikidata** (SPARQL) | **characters (P674)**, series (P179) + **numeric ordinal (P1545)**, genre | fuzzy title+author → QID | free, no key | **CC0 — zero risk** | **use — characters & series ordinal** |
| **Hardcover** (GraphQL) | **moods (top 5)**, tags, content warnings, series+position | ISBN / title | free per-user token, 60 req/min, beta | redistribution ToS UNVERIFIED — confirm before depending | **use if ToS confirmed — only moods source** |
| **Open Library** | subjects, freeform series | ISBN/OLID | free, ~1 req/s | CC0 | fallback |
| Google Books | shallow categories | ISBN | key, ~1k/day | OK for live lookups | marginal |
| LibraryThing | richest **characters/places** data | ThingISBN | ~2008-era feeds, non-commercial clause | poor | skip (watch) |
| StoryGraph | moods, **pacing** | — | **no API** (scrape-only) | not viable | skip — pacing is unobtainable |
| Goodreads | — | — | API dead since 2020 (confirmed) | — | skip |
| abackend (self-hosted, MIT) | aggregates Audible/iTunes/Google/PRH/Hardcover — narrator/genres/series/ratings; no moods/themes/characters | ISBN/title | Docker self-host | MIT | optional household-level aggregator pattern |

**Ratings & organic discovery (owner feature idea, 2026-07-05; APIs verified same day):** rate books *and* narrators/publishers/authors, derive a transparent per-entity affinity profile from explicit votes, and suggest books organically — in-library (offline ranking + series boost) and outside-library. The no-backend path is **on-demand pull discovery**, not a precomputed graph: the unofficial Audible catalog API (`api.audible.com/1.0/catalog/products`) accepts **unauthenticated `narrator=`/`author=` searches** [V — live-tested, 331 results for a narrator query; same API Audiobookshelf's provider uses], Open Library's `/authors/{olid}/works.json`, `/subjects/{s}.json` and `search.json?author=` are keyless [V — live-tested], and Wikidata/Hardcover (above) cover series ordinals and moods. Book/author ratings can sync via Plex `userRating` (field verified on Album/Artist; exact `/:/rate` call to be confirmed in implementation); narrator/publisher ratings are local entities in the enrichment side-table. Risks: Audible ToS gray zone (Audiobookshelf precedent), discovery queries leak taste to third parties (make outside-tier opt-in), narrator name collisions (anchor by ASIN), thinner non-English coverage (regional Audible endpoints mitigate).

**Server-side queue via playlists/collections (owner idea, 2026-07-05; APIs verified same day):** Plex playlists are the right backbone for a "Next to read / Up Next" feature — **per-user** (unlike collections, which are library-wide), persistent, ordered, and fully writable via API (`POST /playlists?type=audio`, add/remove items, `PUT .../items/{id}/move` reorder — all [V] via python-plexapi). A per-user "Up Next · Chronicle" playlist syncs across devices, survives reinstalls, and is visible/editable in Plexamp and Plex Web. The app renders it book-level by grouping tracks by `parentRatingKey` (verify album→track auto-expansion at implementation). Collections complement it: book-level and shared — series shelves (already fetched) plus an optional household "Family queue"; regular collections support custom ordering (`sortUpdate("custom")` + `moveItem` [V]); smart collections can't be reordered [V]. **Smart playlists/collections (filter-based, API-creatable [V]) let the taste-profile feature materialize suggestion shelves server-side** — "unplayed + favorite genre" evaluated by the server, fresh in every client. Continuous playback of the queue is native via `POST /playQueues?playlistID=` [V]. Addresses upstream #14/#62.

**Design sketch:** a local enrichment layer — Room side-table keyed by Plex `ratingKey` + resolved external ID, populated via a match cascade **ASIN → ISBN → fuzzy title+author** (similarity threshold + manual "fix match" affordance), refreshed lazily. Enriched fields surface as facet chips in search/library (character, mood, theme, series position), degrading gracefully to Plex-native fields on no-match. Independent of the backend-abstraction work (keys off books, not off Plex). Effort **M**. Coverage honesty: characters/moods will hit mostly for popular Western titles; non-English and midlist books will frequently fall back. **Differentiator: no Plex client offers this today.**

---

## 6. Backend-abstraction assessment

**Verdict: do it, staged, Plex-first.** [analysis V, estimates I]

- **What exists:** `data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt` — a well-shaped contract that is dead scaffolding (all `TODO()`, never in DI). (Not to be confused with `features/player/MediaSource.kt`, an unrelated playback-side class.) The genuine seam today is `IBookRepository`/`ITrackRepository`/`IChapterRepository`. Domain models leak Plex hard: `Audiobook.id = ratingKey.toInt()`, `MediaItemTrack.progress = viewOffset`, stream-URL resolution inside the model via `Injector.get().plexConfig()`. 27 feature files import `data.sources.plex.*` directly.
- **Cost:** carve a real backend interface (auth/session, library, book/track, media-URL resolution, progress, downloads) with Plex as sole impl = **M (~2–4 wk)**. Audiobookshelf adapter on top = **S/M (~1–2 wk)** — ABS is plain REST + token auth (`POST /login`, `/api/libraries/{id}/items`, `/api/items/{id}/play`, session sync + Socket.IO push) and is *simpler* than Plex. Jellyfin adapter = similar mechanics but **weak audiobook semantics** (no audiobook library type, unreliable m4b chapters — community runs ABS instead); deprioritize. WebDAV/folder source = fits the same interface; the old `LocalMediaSource` stub hints this was always intended.
- **The hidden cost is UI, not API:** the login flow (ChooseServer/ChooseLibrary/ChooseUser) assumes Plex's PIN + server-discovery model and needs redesign for URL+token backends.
- **Sequencing rule:** interface first, adapter second. Reversing the order roughly doubles the work. The M (2–4 wk) carve estimate spans both the Phase-1 ride-along items (neutral IDs/progress, repository interfaces — roughly the first half) and the Phase-3 completion (routing the 27 direct-import sites, login-flow redesign); the two roadmap entries are halves of this one estimate, not additive.
- **Payoff beyond new backends:** the same refactor (backend-neutral `id: String`, `progressMs`, single `fromPlexModel` mapper) is what makes the codebase testable and is prerequisite-free — it can ride along Phase 1 work.

---

## 7. Fork-harvest table

Fresh enumeration: **67 forks** of upstream; all data below from live GitHub compare API. [V]

| Theme | Best source | Recency / maturity | Merge difficulty | Objective | Issues addressed |
|---|---|---|---|---|---|
| Plex connection resiliency (ConnectionRefreshCoordinator, LAN/WAN/relay tiers) | **fabiogermann** | Active, v0.62.0 Jun 2026 | High (port as pattern, don't merge) | Plex/Stability | #110 #103 #98 |
| Progress-reporting overhaul (CoroutineWorker, retry, immediate pause, duration*2 fix) | **fabiogermann** | Active | Med-High; duration*2 fix extractable alone | Playback/Stability | #88 #112 #68 #67 |
| Seek/player fixes (SeekHandler, ChapterValidator, seekbar drag/commit, thread-safe track list) | **fabiogermann** | Active | Med; drag/commit fix portable solo | Playback | #67 #97 #74 |
| Offline play via per-book cache status | elmerohueso (**= open PR #114**) concept + fabiogermann CachedFileManager (current) | 2023 (stale) / active | Med | Stability/UX | #77 #80 #107 #122 |
| Android Auto refactor (handlers, sync-after-login) | fabiogermann | Active | Med | UX/Plex | #106 #99 #104 |
| Multi-account | fabiogermann | Active | High (cross-cuts auth+DB) | Plex | #110-family |
| Numeric series/title + author last-name sorting | binyaminyblatt | 2024-08, small diff | **Low — quick win** | Metadata/UX | #21 |
| WCAG-AA colors + Material You | LostQuasar (⊇ ZacharyCoumont) | 2025-01 | **Low — quick win** | UX | polish |
| Square thumbnails | ZacharyCoumont | 2025-01 | Low | UX | — |
| CI / release automation (Actions, ktlint, CodeQL, Play publish) | fabiogermann (best), binyaminyblatt/smeinecke (basic) | Active / stale | Low-Med (additive) | Dev velocity | — |
| Collections MVP | elmerohueso | 2023, experimental | High (redesign) | Metadata/UX | #62 #14 |
| Jetpack Navigation migration | AnDr3w7911 | 2022, "mostly complete" | High (stale; use as reference, reimplement) | Tech debt | — |
| Chapter-vs-book progress toggle | lks-nbg | 2022, author says "not ready" | High (idea only) | Playback/UX | #119-family |
| Skip | sharinganthief (formatting noise), johnny9 (premium flag), 8bitgentleman/isaacolsen94/etc. (0 ahead) | — | — | — | — |

**Licensing:** all forks are GPLv3 like upstream — code is safe to pull with attribution. fabiogermann's *branding assets* (name "Chronicle Epilogue", logos, `chronicleapp.net`) are All-Rights-Reserved — take code, never branding. [V]

---

## 8. Modernization & stability plan

### Compliance (deadline-driven **if targeting Play** — see §10.1) [V]
- **targetSdk 36 by 2026-08-31** or the app becomes invisible to new Play users (current: 34). Wear/TV exemptions don't apply.
- **16KB page alignment** becomes binding with the SDK-36 bump (the requirement attaches to apps targeting 35+; at target 34 we're not blocked today) — audit Fresco 3.5.0 `.so` files as part of the bump (Fresco has aligned builds; may just need a version bump). *(If distribution stays sideload/F-Droid-only, both become soft goals.)*

### Upgrade path (verified latest stables, Jul 2026)

| Dependency | Ours | Latest | Notes |
|---|---|---|---|
| compile/targetSdk | 34 | **36** | urgent |
| AGP / Gradle | 8.13.1 / 8.13 | 9.2.0 / 9.6.1 | do together with SDK bump |
| Kotlin | 2.1.20 | 2.4.0 | low risk after AGP |
| Media3 | 1.3.0 | **1.10.1** | 7 minors behind; biggest behavior surface; do before Android-16 QA |
| Room | **2.7.0-alpha12** | 2.8.4 stable | highest immediate risk — alpha in prod |
| Material | 1.9.0 | 1.14 (Views final) | Views line is now maintenance-mode |
| Coroutines / Dagger / WorkManager / MockK | 1.7.3 / 2.54 / 2.9.1 / 1.10.6 | 1.11.0 / 2.60 / 2.10.x / 1.14.3 | routine |
| Moshi | 1.15.2 reflection | switch to KSP codegen | perf + R8 friendliness; stay on Moshi (don't churn to kotlinx) |
| Fetch | 3.3.0 | 3.4.1 exists — **not** abandoned, but single-maintainer | replaced by **Media3 DownloadManager** as part of the Phase-1 download rebuild |
| Glide | 4.11.0 (2020-era) | 5.x | resolve the Fresco/Glide duplication — keep one |
| kotlin-result | 1.1.11 (marked "out of date" in the catalog [V]) | 2.x | routine; or replace with stdlib `Result` |

### Android 15/16 behavior changes that bite this app [V]
Edge-to-edge enforced at target 36 (no opt-out) → player insets work; predictive back on by default → custom back handling in `Navigator.kt`/bottom sheets breaks; sleep timer must not assume exact alarms (use `SCHEDULE_EXACT_ALARM` user grant or foreground `Handler`/WorkManager); verify `mediaPlayback` FGS type declarations; `POST_NOTIFICATIONS` needed for **download** notifications (media-session notifications are exempt).

### Architecture debt (ranked by leverage; details §6) [V]
1. Extract stream-URL resolution out of domain models (kill `Injector.get().plexConfig()` in `MediaItemTrack`) — **S**.
2. Interfaces for `CollectionsRepository`/`LibrarySyncRepository` — **S**.
3. Backend-neutral `id`/`progressMs` in domain models, single Plex mapper — **M**.
4. Resurrect-or-delete the dead `MediaSource` scaffolding — decide once, **during the Phase-1 ride-along refactor** — S to delete, M to resurrect (§6).
5. Route 27 direct `plex.*` imports in `features/` through repositories — **M**, after 3.
6. Bigger, later: LiveData→StateFlow (M), Hilt (M), Jetpack Navigation (L, reference AnDr3w7911), split 800–1000-line God classes `MediaPlayerService`/`SettingsViewModel`/`CurrentlyPlayingViewModel` (L), DataBinding exit (L, only with a Compose decision).
7. **Testing is near-zero (2 unit-test files, no fakes)** — every port from fabiogermann should land with tests; their repo shows Robolectric patterns to copy.

---

## 9. Prioritized roadmap

### Phase 0 — Stabilize & comply (≈ 2–3 weeks; deadline 2026-08-31 **iff Play distribution — gate on §10.1**)
| Item | Why | Effort | Source |
|---|---|---|---|
| targetSdk/compileSdk 36 + AGP 9.2 + Gradle 9.6.1 (AGP bump is required for SDK 36); edge-to-edge, predictive-back, FGS-type, notification-permission QA | Play deadline | M | verified requirement |
| Room alpha → 2.8.4 stable | alpha in prod — independent of Play | S | repo audit |
| 16KB check as part of the SDK bump (Fresco bump or drop — app has Glide too) | binding at target 35+ | S | audit |
| CI: build+test on PR (adapt fabiogermann/binyaminyblatt Actions) | no CI today | S | forks |
| **Quick wins:** binyaminyblatt sorting fix (#21), LostQuasar WCAG+Material You palette, fabiogermann duration*2 + seekbar drag/commit fixes | visible improvements while stabilizing | S each | forks |

### Phase 1 — Plex depth: playback, downloads, sync (the daily-driver core)
**Gate:** settle the fabiogermann relationship question (§10.2) first — most items below assume *harvesting* from that fork.
| Item | Why | Effort | Source |
|---|---|---|---|
| Media3 1.3.0 → 1.10.1 + Kotlin 2.4 + Coroutines 1.11 | core engine catch-up before touching playback code (not deadline-bound → moved out of Phase 0) | M | repo audit |
| Port progress-reporting overhaul (immediate pause report, WorkManager retry, correct duration) | #88/#112/#68/#67 — top complaint family | M | fabiogermann |
| 401 → silent re-auth + token validation on app start | #110 stuck login; no re-auth exists today | M | API mapping + fabiogermann |
| Connection tiering LAN→WAN→relay w/ refresh coordinator | #103/#98 | M | fabiogermann |
| Resumable ranged downloads, chunked-to-disk (kills 2GB OOM), Media3 DownloadManager migration | #77/#80/#83/#107; owner pain #1 | M–L | API mapping |
| Offline play via per-book cache status | PR #114 concept, modernized | M | elmerohueso + fabiogermann |
| Chapter-mapping fixes (multi-file, names, current-chapter, `chapterSource`) + album-art fix (#113) | data already fetched | S–M | API mapping |
| `/status/sessions` drift-check + "force sync" affordance (#17/#43) | sync trust | S–M | API mapping |
| Ride-along refactor: neutral domain IDs/progress + repository interfaces + MediaSource resurrect-or-delete decision (§8 items 1–4; first half of the §6 carve) | unlocks testing + Phase 3 | M | arch audit |

### Phase 2 — UX & appeal (reach the Prologue bar)
**Design language:** all visual work in this phase follows the §3.1 design brief (cover-art color extraction → audiobook-native hierarchy → frequency-layered player → typography → Material 3 → shelves/chips → chapter-segmented progress bar as signature element).

Continue-Listening home row (client-built; Plex has no music on-deck) — M. Narrator facet + series shelves from Style/Mood tags (honors seanap/Audnexus libraries) — M. Chapter-aware progress display — S–M. Per-book speed memory — S. Bookmarks with notes — M. Sleep-timer polish (auto-restart on resume, end-of-chapter option; #121/#101) — S. Library list view + author browse (#105/#72) — S. `/hubs/search` fuzzy search — S–M. Android Auto refresh (fabiogermann patterns; #106/#99/#104) — M. Stats/streaks from session history — M. Up-Next UI on existing playQueues — M.

### Phase 3 — Stretch & differentiators
Complete the backend interface carve (second half of §6's M estimate: route the 27 direct-import sites, redesign login flow) → **Audiobookshelf adapter** (Socket.IO push sync; the strongest second backend) — then S/M. **Metadata-enrichment layer + faceted search** (§5.1: Audnexus + Wikidata + Hardcover; browse by character/mood/theme/series-position) — M, a genuine differentiator no Plex client has. Websocket push cross-device sync for Plex — L. Multi-account/Plex Home — M. WebDAV/local-folder source — M. Jellyfin adapter — only if demand (weak audiobook semantics) — M. Wear OS / widgets — M–L. Voice boost/EQ — M. Jetpack Navigation + Hilt + StateFlow consolidation — L (schedule opportunistically). Compose migration — L, decide only after Phase 2.

**Quick wins vs large bets:** quick wins are all of Phase 0's cherry-picks plus chapter/cover fixes, per-book speed, sleep-timer polish, list view, search. Large bets are the download-stack rebuild, websocket sync, backend abstraction, and any UI-framework migration.

---

## 10. Open questions & risks

1. **Distribution intent?** The Aug-31 SDK-36 deadline only *hard*-matters for Play Store distribution (upstream #124 shows a Play version exists). If this stays a sideloaded/homelab app, Phase 0 compresses and Phase 1 can start immediately. **Owner decision.**
2. **Relationship with fabiogermann/Chronicle Epilogue — this gates Phase 1.** Options: (a) contribute there instead of maintaining our fork (fastest path to a good daily driver, less control); (b) harvest patterns while developing independently (recommended default; the Phase-1 plan assumes it); (c) rebase onto it (rejected: minSdk 33, toolchain jump, branding entanglement). **Owner decision — make it before Phase-1 ports begin.**
3. **minSdk floor.** We support 27 (Android 8.1); fabiogermann chose 33. Keeping 27 preserves old household devices but increases QA surface. Recommend keeping ≥27 until data says otherwise.
4. **How far multi-backend goes.** Recommendation: Plex depth first (Phases 0–2), ABS adapter as the single Phase-3 backend bet; Jellyfin/WebDAV only on demand. Symfonium proves the market exists; Abookio + abackend show a metadata-aggregator pattern worth watching.
5. **GPLv3 hygiene & billing.** All harvested code keeps GPLv3 + attribution (commit trailers referencing source fork). Never copy Chronicle-Epilogue branding. **Fork name decided (2026-07-05): "Chronicle Unabridged"** — audiobook-native ("the complete, uncut edition", matching the removed premium gate), thematically sibling to Epilogue, collision-checked clean; requires own applicationId (e.g. `dev.alebianco.chronicle.unabridged`), icon and wordmark. Note the codebase already ships Google-IAP 1.7.0 (upstream's premium plumbing; johnny9's fork extends it) — decide whether to keep, strip, or repurpose it for a homelab-focused fork.
6. **Unofficial API risk.** Timeline/scrobble/websocket endpoints are community-documented, not guaranteed. Mitigation: they're the same endpoints official clients use; wrap them behind the backend interface so breakage is contained.
7. **Verification debts from this research** (claims one agent produced that a second didn't independently confirm): exact token-expiry behavior (48h transient-token claim), Fetch2's internal use of Range/resume, "Store Track Progress" being strictly required for per-track viewOffset persistence, and the upstream-⇄-fabiogermann refactor relationship. Each is flagged inline; none change the roadmap's shape, but confirm before building on them.

---

## 11. Source register

Every fact in this report traces to one of the sources below (or to a repo file path / issue number cited inline). Fetched/queried 2026-07-05 unless noted.

### Repositories & forks (queried live via GitHub API)
- Upstream: https://github.com/mattttvaughn/chronicle — commits, releases, issues (#12–#129 as cited), PRs #114/#129, license metadata.
- Active continuation fork: https://github.com/fabiogermann/chronicle ("Chronicle Epilogue") — commit history, releases v0.60.4–v0.62.0, README (branding clause), `Constants.kt`/`ChronicleBillingManager.kt` (IAP status), libs.versions.toml (toolchain). Site: https://chronicleapp.net
- Fork diffs, all via `github.com/mattttvaughn/chronicle/compare/develop...{owner}:chronicle:{branch}`: ZacharyCoumont (develop), LostQuasar (develop; fork-of-fork of ZacharyCoumont), binyaminyblatt (develop), smeinecke (develop), bheinks (develop), sharinganthief (develop), johnny9 (develop), elmerohueso (`offline_play` = PR #114), AnDr3w7911 (`switch-to-jetpack-navigation`), lks-nbg (`feature/progress-chapter-vs-audiobook`).
- Our tree: local repo at `feature/agentic-dev` — gradle/libs.versions.toml, AndroidManifest, `data/sources/plex/PlexService.kt`, `PlexInterceptor.kt`, `PlexConfig.kt`, `CachedFileManager.kt`, `MediaItemTrack.kt`, `data/sources/MediaSource.kt`, `MainActivity.kt`, `Constants.kt`, strings.xml (premium copy) — all line references inline.

### Plex API documentation
- Community OpenAPI docs: https://plexapi.dev
- Unofficial endpoint wiki: https://github.com/Arcanemagus/plex-api/wiki
- python-plexapi (reference client, field/type model): https://github.com/pkkid/python-plexapi (`plexapi/audio.py`, `plexapi/media.py`) · https://python-plexapi.readthedocs.io
- Official: https://developer.plex.tv · https://support.plex.tv/articles/200241558-agents/
- Audiobook metadata conventions: https://github.com/seanap/Plex-Audiobook-Guide · https://github.com/seanap/Audiobooks.bundle · https://github.com/djdembeck/Audnexus.bundle

### Competitors & market
- Prologue: https://prologue.audio · App Store id1459223267 · https://9to5mac.com/2020/09/06/plex-audiobooks/ · AppleVis forum thread
- Kiesa iPhone audiobook shootout (2025-03-28): https://kiesa.festing.org/wordpress/2025/03/28/iphone-audiobook-app-comparison/
- BookPlayer: https://github.com/TortugaPower/BookPlayer
- Abookio: App Store id6754542041 · https://abookio.app/blog/ · abackend: https://github.com/nreexy/abackend
- Audiobookshelf: https://www.audiobookshelf.org · https://api.audiobookshelf.org · https://github.com/advplyr/audiobookshelf · https://github.com/advplyr/audiobookshelf-app · https://github.com/advplyr/abs-socket-client-demo
- Jellyfin: https://jellyfin.org/docs/general/server/media/books/ · https://jellywatch.app/blog/jellyfin-audiobooks-podcasts-setup-guide-2026 · https://selfhosting.sh/compare/jellyfin-vs-plex/
- Android market survey: Play Store / F-Droid listings of Smart AudioBook Player, Voice/VoicePlus, Listen Audiobook Player, Symfonium, Plexamp, Audiobookshelf app (per-cell claims are [I]-grade, §3).

### Toolchain / platform (basis of §8 and the Phase-0 deadline)
- Play target-API policy: https://support.google.com/googleplay/android-developer/answer/11926878 (+ deadline analysis: stora.sh, 2026-04-14)
- AGP 9.2 release notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes · Kotlin releases: https://kotlinlang.org/docs/releases.html
- Media3 1.10 & Room 3.0 announcements: android-developers.googleblog.com (2026-03)
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16 · 16KB pages: https://developer.android.com/guide/practices/page-sizes · exact alarms: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms · notification permission: developer.android.com
- Library status: https://github.com/facebook/fresco/issues/2834 (16KB) · https://github.com/tonyofrancis/Fetch/releases · https://github.com/google/dagger/releases

### Metadata-enrichment APIs (§5.1)
- Audnexus API: https://api.audnex.us · https://github.com/laxamentumtech/audnexus (GPL-3.0, self-hostable)
- Wikidata SPARQL: https://query.wikidata.org (properties P674 characters, P179 series, P1545 ordinal; CC0)
- Hardcover GraphQL API (beta, per-user token): https://hardcover.app
- Open Library API: https://openlibrary.org/developers/api (CC0)
- Google Books API: https://developers.google.com/books
- LibraryThing Common Knowledge / ThingISBN (stagnant, non-commercial clause): https://www.librarything.com/services/
- StoryGraph — no public API (confirmed); Goodreads API — closed to new keys since Dec 2020 (confirmed)
- abackend aggregator (MIT, Docker): https://github.com/nreexy/abackend

### Design references (§3.1)
- Screenshots preserved in `docs/design-references/` (uncommitted), sourced from: prologue.audio (player), plex.tv/plexamp (player/browse), upstream Chronicle Play listing/README (home + player), Apple App Store via iTunes Lookup API (Pocket Casts id414834813, Libby id1076402606), apk4fun mirrors (Audible, Smart AudioBook Player — Android screenshots).

### Provenance caveats
- §4/§5 "unofficial" Plex endpoints: community-documented (plexapi.dev, Arcanemagus wiki, python-plexapi source), not guaranteed by Plex.
- Single-source claims flagged in §10.7 (token expiry, Fetch2 Range internals, "Store Track Progress" requirement, upstream⇄fabiogermann refactor lineage) await independent confirmation.
- All fork/issue data pulled live from the GitHub API on 2026-07-05; nothing relies on the stale `other-forks.txt` seed list.

---

*Prepared as part of the `manor/quests/chronicle-audiobook` quest (started 2026-06-13): better playback, metadata, and Plex integration for a self-hosted household audiobook library.*

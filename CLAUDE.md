# CLAUDE.md — Chronicle Unabridged

Android audiobook player for self-hosted libraries: **Plex first**, Audiobookshelf and local files/WebDAV planned (backlog D11). GPLv3 fork of [mattttvaughn/chronicle](https://github.com/mattttvaughn/chronicle). Free forever, no monetization (D9).

This file is the **single source of truth for agents and humans**. `.github/copilot-instructions.md` and `AGENTS.md` are pointers here. If this file contradicts the code, the code wins — then fix this file in the same PR.

## Development principles (owner rules, 2026-07-13)

1. **Agentic-first, geared to Claude Code.** The repo must stay agent-implementable: truthful docs, headless verify loop, hermetic tests. Anything that degrades an agent's ability to close the loop is a bug.
2. **Claude is implementer *and* architect.** The owner rarely reviews code or gives code-level direction. Therefore **self-review is mandatory, not optional**: before declaring any non-trivial change done, run the verify loop, re-read the diff critically (correctness, silent failures, error handling, simplification), and prefer industry-standard patterns over cleverness. When an architectural decision is needed, make it, record it (see Docs & decisions below), and state the trade-off — don't wait for direction.
3. **Prefer third-party libraries over hand-rolled solutions** — when maintained and licence-compatible with GPLv3 (Apache-2.0, MIT, BSD, MPL are fine; check before adding). A boring, well-tested dependency beats bespoke code an agent must maintain forever. Exception: trivial utilities where a dependency is pure weight.
4. **Acknowledge origins and influences.** Upstream author Matt Vaughn (mattttvaughn) stays credited in README/About. Code or patterns ported from the fabiogermann "Chronicle Epilogue" fork carry attribution in the commit message (`Ported-from: fabiogermann/chronicle <ref>`). Design influences (Prologue, Pocket Casts, Libby per `backlog/docs/research/RESEARCH_FINDINGS.md` §3.1) are credited in docs, never copied as assets. Epilogue and upstream *branding* are off-limits (All Rights Reserved).
5. **The primary user is the owner's household.** Features are judged by the north-star (*zero interventions*, defined in `backlog/decisions/`), not by imagined market users. When in doubt, the Trust → Comfort → Delight → Differentiation ordering (the `R0`–`R4` labels) decides.
6. **File over app** (<https://stephango.com/file-over-app>) — no lock-in to GitHub-only features. All non-code knowledge lives as **markdown in `backlog/`** (D13, see Workflow below): tasks, drafts, decisions, reference docs, plans, research. CI logic lives in `verify.sh`/Gradle so any CI system (GitHub Actions, GitLab CI, Woodpecker) is a thin wrapper. Don't build workflows on GitHub Projects/Discussions/wiki; plain git + markdown must be enough to move the whole project to another forge without loss.
7. **Open formats, DRM-free, licence-free tools.** Open file formats for state (JSON/zip exports per D8/cu-17, markdown for docs); DRM-free audio only (DRM stores are a permanent won't-do, decision-14); OFL fonts for branding; no proprietary SDKs (no Firebase, no analytics, no ad SDKs); prefer open/keyless APIs (Audnexus, Open Library, Wikidata pattern from RESEARCH_FINDINGS §5.1).

## Verify loop (run before claiming anything is done)

```bash
./verify.sh            # the full gate: ktlint, unit tests, coverage ratchet, debug APK, lint, release compile
./verify.sh --quick    # inner loop while iterating: ktlint + unit tests + coverage only
./verify.sh --format   # runs ktlintFormat first, then the full gate
```

- `verify.sh` **is** the definition of "the build is fine" (D12 rule 6) — not CI, not a forge's required checks. CI is a thin wrapper that calls this same script, so the gate is identical on a laptop and on any forge.
- Green = ktlint clean + unit tests pass + coverage did not regress + debug APK builds + lint passes
  + **the release variant compiles**. Nothing less. That last stage exists because the debug and
  release source sets each provide their own `DebugHooks` object: `DebugHooksContract` makes the
  compiler check the shape, but only for the variant being built, so a drifted release twin used to
  pass every debug-only check and break the first release build (cu-70).
- **Coverage ratchet, two gates** (cu-135). `coverage-ratchet.sh` checks JaCoCo instruction
  coverage twice from one report, and both baselines are plain committed files so every movement
  is reviewable in a diff (D12 rule 6).
  - **Aggregate**, against `coverage-baseline.txt`: fails on a drop of more than **0.05%**. That
    tolerance absorbs codegen jitter, and it *is* a high-water mark — the no-regression branch
    deliberately does not rewrite the file, so a second consecutive dip is measured against the
    same high number and fails. Drops cannot accumulate. (An earlier version of this note claimed
    the opposite and cu-135 was filed to "fix" it; the walk does not exist — the comment in the
    script was simply describing a 0.01% tolerance the code never had.)
  - **Per package**, against `coverage-baseline-packages.txt`: fails when any single package drops
    more than **0.50%**, even while the aggregate rises. The looser tolerance is because a small
    package moves several tenths of a percent per instruction. This gate exists because coverage
    here sits *backwards* — `data/model` above 80% next to `features/collections` and
    `features/home` at 0% — so the average passes while the expensive packages rot. A **new
    package is seeded and announced, never silently admitted**, and a departed one is pruned.
  - Both ratchet *up* on a rise (commit the changed file). To lower either on purpose:
    `./coverage-ratchet.sh --update`, and justify it in the commit message.
- Release builds: `./test_release_build.sh` (R8/ProGuard smoke test; see CONTRIBUTING.md "Release Builds & ProGuard"). Run it whenever touching ProGuard rules, reflection-adjacent code (Moshi models, Room entities), or dependencies. It asserts against the **dex** that Room/Retrofit/Dagger/Moshi classes survived R8 — these fail at runtime, not build time. Keep rules are deliberately narrow (cu-45): prefer adding one precise rule over widening a blanket `-keep`, which silently exempts code from R8.
- **Instrumented tests run again, on two Gradle Managed Devices** (cu-54, was quarantined since `c5cfd46`). `./verify.sh --instrumented` adds them as a 7th stage; `./gradlew instrumentedCheckGroupGroupDebugAndroidTest` runs them directly. **API 27** (the minSdk floor, which catches a new API called without a version guard) and **API 35**, both AOSP `arm64-v8a`. Opt-in, not in the default gate: two emulators take minutes where the unit gate takes seconds. The suite is `LoggedInLaunchTest` — three cases against the cu-16 fixture server via `MockPlexMode`, so **no credentials and no live server**. It is deliberately small; it exists to make the Fragment/Activity/media-session layer reachable at all, not to cover it. Four traps it cost to learn, all recorded in cu-54: `MockWebServer.start()` must bind `127.0.0.1` explicitly (an AOSP image cannot resolve `localhost`, and the throw lands on a background thread with an *empty* crash buffer); Espresso needs `hamcrest:2.2` declared for androidTest (`hamcrest-all:1.3` resolves but `org.hamcrest.Matchers` reaches no dex); `testOptions.animationsDisabled = true` is required; and a `BottomNavigationItemView` sits under the system bars, so Espresso's stock `click()` refuses it — tab navigation is *not* covered for that reason.

## Project snapshot (truthful as of 2026-08-31 — verify against build files if in doubt)

- Single module `:app`, Kotlin **2.2.10**, minSdk 27, target/compileSdk **36** (cu-6). Gradle 9.5.1 + AGP 8.13.2 — note AGP 8.x cannot use Gradle >= 9.6.0, and AGP 9.x absorbs the Kotlin plugin (its own migration).
- MVVM + Repository · Dagger 2.57.2 (hand-rolled components) · Room **2.8.1 (stable, since cu-1) — always write a migration with any schema change; all four DBs export schemas and have migration tests** · Retrofit/OkHttp + Moshi (**codegen**, `@JsonClass(generateAdapter = true)`; the reflective `KotlinJsonAdapterFactory` was removed in cu-62) · Media3 **1.11.0** (ExoPlayer + MediaSession + Cast; cu-7) · LiveData + **ViewBinding** (DataBinding removed in cu-58; no Compose) · Fetch2 for downloads.
- **KSP, not KAPT** (cu-8/cu-58). `kotlin-kapt` is gone; Room and Dagger use `ksp(...)`. Any doc claiming KAPT is wrong.
  Note incremental builds are *slower* than they were under KAPT (+13% on an ordinary edit, +97% when an annotated type
  changes) — this is fixed per-invocation overhead in KSP2, not a misconfiguration. Ruled out: Dagger/Room aggregating
  outputs, `ALL_FILES` poisoning, KSP1 fallback, larger daemon heap, newer Dagger. See cu-8 notes before re-investigating.
- **Mock Plex mode** (cu-16): a debug build can run against the fixture pack with no account —
  `adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity --ez mock_plex true`
  (records the flag and restarts; it must apply before `setupNetwork()`). Use it to see and screenshot
  UI states without credentials. The machinery lives in `app/src/debug/`, with a no-op twin in
  `app/src/release/`, so it is not compiled into release builds at all.
  The mock also serves cover art and a generated audio tone with HTTP range support (cu-64), and
  `--el play_book <id>` starts playback via `playFromMediaId` without needing tap coordinates. Audio
  really does flow: all three track parts are fetched and decoded. The tone is **180 s** (8 kHz mono,
  2.7 MB, a semitone step every 30 s so a log tells you where in the file playback is) — it was 5 s,
  which ended within a second of starting and silently blocked every player-open verification for
  three sessions while being diagnosed as a debug-hook gap (cu-115).
  An earlier claim that no request reached the mock was a logging blind spot, not a bug — the log sat
  below the early returns (cu-64). Seeks are still unexercised end-to-end, so the 206/range path is
  unit-tested only. `./capture-screens.sh <dir>` drives the app and screenshots the main
  screens; it asserts the app was actually foregrounded, because an earlier version silently captured
  the launcher.
  **Getting back to a real server needs `adb shell pm clear <pkg>`, not `--ez mock_plex false`**
  (cu-73). `MockPlexMode.enable` seeds `accountAuthToken`/`server`/`library` into prefs, and
  `determineLoginState` reports `LOGGED_IN_FULLY` whenever all three are present — so merely
  clearing the flag leaves the app "logged in" to a dead `127.0.0.1`, with no login screen.
  `MockPlexMode.disable()` would clear those prefs but is **dead code, called from nowhere**, and
  `onMockPlexIntent` exits the process before anything could call it. `pm clear` also drops the
  `mock_plex` flag itself, since it lives in `chronicle_debug.xml`. The two modes therefore cannot
  be interleaved within one verification pass — plan mock items and live-server items as separate
  blocks.
- Tests: **801 unit tests** (`app/src/test/...`), including `RoomMigrationTest` which drives the historical migration chains through real SQLite via **Robolectric** (Room's `MigrationTestHelper` is instrumented-only), plus **3 instrumented tests** on two managed emulators (see above). Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
- CI: `.github/workflows/ci.yml` — a single `verify` job that runs `./verify.sh` and uploads the APK, test results and coverage report. All build logic lives in `verify.sh`/Gradle, never in the workflow (D12 rule 6).

## Map (fast navigation)

- `app/build.gradle.kts` — plugins, SDK versions, dependencies · `gradle/libs.versions.toml` — version catalog
- `application/ChronicleApplication.kt`, `application/MainActivity.kt` — entry points + DI root
- `injection/` — Dagger components/modules/scopes
- `data/local/` — Room DBs, DAOs · `data/sources/plex/` — Plex API (`PlexService.kt`), login/config, `CachedFileManager.kt`
- `data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt`, `data/sources/local/LocalMediaSource.kt` — multi-backend scaffolding, **declared but not yet load-bearing**. cu-15 added the D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) and made `SourceManager.refreshBooks` fail loudly instead of silently discarding fetches, but the fetch methods on both `LocalMediaSource` and `PlexMediaSource` are still `TODO("Not yet implemented")` — the live Plex work is in `PlexMediaRepository`. Don't call it; don't delete it (cu-33 resurrects it properly).
- `features/` — Fragment + ViewModel + adapters per feature (28 files import `data.sources.plex.*` directly — known debt, task cu-33)
- `navigation/Navigator.kt` — centralized navigation · `views/BindingAdapters.kt` — reusable bindings

## Conventions (the golden rules)

1. DI via constructor `@Inject`/factories; respect scopes (`@Singleton`, `@ActivityScope`, `@ServiceScope`); never instantiate singletons manually.
2. UI logic in Fragments/XML; business logic in ViewModels/Repositories; DB never accessed from UI.
3. LiveData for UI state: private `MutableLiveData`, public immutable `LiveData`. (StateFlow migration is future work — don't mix ad hoc.)
4. Coroutines: **inject `DispatcherProvider`** (cu-15) rather than referencing `Dispatchers.*` directly; UI on Main via `viewModelScope`. `GlobalScope` is gone and stays gone — three tests pin this (`CachedFileManagerScopeTest`, `RepositoryDispatcherTest`, `InternalApiUsageTest`). The five repositories are converted; ViewModels, workers and the player service still hardcode dispatchers (cu-72) — don't add more.
5. User-facing text in `res/values/strings.xml`, always.
6. Room schema change ⇒ bump DB version + write a migration in the same PR.
7. Navigation through `Navigator.kt`; data via Bundles/args.
8. Playback via `MediaServiceConnection`/`MediaPlayerService` — never touch ExoPlayer from UI.
9. Network endpoints in `PlexService.kt`; errors handled in repositories; log with Timber (`Timber.e(e, "context")`).
10. ktlint style; no wildcard imports; new libraries needing keep rules ⇒ update `app/proguard-rules.pro` **and** run `./test_release_build.sh`.

## Gotchas (things that waste agent runs)

- **Four separate Room databases** (`BookDatabase` v9, `TrackDatabase` v6, `ChapterDatabase` v3, `CollectionsDatabase` v2), each with its own version and migration list — a schema change means finding the right one. None use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never silently wipe listening progress. Add a case to `RoomMigrationTest` for any new migration — and note the *load-bearing* check is `RoomSchemaTest`, which opens a real file at the old schema and lets Room migrate it; an in-memory test cannot catch a migration that disagrees with its entity.
- **Listening position is owned by the *tracks*, never the book** (decision-16, cu-90). Plex stores
  no album-level `viewOffset` — only per-track — so `Audiobook.progress` is a cache of a derivation.
  `merge` carries the local value and **never** adopts `network.progress`; only `syncAudiobook`,
  where the tracks are loaded, writes a fresh one. `getActiveTrack` is the *furthest started* track
  (`progress > 0` only): using `max(lastViewedAt)` made position jump backwards between devices, and
  counting a timestamp as "started" made a book marked-as-read report itself half finished, because
  `markTracksInBookAsWatched` stamps every track. **Completion is a separate explicit fact**
  (`viewCount`), not inferred from position.
- **A downloaded track's URI needs its `file://` scheme** (cu-83). `"/path/x.mp3".toUri()` gives
  `scheme = null` and ExoPlayer will not treat it as a local file — it surfaces as an
  unsupported-format error on downloaded books only. Use `Uri.fromFile`, never `"file://" + path`,
  which skips percent-encoding.
- **A cache scan that cannot read its directory must change nothing** (cu-85). `listFiles()` returns
  null for a missing or unreadable directory, and coalescing that to an empty list un-cached whole
  libraries. `cachedMediaDir` also returns the *stored* path even when unmounted, so an absent SD
  card reads as unavailable rather than silently resolving to a different, readable directory.
- **Chapters are stored twice, on purpose, for now** (cu-49). They are written to `ChapterDatabase`
  *and* still serialized into `Audiobook.chapters`, because `syncAudiobook` is the only writer and
  runs per book on demand — so the table is empty for any library synced by an earlier version, and
  a read switched straight to the DAO would show no chapters until each book re-syncs. The four
  read sites still read the book column. Finishing the move is cu-82; until then, **write to both**.
  Chapter offsets are *absolute within the book*, not per-track: two separate bugs came from a
  per-track `0L` (cu-13, cu-49), and `getChapterAt` silently resolves nothing when they are wrong.
  Since cu-136 the frame is a **type**, so that mistake no longer compiles — see below.
- **All four entity ids are `String`** (cu-71), so a non-numeric backend can be represented (decision-11). Two traps follow. **A DAO parameter bound against an id column must be `String`**: SQLite compares across storage classes, so a numeric bind matches *no row, silently, with no error* — two dead DAO methods had exactly this. And **a numeric-looking id must never be parsed**: `id.toLong()` throws on the very ids the retype exists to allow (it did, in two RecyclerView `getItemId` overrides; they hash now).
- **A migration is only tested if a *file* is opened through Room.** `verify.sh` was green while a committed migration would have crashed on launch: Room validates entity against schema **on open**, and an in-memory database is created fresh at the current version and never migrated. `RoomSchemaTest` does both — in-memory opens for entity consistency, plus a file created at the old schema and opened at the current one, which is the only check that catches a migration disagreeing with its entity. A migration that dropped every track's `parentKey` — orphaning every book from its tracks — passed all 201 other tests. Every migration there is verified by deliberate sabotage; a check that cannot fail proves nothing.
- **KSP** — build errors in generated code usually mean an annotation problem upstream; don't loop blindly. KSP errors are
  clearer than KAPT's were, but a DataBinding-style opaque failure is gone with DataBinding itself.
- **401 re-auth covers the server token only** (cu-10). `PlexTokenAuthenticator` on the media
  OkHttp client re-fetches the server access token from `/api/v2/resources` and retries **once**.
  It cannot recover an *account* token: Plex has no refresh token, and a new one needs a human
  approving an OAuth PIN in a browser. A 401 that survives the retry means the account is signed
  out — the app says so and keeps playing cached files. **Don't add a retry loop here**: most of
  that class's tests assert it gives up, because looping would hammer plex.tv. Plex tokens never
  expire on a timer; they are invalidated by an event (password change with "sign out connected
  devices", server re-claim).
- **Account state is three-way, and revocation is checked proactively** (decision-17, cu-122/cu-123).
  `AccountAuthState` is `Authenticated` / `Unknown` / `Revoked` — a boolean could not tell "known
  fine" from "could not check", and that distinction *is* cu-84's rule. **Only a successful,
  parseable negative answer may set `Revoked`**: a timeout, 5xx, offline or malformed body is
  `Unknown`, or the app nags users on trains again. Two traps found the hard way: Plex invalidates
  **no token** when a device is removed at plex.tv, so nothing reactive can ever notice it — the
  check is `GET /api/v2/devices` matched on this install's own `X-Plex-Client-Identifier`
  (`/api/v2/resources` cannot answer, its `clientIdentifier` is the *server's*); and **every login
  mints a new identifier**, so several rows share a device name and matching on name is wrong.
  A revoked account stays `LOGGED_IN_FULLY` on purpose — `NOT_LOGGED_IN` routes through
  `Navigator.showLogin()`, which calls `plexConfig.clear()` and wipes server, library and
  connections, so an expired token used to cost the user their whole configuration.
- **Credentials live in their own `SharedPreferences` file** (`ChronicleAuth.xml`), split out of
  `Chronicle.xml` in cu-108. All three secrets — the Plex account token, the server access token
  and the serialized user — go through `credentialString`/`putCredential`/`removeCredential`,
  which read auth-file-first with a legacy fallback and purge the old copy from `Chronicle.xml` on
  write. Auto Backup excludes `ChronicleAuth.xml` and *not* `Chronicle.xml`
  (`data_extraction_rules.xml` + `backup_rules.xml`, one per API level — keep them in agreement,
  `BackupRulesTest` enforces it, and it checks both files by parsing `path=` rather than by
  substring). Any settings export still MUST use the `BACKUP_SETTING_KEYS` allowlist and
  **never enumerate `sharedPreferences.all` into a file or payload** — the allowlist, not the file
  split, is what keeps an export clean, and a legacy install can still have a token in the old file.
  Note the allowlist gates **keys, not values**: an imported string is written straight to prefs,
  so a value with a closed set of valid options needs validating on the way in (cu-77).
- **An offset carries its frame in its type** (cu-136). `BookOffset`, `TrackOffset` and
  `TrackIndex` (`data/model/Offsets.kt`) are `@JvmInline` value classes, so a book-frame value
  passed where a track-frame one belongs **fails to compile**. Six bugs came from that mistake as
  plain `Long`s (cu-13, cu-49, cu-93, cu-96, and four more in cu-115), and prose did not stop it:
  `Chapter.bookStartTimeOffset` was *renamed to say the frame* and carries a KDoc explaining it,
  and the frame was still guessed wrong twice afterwards. On a single-track book — most of this
  library — the two are the **same number**, so every one of them worked by accident.
  - **One conversion, one home.** `inTrackOffsetOf` (in `ChapterSeekTarget.kt`) is the only
    book → track conversion; `chapterSeekTarget` delegates to it. Three sites used to inline
    `tracks.takeWhile { it.id != trackId }.sumOf { it.duration }`, which **sums every track when
    the id is absent** instead of reporting that it could not resolve one. Don't write a fourth.
  - `getProgress()` is the canonical track → book sum and returns a `BookOffset`.
  - **`TrackIndex` means "index into the *sorted* list"** — the order the player's playlist is
    built in, which is what `seekTo`'s `mediaItemIndex` addresses. `getActiveTrack()` sorts
    internally, and its result used to be looked up in the unsorted list; that agreed only because
    both callers happened to pass a DAO-ordered one.
  - Room stores plain `INTEGER` via `OffsetConverters`, so **no migration** — verified by diffing
    the exported schema. `Audiobook.progress` and `ProgressUpdater` stay `Long` on purpose: they
    already keep the two frames as separate named locals, so names do the work there.
- **Do not do per-second work whose result cannot change** (cu-110). `ProgressUpdater` writes once
  a second during playback and Room invalidates **per table**, so every `LiveData` on `Audiobook`
  or `MediaItemTrack` re-emits at tick rate. The measured damage was not computation but
  **re-rendering**: 1405 `View.measure` calls in 20 s, 88% janky frames, dropped taps. Four causes,
  all the same shape — a constraint-graph rebuild for a constant aspect ratio, a slider refresh for
  an invisible sheet, a DB read to resolve a track that had not changed, and an image reload for
  identical artwork. Guard on *visibility* (`isShown`) and on *value changed*, and remember a
  `RecyclerView` row legitimately rebinds every second, because the playing book's `progress` is in
  `areContentsTheSame` — so a rebind must be cheap. **Profile, do not read**: four rounds of
  inspection produced plausible wrong answers here; `am profile start --sampling` named it at once.
- **A performance fix verified against the easy fixture is not verified** (cu-110/cu-115). The
  single-track, 3-chapter fixture showed 1 jiffy/6 s and looked fixed; the 3-track, 8-chapter one
  put it back to 431 jiffies/12 s and exposed the real dominant cause. Measure against the worst
  realistic input.
- **`postValue` defers to the next main-loop pass**, so a flag it sets cannot guard anything read
  in the same pass (cu-110). `MediaServiceConnection.connectIfIdle` tested `isConnected.value`,
  which `onConnected` publishes with `postValue` while clearing `isConnecting` immediately — so
  both read idle while the browser was CONNECTED, and `MediaBrowserCompat.connect()` throws rather
  than ignoring a redundant call. Ask the collaborator's own synchronous state instead.
- **Never log an auth token.** `TokenLoggingTest` fails the build on any `Timber` call that
  interpolates one — it caught three live leaks, including one logging *two* tokens per media
  item. Logging *presence* (`token.isNotEmpty()`) is fine and is what the guard permits.
- **Never log a whole collection either** (cu-134). `CollectionLoggingTest` fails the build on a
  `Timber` call that interpolates a bare collection-shaped name; log a projection
  (`${books.map { it.id }}`, `${books.size}`). `Audiobook.toString()` drags in the serialized
  `chapters` column, so one `List<Audiobook>` is tens of kilobytes — a measured session produced
  **3.38 MB across 2920 lines**, built on the main thread. Two things to know: **a
  `BuildConfig.DEBUG` guard does not help**, because Kotlin builds the interpolated string
  *before* `Timber` is called, so a debug build pays the full `toString()` either way (two sites
  carried a comment claiming otherwise); and the check keys on the **name**, not the type,
  because the two worst offenders had inferred types that only the compiler could resolve. The
  name heuristic's real enemy is not plurals but **plural units** — `Millis`, `Minutes`,
  `Bytes` are the commonest plural nouns here and all scalars, so `SCALAR_SUFFIX` excludes them
  by suffix. cu-110 swept this class by hand and declared it clean; the review then found three
  more, and this scan found four the review missed. Hence a build gate.
- **Connections are tiered, not raced** (cu-11). `ConnectionChooser` tries LAN, then direct
  WAN, then relay, each tier getting a 1.5s budget before the next also starts (earlier
  attempts keep running, so a slow LAN address can still win). The **last** tier is awaited
  for a real answer, which is what keeps a LAN-only server working. `Connection.relay` comes
  from `/api/v2/resources` and is checked *before* `local`, because Plex can report a relay
  route with `local = 1`. Don't reorder `ConnectionTier` — its declaration order *is* the
  preference order.
- **`retrieveAlbum` and `retrieveChapterInfo` are the same URL** (cu-18) —
  `/library/metadata/{id}?includeChapters=1` — so nothing in the *request* says whether an album or
  a track is expected back, and both fixture servers (`MockPlexServer` for the debug app,
  `FakePlexServer` for the unit tests) routed every `/library/metadata/*` to
  `track-with-chapters.json`. An album request therefore got tracks, and since
  `bookDao.update` is `@Insert(REPLACE)` a **track was inserted into the `Audiobook` table** and
  appeared on the home shelves as a phantom book with a track's title and its book's name in the
  author field. Both routers now key on the **id** (`album-<id>.json` per book, one album each —
  a file listing all of them would make `fetchBookAsync`'s `firstOrNull()` answer the same book for
  every request). `PlexFixtureContractTest` pins it, because the routing exists twice and both
  copies had the same defect. `asAudiobooks()` also refuses a *known* non-album `type` now; an
  absent or unrecognised one is **accepted** deliberately, since Plex does not guarantee the field
  and a strict check would empty the library of a server that omits it.
- **Plex unofficial endpoints** (`/:/timeline`, scrobble, websockets) are community-documented, not guaranteed — keep them wrapped behind repositories/the MediaSource seam.
- **Plex audiobook metadata is a convention hack**: narrator = `Style` tags, series = `Mood` tags (Audnexus/seanap). Never treat these as music semantics.
- `NOTES.md` history: the old `freeAsInBeer` product flavor **no longer exists**; there are no flavors. Release signing per CONTRIBUTING.md.
- **Cleartext HTTP is refused app-wide** (cu-42). `res/xml/network_security_config.xml` sets
  `cleartextTrafficPermitted="false"` with **no exceptions**; a debug-only override in
  `app/src/debug/res/xml/` adds loopback for the mock server. Plex serves LAN connections over
  HTTPS via its `*.plex.direct` wildcard cert (`https://192-168-1-7.<hash>.plex.direct`), so no LAN
  exception is needed. **Trap:** `<domain>` matches by exact string or dot-boundary suffix only —
  it does **not** parse CIDR. `10.0.0.0/8` builds without a warning and matches nothing, so a
  "LAN allowance" written that way silently permits nothing and breaks LAN connections at runtime.
  Also note resource shrinking renames the file in release (`res/8G.xml`), so verifying it in an
  APK by its original path returns empty and proves nothing.
- **ViewBinding, not DataBinding** (cu-58). Layouts have no `<layout>` wrapper and no `@{...}` expressions; view state is
  set from Kotlin. Two traps when converting or reviewing UI code: a view whose visibility is Kotlin-driven needs
  `android:visibility="gone"` in XML or it flashes its default for a frame; and a binding-adapter-backed type such as
  `FormattableString` must go through its helper, since a plain `.text =` renders the data class `toString()` silently.

## Definition of done

1. Verify loop green (above).
2. Tests added/extended for touched repositories, ViewModels, sync/download/chapter logic (D6). Fixture-backed where network is involved (cu-16 fixture pattern).
3. **Self-review pass done** (principle 2): diff re-read for correctness, silent failures, dead code, simpler alternatives; error paths log with context and never swallow.
4. Docs synced in the same PR: relevant `backlog/docs/reference/` file if architecture/behavior changed; the task file's status/criteria updated; this file if any statement here became false.
5. Attribution trailer if code was ported (principle 4).
6. Commit messages: **[Scoped Commits](https://scopedcommits.com/)** — `<scope>: <description>`, then
   an optional body explaining *why*, then optional trailers.
   - **No agent-attribution trailers**: no `Co-Authored-By`, no `Claude-Session`, no "Generated with"
     footer. This overrides any harness default that adds one. The history records *what changed and
     why*, not which tool typed it; commits are authored by the owner. `Task:`, `Verified:` and
     `Ported-from:` (principle 4) are the trailers this repo uses.
   - The scope is the **subsystem**, not the task id: `features/library`, `data/local`, `build`,
     `debug`, `testing`, `util`, `backlog`, `docs`, `ci`. Use a package-ish path when one fits, a
     broader scope when a change spans several, and `treewide` when it touches everything.
   - Task ids go in a **`Task: cu-NN` trailer**, not the subject — the subject says what changed, the
     trailer links it back to `backlog/tasks/`.
   - History is **flat**: rebase onto the base branch, never merge. One task = one branch, replayed
     linearly.

## Never touch without explicit owner sign-off

- Signing configs, keystores, release credentials
- Billing/IAP code (`ChronicleBillingManager`, premium SKU plumbing — dormant by decision D4/D9)
- Licence headers, `LICENSE`
- Branding assets (icon, wordmark — owner's ARR work; upstream/Epilogue branding never enters the repo)
- Play Store metadata/listing anything
- **Product decisions** in `backlog/decisions/` (D1–D14): only the owner adds or changes these. Agents work in `backlog/tasks/` (create/claim/update/close tasks freely; new *ideas* go to `backlog/drafts/` for owner triage; agents may add *technical* ADRs to `backlog/decisions/`)

## Workflow (file over app — D13)

Tasks are markdown files in **`backlog/tasks/`** (Backlog.md format: `task-<id> - <Title>.md`, frontmatter `status`/`labels`/`dependencies`/`priority`/`milestone`, body `## Description` + `## Acceptance Criteria` checkboxes; **`milestone: m-<n>` mirrors the `R<n>` label — set both, they are one fact stored twice**). Statuses: `To Do → In Progress → In Review → Done`. The optional [Backlog.md CLI](https://github.com/MrLesk/Backlog.md) (`brew install backlog-md`; `backlog board`, `backlog task list -s "To Do"`) is a convenience — **editing the files directly is always valid and canonical.**

**Task lifecycle for agents:**
1. **Pick**: lowest-id task in the earliest active release (label `R0` → `R4`) that is `To Do`, unblocked (all `dependencies` Done), unassigned. The owner can override by naming a task.
2. **Claim**: set `status: In Progress`, add yourself to `assignee`. One task = one branch = one worktree (`.worktree/task-<id>-<slug>`); never commit directly to `develop`.
3. **Plan**: use `superpowers:writing-plans` to draft the execution plan (bite-sized TDD steps, exact code/paths). Its output file (`docs/superpowers/plans/…`) is **transient scratch — gitignored, never committed.** When the plan is ready, **summarize it into the task file's `## Implementation Plan` section** (the committed record) — that summary is the spec-driven checkpoint the owner may review. If a task has a `backlog/docs/analysis/` file, read it first for background. (S tasks may skip the Superpowers draft and write the `## Implementation Plan` summary directly.)
4. **Implement** to the acceptance criteria; check them off (`- [x]`) as they're genuinely met.
5. **Verify**: full Definition of done (above) — verify loop, tests, self-review.
6. **Close**: replace/condense `## Implementation Plan` into `## Implementation Notes` (what actually changed, decisions taken, follow-ups → new task files in `backlog/drafts/`), set `status: Done`, commit referencing the task id (`cu-12: rebuild downloads on Media3 DownloadManager`). If the task had an analysis file that no longer reflects the code, move it to `backlog/docs/analysis/archive/`.
7. **Sync docs** in the same change (rule below).

**Two layers, not three.** A task's plan and notes live **inside the task file** (Backlog.md's native `plan`/`notes` fields) — the task is the single committed home for a unit of work. Superpowers is the drafting *tool*; everything it writes under `docs/superpowers/` (plans *and* brainstorming specs) is gitignored scratch — its durable content is redirected into `backlog/` (plan → task `## Implementation Plan`; spec → mostly the task, with an ADR spun off only for a durable architectural choice — see the `docs/superpowers/` map entry). `backlog/docs/analysis/` is *optional* deep-reference for the debt items whose understanding is too large to inline — linked from a task only when it earns its place, archived when stale.

**Docs map** (everything non-code is under `backlog/` — see `backlog/README.md`):
- `backlog/tasks/` — the work (one file per task). `backlog/drafts/` — uncommitted ideas awaiting owner
  triage.
  **Closing out a release.** When every task in a milestone is Done, retire it in this order —
  `backlog task complete <id>` for each task (moves it to `backlog/completed/`, off the Kanban
  board), then `backlog milestone archive m-<n>` (moves it to `backlog/archive/milestones/`).
  Order matters and so does the second step: a milestone's completion count is derived from **task
  files**, so once they move it reports **0/0** and sits under *Active*, reading as an empty
  milestone available for reuse rather than a finished one. Record the real count in the milestone
  file before archiving, since the CLI can no longer compute it.
  Completed tasks stay inside `backlog/` and in git — `backlog task cu-<n>` still resolves and
  `grep -r` still finds them, which is how the gotchas above cite tasks. The one cost is that
  **`backlog search` does not index `completed/`** — tracked upstream as
  [Backlog.md#825](https://github.com/MrLesk/Backlog.md/issues/825), so this is a known gap with a fix requested rather than a permanent
  limitation. Until it lands, reach a completed task by id (`backlog task cu-<n>`) or by
  `grep -r`, which is how the gotchas above cite tasks anyway.
  **A colon in a `title:` must be quoted** (`title: "Toolchain bump: SDK 36"`). An unquoted one
  breaks YAML parsing and the task becomes invisible to *every* CLI operation — `backlog task
  <id>` reports "not found" while the file sits in place. Nine files had this, four of them
  decision records `backlog doctor` was reporting as unreadable.

  **Deferred work is not a draft.** A draft is an *idea nobody has committed to*; work that was
  started, scoped and then postponed is a **task** with `status: To Do`. The difference matters
  mechanically: `backlog board` and `backlog task list` show tasks, while drafts surface only in
  `backlog draft list`, so a deferred item filed as a draft and linked from a **closed** task is
  invisible in every normal view — which is exactly how it gets lost (cu-73/cu-132). When closing a
  task with unfinished items, promote the remainder to a task and list in the closing notes *where
  each item went*. **A draft's `id` must use the `DRAFT-<n>` prefix**, not `cu-<n>`: Backlog.md keys its drafts
  view on that prefix, not on the directory or on `status: Draft`, so a draft filed as `cu-<n>` is
  invisible in the board and to `backlog draft list`. Keep `<n>` from the cu number it will take, and
  `backlog draft promote DRAFT-<n>` turns it back into a `cu-` task on promotion.
- `backlog/decisions/` — decision records `decision-<n> - <Title>.md` (context → decision → consequences): product decisions D1–D14 (owner-only) + technical ADRs (agents may add). Framing, won't-do, and risks live here (decision-9/11/14).
- `backlog/docs/reference/` — architecture knowledge base (project overview, architecture, data flow, components, glossary); keep in sync with behavior.
- `backlog/docs/analysis/` — *optional* deep-reference for debt items (C1–C6, H1–H8, M1–M7): problem/current-state/risk, linked from a task only when the understanding is too large to inline. `analysis/archive/` holds ones whose task is Done and content is stale. These are *analysis*, not execution plans.
- `docs/superpowers/` — **gitignored working scratch, never committed** (the whole tree). Superpowers writes execution plans to `plans/` and brainstorming design specs to `specs/`; both are drafting output. Redirect their durable content into `backlog/`, distilled by *kind* — a spec is not one artifact:
  - forward design / requirements / approach → the **task's `## Implementation Plan`** (feature-scoped, lives with the work);
  - a genuine cross-cutting choice that outlives the feature ("Coil not Glide, because …") → a **`backlog/decisions/` ADR** — the decision only, not the whole design;
  - large problem/current-state analysis → an optional **`backlog/docs/analysis/`** file.
  Default is the task file; spin off an ADR only for durable architectural choices. Don't leave anything stranded in `docs/superpowers/`.
- `backlog/docs/research/` — evidence base (`RESEARCH_FINDINGS.md`, `COMMERCIAL_VIABILITY_REPORT.md`); cite, don't duplicate. `research/design-references/` — competitor/design screenshots (uncommitted third-party assets).

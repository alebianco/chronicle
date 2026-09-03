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
- Tests: **1070 unit tests** (`app/src/test/...`), including `RoomMigrationTest` which drives the historical migration chains through real SQLite via **Robolectric** (Room's `MigrationTestHelper` is instrumented-only), plus **3 instrumented tests** on two managed emulators (see above). Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
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

- **Five separate Room databases** (`BookDatabase` v12, `TrackDatabase` v6, `ChapterDatabase` v3, `CollectionsDatabase` v2, `BookmarkDatabase` v1), each with its own version and migration list — a schema change means finding the right one. None use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never silently wipe listening progress. Add a case to `RoomMigrationTest` for any new migration — and note the *load-bearing* check is `RoomSchemaTest`, which opens a real file at the old schema and lets Room migrate it; an in-memory test cannot catch a migration that disagrees with its entity.
- **Listening position is owned by the *tracks*, never the book** (decision-16, cu-90). Plex stores
  no album-level `viewOffset` — only per-track — so `Audiobook.progress` is a cache of a derivation.
  `merge` carries the local value and **never** adopts `network.progress`; only `syncAudiobook`,
  where the tracks are loaded, writes a fresh one. `getActiveTrack` is the *furthest started* track
  (`progress > 0` only): using `max(lastViewedAt)` made position jump backwards between devices, and
  counting a timestamp as "started" made a book marked-as-read report itself half finished, because
  `markTracksInBookAsWatched` stamps every track. **Completion is a separate explicit fact**
  (`viewCount`), not inferred from position.
- **A local-only column must be named in *both* arms of `Audiobook.merge`** (cu-20). A library
  refresh merges a network copy without loading tracks, and a field the server knows nothing about
  is always the default on that copy — so an arm that omits it wipes the local value on every
  refresh. `progress` documents this (decision-16) and `playbackSpeed` repeats it. `merge` has two
  branches and only one runs for a given pair, so a fix applied to one arm and missed in the other
  looks correct in a test that happens to take the fixed path; `PerBookSpeedTest` exercises both
  and was verified by sabotaging one arm.
- **Per-book playback speed is a column with a sentinel, resolved in one place** (cu-20).
  `Audiobook.playbackSpeed` is `NO_SPEED_OVERRIDE` (`0f`) when the book follows the global
  preference, and `effectiveSpeed(global)` is the **only** reader — `MIN_VALID_SPEED` is pinned
  equal to the slider's floor by a test, because if it drifted below, a legitimately chosen speed
  would read as "no override". `MediaPlayerService.invalidatePlaybackParams()` is the single writer
  of `PlaybackParameters` and resolves it there; it collects `currentlyPlaying.book` mapped to
  `id to playbackSpeed` and `distinctUntilChanged`, because `ProgressUpdater` republishes the book
  **once a second** during playback (cu-110's shape). Note the DB write alone does not propagate:
  `ProgressUpdater`'s tick is gated on `isPlaying`, so a change made **while paused** needs
  `CurrentlyPlaying.updateSpeedOverride` to reach the player at all.
- **A `Slider` throws for a value off its step grid.** `setValue` requires an exact multiple of
  `stepSize` above `valueFrom`, so any value coming from outside the UI — a settings import
  validates keys, not values (cu-77) — must be snapped first (`SpeedChooserState.snapToStep`). And
  **a `Chip`'s `android:tag` must not be a string resource** when it is parsed as data: the speed
  presets keyed on `@string/playback_speed_1_0x`, so a locale rendering it "1,0x" matched no branch
  and every preset silently became 1.0x.
- **A modal bottom sheet opens at its peek height in landscape, hiding everything** (cu-142). The
  speed popover rendered *only* its title bar there — Material's `BottomSheetDialog` opens
  collapsed and expects a drag, and for a `wrap_content` sheet that peek settled at 96px, shorter
  than the sheet's own 108px title bar, with nothing on screen suggesting anything was draggable.
  Every modal sheet here calls `expandBottomSheetOnStart()` (`views/ExpandedBottomSheet.kt`) for
  that reason — all three had the bug, only one had it noticed. **The obvious diagnosis was wrong**:
  the task blamed a `wrap_content` `ConstraintLayout` measuring to zero, but the layout measures
  356px in both orientations with or without any fix. Probe the measurement before believing a
  layout explanation. The layout's `NestedScrollView` + `fillViewport` is a *separate* need: fully
  expanded, a window shorter than the content clipped the last control (60px of 72 at 480px tall).
  Two reading traps: a zero-bounds view is **absent from a `uiautomator` dump entirely**, so
  screenshot rather than dump when a sheet looks wrong; and a dump during playback fails with
  "could not get idle state" while leaving the previous file in place, so `rm` the target first and
  assert it exists, or a stale read looks like success.
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
- **Bookmarks are a separate database on purpose** (cu-22). `BookmarkDatabase` is keyed by
  `bookId` and lives outside `BookDatabase` **so the sync path cannot reach it**: `refreshData`
  merges `Audiobook` rows and calls `bookDao.removeAll` for books the server no longer lists, so a
  bookmark stored alongside a book would vanish when a Plex rescan briefly drops it — permanently,
  since no server holds a copy of a note the user wrote. `BookmarkSurvivesSyncTest` runs the real
  refresh over real databases and asserts the note outlives the catalogue row; moving bookmarks
  into `BookDatabase` breaks it, which is the point. A library *switch* (`clear()`) leaves them
  alone too — the user may switch back.
- **The backup file carries records as well as settings** (cu-22). `SettingsBackup.settings` is a
  `Map<String, String>` of *preference keys*; bookmarks are a top-level `bookmarks` array, because
  forcing per-book rows through that map means JSON encoded inside a string value and the file is
  meant to be openable in an editor (D12 rule 7). `BACKUP_SCHEMA_VERSION` is **2** for that: adding
  a settings key needs no bump (unknown keys are ignored), but the format growing a field does,
  or `importSettingsOrNull`'s refusal of a newer version can never distinguish "a v1 file with no
  bookmarks" from "a v2 file whose bookmarks were lost". Import is **additive and idempotent**,
  keyed on the id in the file — never a replace-all, which would delete notes made since the
  export.
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
- **The player's progress readout is human-formatted, never `h:mm:ss/h:mm:ss`** (cu-19).
  `formatCoarseDuration` (`6h 12m`, `<1m`) for a span, `formatPrecisePosition` (`32:10`) for a
  position inside a chapter — both in `util/DurationFormat.kt`, both pure over millis so the
  wording is testable without a `Context`. RESEARCH_FINDINGS §3.1 rule 3 is the source; a 47-hour
  book used to read `47:12:33/52:04:11`. `RawDurationFormatTest` asserts the four progress views
  are written from those two and that the ViewModel exposes no raw pair. It is scoped to the
  **readout**, not to `DateUtils`: a sleep-timer countdown genuinely *is* `h:mm:ss`, and a first
  cut that banned the call outright flagged three legitimate uses.
- **An `isShown` guard must probe a view that exists in every orientation** (cu-19).
  `renderPlayerText` guarded on `binding.progress`, which carries
  `android:visibility="@integer/currently_playing_artwork_visibility"` — GONE in `values-land`. So
  on a landscape tablet the guard returned early *every* time and the whole text block stayed
  blank: chapter position, chapter duration, percentage and chapter title. The guard's intent
  (cu-110/cu-117 — skip the work while the sheet is collapsed) is right; the anchor was not. It
  probes `chapterProgressSeekbar` now, which is what `refreshSlider` already used. **A
  `uiautomator` dump cannot see this**: it omits an empty `TextView` from the tree, so a blank view
  and an absent one look identical — and a dump taken *during playback* fails with "could not get
  idle state" while leaving the previous file in place, so a stale read looks like success. Pause
  first and assert the file exists.
- **`ACTION_SLEEP_TIMER_CHANGE` is bidirectional, and the service must not answer itself** (cu-21).
  Commands travel *into* the timer on that action and its ticks travel *out* on the same one, so a
  service that handles every broadcast it hears feeds the timer its own output. That was invisible
  while `SleepTimer.update` reassigned a Long to itself; once the state carried a **mode**, the loop
  rewrote an end-of-chapter timer as a zero-length countdown that expired on the next tick. The
  service filters `SleepTimerAction.UPDATE`, which is **outbound-only**.
- **A sleep timer's expiry and its cancellation are different facts** (cu-21). `cancel()` forgets
  the duration; `expire()` keeps it in `SleepTimerState.Expired` so `onPlaybackResumed` can re-arm
  it — and it re-arms to `FixedDuration.originalMillis`, *not* the remaining time, because a timer
  always expires with almost none left (a first cut restored a one-second timer). An expired timer
  keeps ticking on purpose: that is how it notices playback resuming. **End-of-chapter carries no
  deadline** — it stores the chapter id and compares each tick, so a seek or a speed change cannot
  desync it, which a computed `(chapterDuration - chapterProgress) / speed` countdown did both ways.
  Decisions live in `SleepTimerLogic` (pure, no Android types); `SimpleSleepTimer` owns the state
  and the plumbing. Note `isTicking` is tracked **separately** from the state: `BEGIN` is
  `update(duration)` then `start(true)`, and `update` already leaves the state `Running`, so a
  guard that asks the state whether it is active makes every `BEGIN` a silent no-op.
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
  The **track** half of the same routing was fixed in cu-19: `retrieveChapterInfo(trackId)` is
  read with `metadata.firstOrNull()`, so one fixture holding all three tracks answered *track
  2001's* chapters for every track and the player read "Ch 1 of 9" for a 7-chapter book. Each
  track now has its own `track-<id>-chapters.json`. A chapter spanning a track boundary
  legitimately appears on **both** tracks, so a count above the distinct-chapter count is correct.
- **Plex unofficial endpoints** (`/:/timeline`, scrobble, websockets) are community-documented, not guaranteed — keep them wrapped behind repositories/the MediaSource seam.
- **Search is local, not `/hubs/search`** (cu-25). `BookSearch.kt` scans the synced library in
  memory over four fields (title, author, narrator, series). The endpoint the task named cannot be
  the foundation: its results **omit `Style`/`Mood`**, so it cannot answer a narrator or series
  query at all; it is unavailable in offline mode, which every other read path honours; its
  `sectionId` only *re-orders* rather than filtering to a library; and `limit` defaults to **3 per
  hub**. It does spell-check server-side and is built for type-ahead, so it is still worth adding
  as a *complement* for books not yet synced — after cu-143, which may remove the need. Two traps
  in the matching itself: it is **Damerau**-Levenshtein because plain Levenshtein charges 2 for a
  transposition (the commonest typo), and the cheap prefilter counts **characters, not bigrams** —
  a transposition rewrites every adjacent pair, so a bigram prefilter silently discards the very
  matches the fuzziness exists for. Fuzzy matching is floored at 4 characters; below that only
  prefix/substring match, or the first keystroke answers the whole library.
- **Plex audiobook metadata is a convention hack**: narrator = `Style` tags, series = `Mood` tags (Audnexus/seanap). Never treat these as music semantics.
  Both are **detail-only** (cu-24): `/library/metadata/{id}` carries them, the library listing
  `/library/sections/{id}/all` does **not** — verified against fixtures captured from a real Plex
  1.43.3 server, and there is no `includeFields`/`includeTags` that would add them. So today the
  index fills in as books are synced (`syncAudiobook` already fetches the detail), and
  `FacetList.unknownCount` exists so the UI is obliged to say how partial it is.
  **But "an index cannot be built from a refresh" is false** — that was inferred from the listing
  gap without checking whether another endpoint could enumerate the tags. Two routes exist
  (researched in cu-25, filed as **cu-143**): the *filter enumeration* route, where
  `/all?includeMeta=1&includeAdvanced=1&X-Plex-Container-Size=0` returns only filter metadata
  naming the `style`/`mood` filter keys, `/library/sections/{id}/style?type=9` lists the distinct
  values, and `/all?type=9&style={key}` lists each value's books — `3 + N + M` requests, verified
  in python-plexapi's source; and the *multi-id* route, `/library/metadata/{id1},{id2},...`
  ("Get one or more metadata items" in the API spec), which is spec-verified but not yet
  live-tested. Don't re-derive this. `merge` needs a **third** rule for fields like these — the
  network value when it has one, the local value when it does not: preferring the network blanks a
  narrator on every refresh, preferring the local one makes a re-tagged book uncorrectable.
  `Audiobook.seriesIndex` is parsed from `titleSort`, **not** Plex's `index`, which is the album
  ordering index and is 1 for nearly every audiobook — and **not** from `Mood`, which carries the
  series name without a number.
- **The series index is parsed from anywhere in `titleSort`, in hundredths** (cu-146). The parser
  was **end-anchored** and both dominant taggers put the number at the *front* — Audnexus writes
  `"<Series>, Book <n> - <Title>"`, seanap prescribes `"<Series> <n> - <Title>"` — so it read
  **1 of 8** real formats, the one being our own fixture, which happened to end with the number
  (the cu-24 fixture trap in a new field). `SERIES_INDEX_PATTERNS` now holds seven patterns tried
  **most specific first**, and that order is load-bearing: `audnexus` must precede `label-first`
  or `"Book 2 of the Saga, Book 5"` reads 2, which is exactly what the old anchoring protected.
  Values are **hundredths** (`SERIES_INDEX_SCALE`, so book 2 is `200`) because a novella genuinely
  sits at 1.5 — but they stay `Int`, because `NO_SERIES_INDEX` (0) is compared for equality and
  float equality against a sentinel is unreliable. The column stays `INTEGER`, so v11→v12 changes
  **no shape** — the exported schemas differ only by version and share an `identityHash`, since
  Room hashes the schema, not the version. The migration rescales *data*, and nothing but
  `RoomSchemaTest`'s v11 case can catch it being wrong. Two forms the old parser accepted
  (`"Mistborn, Bk 2"`, `"Mistborn, 2"`) were silently dropped when un-anchoring and restored after
  `BookFacetsTest` failed — don't remove `Bk` or the loosest `comma-trail` pattern. `Book 0` reads
  as **unknown** on purpose (0 is the sentinel), so a prequel numbered zero sorts last; a test says
  so.
  **The rules are data, not constants** (cu-147, decision-18). They live in
  `data/model/SeriesIndexPatterns.kt` as named `SeriesIndexPattern`s with named capture groups, and
  `Audiobook.installSeriesIndexPatterns(patterns, order)` lets a user's own rules go **before**,
  **after** or **instead of** the built-ins — modelled on tvnamer, but deliberately *not* copying
  three of its failure modes: a user config there replaces every built-in (its own maintainer's
  open issue #191), required groups are validated only *after* a match so a bad rule aborts a parse
  a later rule would have handled, and there is no way to see why a rule did not match (#216).
  Hence `SeriesIndexPatternSet.explain()`, which reports every rule's verdict. Two traps: **do not
  use `RegexOption.COMMENTS`** — like Python's `re.VERBOSE` it strips literal spaces, which cost a
  tvnamer user real debugging time; and **`MatchResult.groups["name"]` throws** for a group the
  *matching* pattern never declared rather than returning null, so four of the seven built-ins
  (which declare no `series` group) crashed every match until every named read went through
  `namedGroupOrNull`. Nobody calls `installSeriesIndexPatterns` yet — the file format and the
  tester UI are cu-148.
- **A tag list's `@Json` name must be checked against a captured response, not a fixture** (cu-24).
  `plexGenres` carried **no** `@Json(name = "Genre")` for the life of the project, so Moshi looked
  for a key literally called `plexGenres` and `Audiobook.genre` was empty against every real
  server — while every test passed, because the hand-written fixtures were written to match the
  *code*. The `*-real-shape.json` fixtures are captured from a real server and are the authority;
  pin new parsing tests against those.
- **An exported Room schema for a released version must never change** (cu-24). Room rewrites
  `<version>.json` from the current entities, and when a version bump and an entity change land in
  the same build it overwrites the **older** file — leaving `10.json` containing v11's shape. Those
  files are the authority a migration's column list is written from (`BOOK_MIGRATION_8_9` says so),
  so a corrupted one silently misinforms the next migration. `RoomSchemaTest` checks each file's
  name against the `version` inside it; comparing column counts between neighbours does **not**
  work, because an overwritten file is an exact copy of the newer one and compares equal.
- **The bottom navigation cannot be driven by `adb shell input tap`** — a `BottomNavigationItemView`
  sits under the system bars (the obstacle recorded in cu-54). Screens behind a tab need a debug
  hook to be reachable from a script: `--ez show_browse true` is one (cu-24). Such a hook must
  **post** rather than navigate immediately — called from `onCreate` a `commit()` throws
  `FragmentManager has not been attached to a host`.
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

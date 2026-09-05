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
7. **Open formats, DRM-free, no data extraction** (re-stated 2026-09-05, decision-19). Open file formats for state (JSON/zip exports per D8/cu-17, markdown for docs); DRM-free audio only (DRM stores are a permanent won't-do, decision-14); OFL fonts for branding; prefer open/keyless APIs (Audnexus, Open Library, Wikidata pattern from RESEARCH_FINDINGS §5.1).
   **No dependency may extract the household's data or gate functionality behind a third party** — analytics, telemetry, crash reporting, advertising and anything needing a cloud account are permanently out *whatever their licence*, since the objection is the data flow and an open-source analytics SDK is equally banned. A **proprietary SDK for a device capability the platform exposes no other way** is permitted when all four hold: it sends the household's data nowhere; it **degrades to absent** (a device without it loses that one feature, with no crash, error or nag); it is **confined behind a seam** so an open replacement would be a swap; and there is **genuinely no open alternative reaching the same hardware**. Each one admitted is recorded as an ADR. Google Cast (cu-168) is the first — Plex server to a receiver on the same network, no Cast button on a de-Googled device, every SDK reference inside `CastPlayerProvider`, and no open protocol reaches a Chromecast. `play-services-oss-licenses`, which renders the licence list, qualifies retroactively. The earlier wording was a flat "no proprietary SDKs" whose three examples (Firebase, analytics, ads) were all *extraction* SDKs — it banned a category while describing a narrower harm, and it silently forbade both of these.
   **A second admission route covers data the user *asks* to send** (decision-20): permitted only when the user opted in per feature (off by default, informed, revocable), it is not the business model, the payload is an **allowlist never a dump**, it carries **no credentials**, and it degrades to absent. **Ads, behavioural analytics, usage telemetry and any listening profile are barred regardless of consent** — they will not be built, so there is nothing to opt into. Two features qualify: **crash reporting** (opt-in once, but *every individual report still needs a tap*, payload viewable — enabling the feature is not consent to a standing upload channel), and **settings cloud sync** to the user's own Google Drive `appDataFolder`. **Sync carries no auth token**: [[decision-8]]'s "auth tokens excluded (re-login on restore)" stands, enforced by both backup-rules files, `BACKUP_SETTING_KEYS` and the cu-108 `ChronicleAuth.xml` split — Plex has *no per-device revocation and no refresh token*, so a leaked account token can only be killed by logging out every device the household owns. Migration is still one tap because sync carries server id, library id, connections, settings, bookmarks and per-book speed — all non-secret prefs sitting beside the credentials. Note `BACKUP_SETTING_KEYS` is **preferences only today** and would not deliver that; extending it is the implementing task's first job.

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
- MVVM + Repository · Dagger 2.57.2 (hand-rolled components) · Room **2.8.1 (stable, since cu-1) — always write a migration with any schema change; all four DBs export schemas and have migration tests** · Retrofit/OkHttp + Moshi (**codegen**, `@JsonClass(generateAdapter = true)`; the reflective `KotlinJsonAdapterFactory` was removed in cu-62) · Media3 **1.11.0** (ExoPlayer + MediaSession + Cast; cu-7) · **StateFlow** (LiveData removed in cu-52) + **ViewBinding** (DataBinding removed in cu-58; no Compose) · Fetch2 for downloads.
- **KSP, not KAPT** (cu-8/cu-58). `kotlin-kapt` is gone; Room and Dagger use `ksp(...)`. Any doc claiming KAPT is wrong.
  Note incremental builds are *slower* than they were under KAPT (+13% on an ordinary edit, +97% when an annotated type
  changes) — this is fixed per-invocation overhead in KSP2, not a misconfiguration. Ruled out: Dagger/Room aggregating
  outputs, `ALL_FILES` poisoning, KSP1 fallback, larger daemon heap, newer Dagger. See cu-8 notes before re-investigating.
- **The debug build's package is `io.github.mattpvaughn.chronicle.debug`**, not
  `io.github.mattpvaughn.chronicle` — `app/build.gradle.kts` sets `applicationIdSuffix = ".debug"`
  for the debug variant. Every `adb` line below therefore names the suffixed id, and a component
  name must be fully qualified (`<pkg>.debug/io.github.…application.MainActivity`), since the
  activity class does **not** move with the suffix. Seven examples in this file and in
  `app/src/debug/` named the unsuffixed id and had never worked as written.
- **Mock Plex mode** (cu-16): a debug build can run against the fixture pack with no account —
  `adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.application.MainActivity --ez mock_plex true`
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
  **Getting back to a real server needs the prefs restored, not `--ez mock_plex false`**
  (cu-73). `pm clear` does it but **destroys the login**, which matters whenever a human is not
  available to sign in again: prefer restoring a captured `Chronicle.xml`/`ChronicleAuth.xml` with
  the app **stopped** (`plex-session.sh real`, see below). `MockPlexMode.enable` seeds
  `accountAuthToken`/`server`/`library` into prefs, and
  `determineLoginState` reports `LOGGED_IN_FULLY` whenever all three are present — so merely
  clearing the flag leaves the app "logged in" to a dead `127.0.0.1`, with no login screen.
  `MockPlexMode.disable()` would clear those prefs but is **dead code, called from nowhere**, and
  `onMockPlexIntent` exits the process before anything could call it. `pm clear` also drops the
  `mock_plex` flag itself, since it lives in `chronicle_debug.xml`. The two modes therefore cannot
  be interleaved within one verification pass — plan mock items and live-server items as separate
  blocks.
  **`./plex-session.sh {backup|real|mock|status}` switches between the two without `pm clear`**
  (2026-09-04). It captures the real session's `Chronicle.xml`/`ChronicleAuth.xml` to
  `~/.chronicle-session-backup/` (outside the repo — they hold live tokens) and restores them with
  the app **stopped**, so mock mode stops being a one-way door and the two modes *can* now be
  interleaved. Three refusals make it safe to run unattended: `mock` will not proceed without a real
  backup on disk, `real` will not restore a backup that is itself a mock session, and `backup` will
  not overwrite a real backup with a mock one. All three are sabotage-verified.
  **Two traps it encodes.** A `SharedPreferences` file edited **while the app runs** is silently
  reverted when the process dies — the framework holds the map in memory and writes it back on
  shutdown, so the file reads correct immediately and wrong at the next launch. That cost a real
  login once: the `mock_plex` flag was set to `false` on disk, verified, and read back as `true` on
  the next cold start. Hence `force-stop` **and poll until the process is actually gone** (it
  returns before the kill completes) before touching `shared_prefs/`. And the device holds a
  *stale* flag from any earlier mock session, so `status` before assuming which mode you are in.
- Tests: **1389 unit tests** (`app/src/test/...`), including `RoomMigrationTest` which drives the historical migration chains through real SQLite via **Robolectric** (Room's `MigrationTestHelper` is instrumented-only), plus **3 instrumented tests** on two managed emulators (see above). Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
- CI: `.github/workflows/ci.yml` — a single `verify` job that runs `./verify.sh` and uploads the APK, test results and coverage report. All build logic lives in `verify.sh`/Gradle, never in the workflow (D12 rule 6).

## Map (fast navigation)

- `app/build.gradle.kts` — plugins, SDK versions, dependencies · `gradle/libs.versions.toml` — version catalog
- `application/ChronicleApplication.kt`, `application/MainActivity.kt` — entry points + DI root
- `injection/` — Dagger components/modules/scopes
- `data/local/` — Room DBs, DAOs · `data/sources/plex/` — Plex API (`PlexService.kt`), login/config, `CachedFileManager.kt`
- `data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt`, `data/sources/local/LocalMediaSource.kt` — multi-backend scaffolding. **The ingestion seam is real since cu-80**: `SourceManager.refreshBooks` ingests per source through `IBookRepository.ingest`, and `planIngestion` (`data/sources/IngestionPlan.kt`) decides what a refresh writes and deletes. Still not *registered* — `sources` is empty in production, so it is a no-op until cu-33.1 adds one. cu-15 added the D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) and made `SourceManager.refreshBooks` fail loudly instead of silently discarding fetches, but the fetch methods on both `LocalMediaSource` and `PlexMediaSource` are still `TODO("Not yet implemented")` — the live Plex work is in `PlexMediaRepository`.
- `features/` — Fragment + ViewModel + adapters per feature (27 files import `data.sources.plex.*` directly — known debt, now task **cu-80**; dominated by `PlexConfig` at 17, a connection-state holder rather than a fetch API)
- `navigation/Navigator.kt` — centralized navigation · `views/BindingAdapters.kt` — reusable bindings

## Conventions (the golden rules)

1. DI via constructor `@Inject`/factories; respect scopes (`@Singleton`, `@ActivityScope`, `@ServiceScope`); never instantiate singletons manually.
2. UI logic in Fragments/XML; business logic in ViewModels/Repositories; DB never accessed from UI.
3. **`StateFlow` for UI state, never `LiveData`** (cu-52): private `MutableStateFlow`, public
   immutable `StateFlow`. Collect in the UI with `collectWhileStarted(flow) { … }` (or
   `collectEventsWhileStarted` for a one-shot `Event`) on `viewLifecycleOwner` in a Fragment, on
   the Activity itself in an Activity — never a bare `lifecycleScope.launch`, which keeps
   collecting while backgrounded, and never the deprecated `launchWhenStarted`, which buffers
   instead of cancelling. **`postValue` is banned outright and `PostValueUsageTest` fails the build
   on one**: it defers to the next main-loop pass and coalesces, so a read-after-write sees a stale
   value — the shape of three cu-73 device races and of the `connect()` crash
   `MediaServiceConnection.connectIfIdle` documents. A `MutableStateFlow` assignment is thread-safe
   *and* immediate, which is why even an off-main-thread publish (a `SharedPreferences` listener, a
   `BroadcastReceiver` callback) needs no deferral.
   - **`stateIn`'s sharing policy is a real choice.** `WhileSubscribed(STOP_TIMEOUT_MILLIS)` is the
     default — it survives a rotation without re-running the query, and drops the Room subscription
     when the screen goes. Use **`Eagerly`** when a click handler reads `.value` *without*
     collecting: `AudiobookDetailsViewModel.audiobook` has five such readers, and under
     `WhileSubscribed` its offline guard read the `null` seed and let an uncached book reach the
     player with no server. A test pins that choice.
   - **Combine with `combineDistinct`** (`util/FlowCombinators.kt`), not a bare `combine`: the
     `distinctUntilChanged` is not an optimisation, it is the cu-110 fix. For a list, key it with
     `distinctUntilChangedBy { it.booksKey() }`.
   - **Testing needs a subscriber *and* a drained dispatcher.** A `WhileSubscribed` flow computes
     only while collected, and `MainDispatcherRule` installs a `StandardTestDispatcher` that queues
     rather than runs — so `.value` read without both is the `stateIn` seed, which looks exactly
     like broken arithmetic. `util/FlowTestExt.kt` has `keepCollected`, `settledValue` and
     `settledValues`; use `settledValues` when one assertion compares two flows, because
     subscribing to them one at a time makes whichever is second read its seed.
4. Coroutines: **inject `DispatcherProvider`** (cu-15) rather than referencing `Dispatchers.*` directly; UI on Main via `viewModelScope`. `GlobalScope` is gone and stays gone — three tests pin this (`CachedFileManagerScopeTest`, `RepositoryDispatcherTest`, `InternalApiUsageTest`). The five repositories, the player layer (cu-72) **and the ViewModel/Fragment/`application/` layer (cu-169)** are all converted, and `RepositoryDispatcherTest` scans all three — a fourth list, `UI_AND_APPLICATION_SOURCES`, was what let ten hardcoded sites stay green before. **Exactly two hardcoded dispatchers remain, both field initialisers that cannot read an injected one**: `MediaPlayerService.serviceScope` and `ChronicleApplication.applicationScope`, the latter because it runs *before* the Dagger graph it would inject from exists. Each is pinned at an exact count by its own test, so neither can become a precedent. **Workers are a deliberate exemption** (cu-152): WorkManager builds them reflectively with a fixed `(Context, WorkerParameters)` signature, so a constructor cannot take a `DispatcherProvider` without a `WorkerFactory` and a `Configuration.Provider` — and that plumbing would buy nothing while no worker is unit-tested and `TestListenableWorkerBuilder` supplies its own executor anyway. The two `withContext(Dispatchers.IO)` calls that remain are *correct*: `doWork` runs on `Dispatchers.Default` and both wrap real blocking file I/O. `WorkerDispatcherTest` pins the exemption list and asserts every file on it really is a `CoroutineWorker`.
5. **Never call `Injector.get()`** (cu-33). Take dependencies as constructor parameters — a class
   that fetches its own at runtime **cannot be constructed in a unit test at all**, because
   `ChronicleApplication.get()` is `INSTANCE!!` and the first line reaching the locator throws NPE.
   That, and `Dispatchers.Main` (cu-15's `MainDispatcherRule`), are the two reasons nine of the
   twelve ViewModels had no test. `ServiceLocatorUsageTest` fails the build on a new call. Two
   exemptions, both on its list: `CoroutineWorker`s (WorkManager builds them reflectively with a
   fixed `(Context, WorkerParameters)` signature — the cu-152 reasoning) and `ChronicleApplication`
   itself, which *is* the DI root. A framework-inflated `View`, a binding adapter or an extension
   function has no constructor either — pass what it needs at the call site, as `SettingsList`,
   `bindImageRounded` and `Player.skipToNext` now do.
6. User-facing text in `res/values/strings.xml`, always.
7. Room schema change ⇒ bump DB version + write a migration in the same PR.
8. Navigation through `Navigator.kt`; data via Bundles/args.
9. Playback via `MediaServiceConnection`/`MediaPlayerService` — never touch ExoPlayer from UI.
10. Network endpoints in `PlexService.kt`; errors handled in repositories; log with Timber (`Timber.e(e, "context")`).
11. ktlint style; no wildcard imports; new libraries needing keep rules ⇒ update `app/proguard-rules.pro` **and** run `./test_release_build.sh`.

## Gotchas (things that waste agent runs)

- **Five separate Room databases** (`BookDatabase` v14, `TrackDatabase` v7, `ChapterDatabase` v3, `CollectionsDatabase` v3, `BookmarkDatabase` v1), each with its own version and migration list — a schema change means finding the right one. None use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never silently wipe listening progress. Add a case to `RoomMigrationTest` for any new migration — and note the *load-bearing* check is `RoomSchemaTest`, which opens a real file at the old schema and lets Room migrate it; an in-memory test cannot catch a migration that disagrees with its entity.
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
- **Chapters live in `ChapterDatabase` and nowhere else** (cu-49, cu-82, cu-159). The legacy
  `Audiobook.chapters` column is **gone** as of v14 — do not reintroduce a serialized copy on the
  book. Every read goes through `resolveChapters` / `resolveChaptersFromCache`
  (`data/model/ChapterAssembly.kt`), now two levels: table → `asChapterList()`. The fallback is
  permanent (cu-13): a server reporting no chapters has nothing to fall back *to*, so one chapter
  per track is derived instead. A book with no rows repairs itself — `syncAudiobook` refetches
  from `/library/metadata/{id}?includeChapters=1` whenever the book is opened.
  **Dropping the column was safe because it was already empty**: nothing had written it since
  cu-49, and both household installs read 0 of 196 books before the drop. The cu-158 backfill and
  `ChapterListConverter` went with it. Verified on the tablet — v14, 196 books, 6 positions and
  138 series indices intact, and a 107-chapter book playing with its chapters resolved.
  **Read `currentlyPlaying.chapters`, never a chapter list off the book** — the singleton's
  resolved list is public because ten call sites in `PlayerExt` and `CurrentlyPlayingViewModel`
  were resolving `indexOf` to `-1` against the empty column, so chapter skip silently did nothing.
  **Rows are passed *into* `CurrentlyPlayingSingleton.update`, never read inside it**: it runs once
  a second from `ProgressUpdater`, so a DAO there is a blocking read per tick (cu-110). The callers
  that fire on the book *changing* supply them; the per-tick caller passes none, and a test pins
  that a tick without rows cannot downgrade an already-resolved list.
  Chapter offsets are *absolute within the book*, not per-track: two separate bugs came from a
  per-track `0L` (cu-13, cu-49), and `getChapterAt` silently resolves nothing when they are wrong.
  Since cu-136 the frame is a **type**, so that mistake no longer compiles — see below.
- **A refresh may only delete books belonging to the source doing the refreshing** (cu-80).
  `planIngestion` scopes removal by `Audiobook.source`; the Plex-only path deleted every local row
  absent from its fetch, which is safe with exactly one source and a **library-wipe with two** —
  taking listening progress no server holds a copy of. An **empty fetch removes nothing** either: a
  source answering `Ok(emptyList())` is a failed refresh, not an emptied library, and a *failed*
  fetch never reaches ingestion at all. All three are sabotage-verified. `refreshData` and
  `refreshDataPaginated` share that one path — the tail was written out twice before, and cu-156 had
  already had to add tag seeding to both copies.
  **`source` is a real per-instance id since cu-127, not the constant `0` it was** — see the
  scoping entry below. Until then every one of the household server's 196 rows carried
  `MEDIA_SOURCE_ID_PLEX` (`0L`), which is why cu-80's source-scoped removal compared a constant
  against itself; three test fixtures used `1L` and nothing noticed until that removal made them
  fail, the cu-24 fixture trap in a new field.
- **Stored rows are scoped by *source instance* — one Plex server — and the reads enforce it**
  (cu-127, decision-21). `SourceId` (`data/model/SourceId.kt`) is a `String` value class holding
  `"plex:<clientIdentifier>"`; `Audiobook`, `Collection` and `MediaItemTrack` all carry one. Four
  things to know before touching this:
  - **The repository is the seam, and that is what makes it enforceable.** All 78 DAO call sites
    live in four repositories; nothing in `features/`, `application/` or the player reaches a DAO.
    Each repository resolves `currentSourceId` from the connected server and passes it the same way
    it already passes `prefsRepo.offlineMode`.
  - **A read that returns rows without naming one must filter by source, and `ScopedQueryTest`
    fails the build otherwise.** A query keyed on a primary key (`WHERE id = :bookId`) or on one
    book (`parentKey = :`) is exempt — the id is already unique, and a filter there masks bugs
    rather than preventing them. Chapters and bookmarks carry no `source` at all for that reason:
    every query of theirs is keyed by `bookId`. The guard exists because an unscoped read fails
    *silently* — the symptom is a union that only appears with two servers configured, so nothing
    else here can catch a newly-added one.
  - **`SourceId.UNKNOWN` is inert, never a wildcard.** `planIngestion` writes nothing when the
    scope is unresolved (mid-login, or after `PlexConfig.clear()`): stamping rows with it would put
    them beyond both the removal rule and every scoped read — a catalogue that grows and can never
    be pruned. Three test suites were stubbing `PlexPrefsRepo` without a `server` and so silently
    stopped exercising ingestion once that guard landed; stub `server`, not just `library`.
  - **Migrating rows land on `SourceId.LEGACY_PLEX`, and `adoptLegacyRows` claims them on the next
    launch.** A `SupportSQLiteDatabase` cannot know which server the app is configured for, so the
    v12→v13 and v6→v7 migrations mark every existing row and `ChronicleApplication` adopts them —
    launched, not awaited, so there is a brief window on the first launch after upgrading where the
    library reads empty and fills. Adoption matches the marker **only**, never a resolved source,
    or a second server could take over the first's library. A plain `CAST(source AS TEXT)` in that
    migration would have produced the string `"0"` — schema-valid, right row count, and every book
    permanently invisible; that is what the migration tests assert, by sabotage.
  What this does **not** fix: `Audiobook.id` is still the sole primary key, so two servers sharing
  a Plex rating key still collide on insert. decision-21 rejected a composite key deliberately
  (cu-71's lesson). Scoping removes the *union*, which is what a user sees. Downloads therefore
  stay at `<cachedMediaDir>/<trackId>.<ext>`: the per-source path decision-21 specified would buy
  nothing while one id means one row means one filename, and it would touch four file paths whose
  failure mode is deleted audio (cu-85, cu-81, cu-153, cu-76). A test pins that reasoning and
  fails the moment the primary key stops being what prevents the collision.
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
- **The progress tick stops the moment playback pauses, so every pause path must flush** (2026-09-05).
  `ProgressUpdater.startRegularProgressUpdates` is gated on `isPlaying`, so pausing silently ends
  the per-second write: without an explicit flush the saved position is whatever the previous tick
  captured, and no `PLEX_STATE_PAUSED` ever reaches the server. Three paths now flush —
  `flushOutgoingBookProgress` on a book switch (cu-91), `onSeekTo` on a seek (cu-93), and `onPause`
  — and a **fourth pause route added later would need its own**. Same defect class as
  advplyr/audiobookshelf-app#1847 and PaulWoitaschek/Voice#3351.
  **Read the position from the player, never from the session**: `MediaSessionCompat`'s playback
  state lags a frame, so `updateProgressWithoutParameters` on a pause or seek path reports the
  *pre-action* position as still PLAYING — worse than not flushing, since it overwrites a good
  position with a stale one. That is what cu-93 hit; `PauseFlushesProgressTest` seeds a deliberately
  stale session position so the regression fails loudly instead of passing by luck.
- **Embedded cover art is never decoded** (2026-09-05). An audiobook is one very large file with a
  single APIC/`covr` frame, and ExoPlayer's default copies it into a heap byte array per media item
  — upstream's `OutOfMemoryError` in `MediaMetadata.maybeSetArtworkData`
  (mattttvaughn/chronicle#83, #16, both still open). `artworkFreeExtractorsFactory` disables it for
  mp3 and mp4; artwork comes from Plex via `getBitmapFromServer(book.thumb)`, so nothing reads it.
  It is a **top-level function, not a member of `ServiceModule`**, because the provider needs a live
  `Service` and cannot be reached from a unit test at all — a test that rebuilt the same flags would
  pass while the player was built with different ones. `EmbeddedArtworkTest` runs a real MP3 with a
  real PNG cover through the production function, plus a second test asserting the fixture still
  carries a frame under stock flags so the first cannot pass vacuously.
- **Do not do per-second work whose result cannot change** (cu-110). `ProgressUpdater` writes once
  a second during playback and Room invalidates **per table**, so every query on `Audiobook`
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
- **`postValue` deferred to the next main-loop pass**, so a flag it set could not guard anything
  read in the same pass (cu-110). `MediaServiceConnection.connectIfIdle` tested `isConnected.value`,
  which `onConnected` published with `postValue` while clearing `isConnecting` immediately — so
  both read idle while the browser was CONNECTED, and `MediaBrowserCompat.connect()` throws rather
  than ignoring a redundant call. **`postValue` is gone tree-wide since cu-52** and
  `PostValueUsageTest` fails the build on a new one, so this exact shape cannot return; the general
  lesson survives it, which is to **ask the collaborator's own synchronous state** rather than a
  published mirror of it. `connectIfIdle` still tests `mediaBrowser.isConnected` for that reason —
  the browser's state also moves *during* `connect()`, before any callback of ours runs.
- **The Plex auth token is resolved in one place, and empty counts as absent** (cu-33).
  `PlaybackSession.authToken` is the only statement of the precedence — server access token, then
  the profile's, then the account's. It was written out **twice** before, in
  `AudiobookMediaSessionCallback` and `ServiceModule.plexDataSourceFactory`, and both were wrong the
  same way: they used `?:`, but neither `ServerModel` nor `PlexUser` stores null for a missing
  token — `asServerModel` writes `accessToken = this.accessToken ?: ""`. So a server reporting no
  token of its own, **the ordinary case for a server the user owns**, stored `""`, won the elvis,
  and authorized every media request with an *empty* `X-Plex-Token` while a good account token sat
  unused. `PlaybackSession` also owns the `/playQueues` session handshake, whose failure is logged
  and swallowed on purpose: the media is already resolved, and the endpoint is unofficial.
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
- **Plex audiobook metadata is a convention hack**: narrator = `Style` tags, series = `Mood` tags.
  Never treat these as music semantics. The convention is **Audnexus's, not seanap's** — seanap's
  guide is an *ID3* convention (`TCOM` = narrator, `TPE1` = author/narrator) and never touches
  Plex's Style/Mood fields; only the Audnexus.bundle agent writes them. An earlier version of this
  line credited both.
  **`Mood` carries authors as well as series** (2026-09-05). `add_series_to_moods` writes
  `"Series: <name>"` unconditionally, while `add_authors_to_moods` writes a **bare** author name
  and is gated on the agent's `store_author_as_mood` preference — verified in
  `Contents/Code/update_tools.py`. Plex returns moods alphabetically, so taking the first non-empty
  tag filed any book whose author sorts before its series under a series named after the author.
  `seriesName()` prefers a **prefixed** tag for that reason and falls back to an unprefixed one only
  when nothing is labelled. It reproduces only on servers with that preference enabled, which is why
  fixtures written to match the code never showed it — the cu-24 trap in a new field.
  Both are **detail-only** (cu-24): `/library/metadata/{id}` carries them, the library listing
  `/library/sections/{id}/all` does **not** — verified against fixtures captured from a real Plex
  1.43.3 server, and there is no `includeFields`/`includeTags` that would add them. So today the
  index fills in as books are synced (`syncAudiobook` already fetches the detail), and
  `FacetList.unknownCount` exists so the UI is obliged to say how partial it is.
  **A refresh now seeds the index anyway** (cu-143): `TagIndexSeeder` enumerates a tag filter's
  distinct values (`/library/sections/{id}/style?type=9`) and lists the books carrying each
  (`/all?type=9&style={tagKey}`), so narrator and series fill in for books nobody has opened —
  `1 + N` requests per field instead of one per book. Seeding runs **after** `Audiobook.merge` and
  **never overwrites a non-empty field**: the detail response is precise and this index is the
  coarser source, so overwriting would blank correct metadata on every refresh. Failure is per
  value and never fatal, since the endpoints are community-documented. **Routing trap:**
  `/library/sections/{id}/style` contains neither `/all` nor a query, so in both fixture servers it
  fell through to the bare-section rule and answered `libraries.json` — the seeder would have read
  a library list as a list of narrators; both routers match the tag paths first now. The route is
  verified in python-plexapi's source and against fixtures, **not against a real server** — in
  particular whether a live Plex returns `key` as `/library/sections/1/style/301`, which is what
  the id is parsed out of. The *multi-id* route, `/library/metadata/{id1},{id2},...` ("Get one or
  more metadata items" in the API spec), would be cheaper still but is spec-verified only. Don't
  re-derive this — and note a 2026-09-05 review claimed python-plexapi "uses it in four places",
  which is **false**: those call sites are `/library/sections/{id}/common` and a PUT to
  `/library/sections/{id}/all`, both of which pass comma-joined ids as an `id=` *query parameter*
  to different endpoints. There is no comma-joined `/library/metadata/{id1},{id2}` read anywhere in
  python-plexapi. The route remains unverified against a real server. `merge` needs a **third** rule for fields like these — the
  network value when it has one, the local value when it does not: preferring the network blanks a
  narrator on every refresh, preferring the local one makes a re-tagged book uncorrectable.
  `Audiobook.seriesIndex` is parsed from `titleSort`, **not** Plex's `index`, which is the album
  ordering index and is 1 for nearly every audiobook — and **not** from `Mood`, which carries the
  series name without a number.
- **The series index is parsed from anywhere in `titleSort`, in hundredths** (cu-146). The parser
  was **end-anchored** and both dominant taggers put the number at the *front* — Audnexus writes
  `"<Series>, Book <n> - <Title>"`, seanap prescribes `"<Series> <n> - <Title>"` — so it read
  **1 of 8** real formats, the one being our own fixture, which happened to end with the number
  (the cu-24 fixture trap in a new field). `SERIES_INDEX_PATTERNS` now holds eight patterns tried
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
  so. An eighth pattern, `audnexus_subseries`, reads the `<Series>, Book <n>, <Subseries> - <Title>`
  shape where the number is terminated by a **comma** (cu-155). It must follow `audnexus`, and it
  keeps the `Book`/`Vol` label **required** — that requirement is the only thing stopping
  `"Warhammer 40,000"` from reading as book 40000, and dropping it is sabotage-verified to break the
  parse.
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
  *matching* pattern never declared rather than returning null, so four of the eight built-ins
  (which declare no `series` group) crashed every match until every named read went through
  `namedGroupOrNull`. A user's own rules live in **`series-index-rules.json`** in the app's files
  directory (cu-148) — `{version, order, rules:[{name, pattern, description}]}`, absent by default,
  and **every** failure degrades to the built-ins: malformed JSON, a newer version, an unknown
  order, a nameless rule, an uncompilable regex. `order` is parsed as a *string* rather than a Moshi
  enum on purpose, since an unknown constant would make Moshi reject the whole file and take the
  valid rules with it. The load runs off the main thread (StrictMode penalises a disk read in
  `Application.onCreate`) and is launched rather than awaited. The tester UI is **cu-151**, and it
  is not optional polish — tvnamer's #216 is a user who could not tell whether their pattern or the
  tool was wrong.
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
- **Changing the sync location does *not* strand partial downloads** (cu-153). Fetch2 downloads
  **in place** and resumes over HTTP Range, so a partial is named `<trackId>.<ext>` exactly like a
  finished file — there is no `.part`/`.tmp` suffix. `MoveSyncLocationWorker` selects with
  `MediaItemTrack.cachedFilePattern`, cannot tell the two apart, and moves both. Correct, but
  load-bearing: give partials a distinguishing suffix and they start being orphaned, because
  cu-81's prune only ever scans the *active* `cachedMediaDir`. `SyncLocationMoveTest` pins it.
  Verified on the tablet's two real volumes (internal + a physical SD card) in **both** directions —
  worth doing both, since they are different filesystems and `Files.move` may fall back to
  copy+delete across them. `--es move_sync_location <dir>` is the debug hook that replays it; it
  validates the path against `externalDeviceDirs()` by **exact** match, since `cachedMediaDir`
  accepts any string and a bad one fails much later as "downloads don't work".
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
  **`FirstFrameFlashTest` is now the gate** (cu-68): it fails the build on any Kotlin-driven view
  with no XML default. "For a frame" understates it — several sources are cold (a
  `stateIn(WhileSubscribed)` before anything collects it, a `combine` waiting on a slow source), so
  the default held
  long enough to read "No libraries found" over onboarding, with the bottom nav and mini player on
  top of the login screen. 34 views were swept. The guard also checks the **mirror** risk, which is
  worse: a view defaulted to `gone` with no writer is *permanently* invisible. It caught two —
  driven through a local `tempBinding` rather than `binding`, so match `\w*[Bb]inding` when
  scanning for these, not just `binding`.

## Definition of done

1. Verify loop green (above).
2. Tests added/extended for touched repositories, ViewModels, sync/download/chapter logic (D6). Fixture-backed where network is involved (cu-16 fixture pattern).
3. **Self-review pass done** (principle 2): diff re-read for correctness, silent failures, dead code, simpler alternatives; error paths log with context and never swallow.
4. Docs synced in the same PR: relevant `backlog/docs/reference/` file if architecture/behavior changed; the task file's status/criteria updated; this file if any statement here became false.
   **The correct closing status is `In Review`, not `Done`, whenever the work changed a screen or
   made a product choice** — see the status rule under Workflow below. `Done` is for work a machine
   proved right.
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

Tasks are markdown files in **`backlog/tasks/`** (Backlog.md format: `task-<id> - <Title>.md`, frontmatter `status`/`labels`/`dependencies`/`priority`/`milestone`, body `## Description` + `## Acceptance Criteria` checkboxes; **`milestone: m-<n>` mirrors the `R<n>` label — set both, they are one fact stored twice**). Statuses: `To Do → In Progress → In Review → Done`.

**`In Review` means "waiting for the owner", and it is not optional** (owner rule, 2026-09-04).
An agent may close a task straight to `Done` only when the proof is *automated* — a test, a build
gate, a measurement a script reproduces. A task must be left `In Review` when the remaining question
needs a human to look:

- it **changed a screen** — layout, wording, an icon, what a state looks like;
- it made a **product or design choice** the owner might want differently — a sort order, a default,
  a threshold tuned by ear, a set of presets, a user-facing file format;
- it has an **acceptance criterion that is a visual or on-device check** which was not performed.
  Leave the box unchecked *and* the status `In Review`; do not tick it on the strength of a test
  that cannot see what the criterion asks about.

A bug fix with a failing-then-passing test and no visible design decision goes to `Done` — that is
the majority of debt, guard and correctness work, and routing it through review wastes the owner's
attention. The question is not "feature or bug", it is **"can a machine prove this was right?"**

When moving a task to `In Review`, say in the task file *what specifically needs the owner's eye* —
"the shelf's sort order", not "please review". A criterion that turned out to be wrong rather than
unmet is **retired with its reasoning**, never silently ticked. The optional [Backlog.md CLI](https://github.com/MrLesk/Backlog.md) (`brew install backlog-md`; `backlog board`, `backlog task list -s "To Do"`) is a convenience — **editing the files directly is always valid and canonical.**

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
  invisible in the board and to `backlog draft list`. **The *filename* must use the lowercase
  `draft-<n>` prefix while the frontmatter `id` stays uppercase `DRAFT-<n>`** — they genuinely
  differ, and a file named `DRAFT-<n> - …md` is invisible to both `backlog draft list` and
  `backlog draft DRAFT-<n>` while sitting in place and parsing correctly. Renaming it needs a
  temporary name in between, since a case-insensitive filesystem treats the two as one file. Keep `<n>` from the cu number it will take, and
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

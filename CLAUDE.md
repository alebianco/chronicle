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
./verify.sh            # the full gate: ktlint, unit tests, coverage ratchet, debug APK, lint
./verify.sh --quick    # inner loop while iterating: ktlint + unit tests + coverage only
./verify.sh --format   # runs ktlintFormat first, then the full gate
```

- `verify.sh` **is** the definition of "the build is fine" (D12 rule 6) — not CI, not a forge's required checks. CI is a thin wrapper that calls this same script, so the gate is identical on a laptop and on any forge.
- Green = ktlint clean + unit tests pass + coverage did not regress + debug APK builds + lint passes. Nothing less.
- **Coverage ratchet**: `coverage-ratchet.sh` compares JaCoCo instruction coverage against the committed `coverage-baseline.txt` and fails on a drop; a rise ratchets the baseline up (commit the change). Baseline is deliberately a plain file in git so every movement is reviewable in a diff. To lower it on purpose: `./coverage-ratchet.sh --update`, and justify it in the commit message.
- Release builds: `./test_release_build.sh` (R8/ProGuard smoke test; see CONTRIBUTING.md "Release Builds & ProGuard"). Run it whenever touching ProGuard rules, reflection-adjacent code (Moshi models, Room entities), or dependencies. It asserts against the **dex** that Room/Retrofit/Dagger/Moshi classes survived R8 — these fail at runtime, not build time. Keep rules are deliberately narrow (cu-45): prefer adding one precise rule over widening a blanket `-keep`, which silently exempts code from R8.
- **Instrumented tests are quarantined, not just disabled**: the sources in `app/src/androidTest` no longer compile — they target an `OnboardingActivity` and strings removed when onboarding became Fragments (`9e89270`). `DebugAndroidTest` tasks are force-disabled in `app/build.gradle.kts`; the fake CI emulator job was deleted in cu-3. **There is no instrumented coverage — never claim any.** Rebuilding the suite is task cu-54.

## Project snapshot (truthful as of 2026-08-31 — verify against build files if in doubt)

- Single module `:app`, Kotlin **2.2.10**, minSdk 27, target/compileSdk **36** (cu-6). Gradle 9.5.1 + AGP 8.13.2 — note AGP 8.x cannot use Gradle >= 9.6.0, and AGP 9.x absorbs the Kotlin plugin (its own migration).
- MVVM + Repository · Dagger 2.57.2 (hand-rolled components) · Room **2.8.1 (stable, since cu-1) — always write a migration with any schema change; all four DBs export schemas and have migration tests** · Retrofit/OkHttp + Moshi (reflection mode) · Media3 **1.11.0** (ExoPlayer + MediaSession + Cast; cu-7) · LiveData + **ViewBinding** (DataBinding removed in cu-58; no Compose) · Fetch2 for downloads.
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
  really does flow: all three track parts are fetched and decoded (`AudioFlinger` confirms 15.000s).
  An earlier claim that no request reached the mock was a logging blind spot, not a bug — the log sat
  below the early returns (cu-64). Seeks are still unexercised end-to-end, so the 206/range path is
  unit-tested only. `./capture-screens.sh <dir>` drives the app and screenshots the main
  screens; it asserts the app was actually foregrounded, because an earlier version silently captured
  the launcher.
- Tests: 14 unit-test files, **73 tests** (`app/src/test/...`), including `RoomMigrationTest` which drives the historical migration chains through real SQLite via **Robolectric** (Room's `MigrationTestHelper` is instrumented-only). ~7 androidTest files, quarantined (see above). Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
- CI: `.github/workflows/ci.yml` — a single `verify` job that runs `./verify.sh` and uploads the APK, test results and coverage report. All build logic lives in `verify.sh`/Gradle, never in the workflow (D12 rule 6).

## Map (fast navigation)

- `app/build.gradle.kts` — plugins, SDK versions, dependencies · `gradle/libs.versions.toml` — version catalog
- `application/ChronicleApplication.kt`, `application/MainActivity.kt` — entry points + DI root
- `injection/` — Dagger components/modules/scopes
- `data/local/` — Room DBs, DAOs · `data/sources/plex/` — Plex API (`PlexService.kt`), login/config, `CachedFileManager.kt`
- `data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt`, `data/sources/local/LocalMediaSource.kt` — multi-backend scaffolding, **declared but not yet load-bearing**. cu-15 added the D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) and made `SourceManager.refreshBooks` fail loudly instead of silently discarding fetches, but the fetch methods on both `LocalMediaSource` and `PlexMediaSource` are still `TODO("Not yet implemented")` — the live Plex work is in `PlexMediaRepository`. Don't call it; don't delete it (cu-33 resurrects it properly).
- `features/` — Fragment + ViewModel + adapters per feature (27 files import `data.sources.plex.*` directly — known debt, task cu-33)
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

- **Four separate Room databases** (`BookDatabase` v8, `TrackDatabase` v4, `ChapterDatabase` v1, `CollectionsDatabase` v1), each with its own version and migration list — a schema change means finding the right one. None use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never silently wipe listening progress. Add a case to `RoomMigrationTest` for any new migration.
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
- **Never log an auth token.** `TokenLoggingTest` fails the build on any `Timber` call that
  interpolates one — it caught three live leaks, including one logging *two* tokens per media
  item. Logging *presence* (`token.isNotEmpty()`) is fine and is what the guard permits.
- **Connections are tiered, not raced** (cu-11). `ConnectionChooser` tries LAN, then direct
  WAN, then relay, each tier getting a 1.5s budget before the next also starts (earlier
  attempts keep running, so a slow LAN address can still win). The **last** tier is awaited
  for a real answer, which is what keeps a LAN-only server working. `Connection.relay` comes
  from `/api/v2/resources` and is checked *before* `local`, because Plex can report a relay
  route with `local = 1`. Don't reorder `ConnectionTier` — its declaration order *is* the
  preference order.
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

Tasks are markdown files in **`backlog/tasks/`** (Backlog.md format: `task-<id> - <Title>.md`, frontmatter `status`/`labels`/`dependencies`/`priority`, body `## Description` + `## Acceptance Criteria` checkboxes). Statuses: `To Do → In Progress → In Review → Done`. The optional [Backlog.md CLI](https://github.com/MrLesk/Backlog.md) (`brew install backlog-md`; `backlog board`, `backlog task list -s "To Do"`) is a convenience — **editing the files directly is always valid and canonical.**

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
- `backlog/tasks/` — the work (one file per task). `backlog/drafts/` — uncommitted ideas awaiting owner triage.
- `backlog/decisions/` — decision records `decision-<n> - <Title>.md` (context → decision → consequences): product decisions D1–D14 (owner-only) + technical ADRs (agents may add). Framing, won't-do, and risks live here (decision-9/11/14).
- `backlog/docs/reference/` — architecture knowledge base (project overview, architecture, data flow, components, glossary); keep in sync with behavior.
- `backlog/docs/analysis/` — *optional* deep-reference for debt items (C1–C6, H1–H8, M1–M7): problem/current-state/risk, linked from a task only when the understanding is too large to inline. `analysis/archive/` holds ones whose task is Done and content is stale. These are *analysis*, not execution plans.
- `docs/superpowers/` — **gitignored working scratch, never committed** (the whole tree). Superpowers writes execution plans to `plans/` and brainstorming design specs to `specs/`; both are drafting output. Redirect their durable content into `backlog/`, distilled by *kind* — a spec is not one artifact:
  - forward design / requirements / approach → the **task's `## Implementation Plan`** (feature-scoped, lives with the work);
  - a genuine cross-cutting choice that outlives the feature ("Coil not Glide, because …") → a **`backlog/decisions/` ADR** — the decision only, not the whole design;
  - large problem/current-state analysis → an optional **`backlog/docs/analysis/`** file.
  Default is the task file; spin off an ADR only for durable architectural choices. Don't leave anything stranded in `docs/superpowers/`.
- `backlog/docs/research/` — evidence base (`RESEARCH_FINDINGS.md`, `COMMERCIAL_VIABILITY_REPORT.md`); cite, don't duplicate. `research/design-references/` — competitor/design screenshots (uncommitted third-party assets).

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
./gradlew ktlintFormat ktlintCheck testDebugUnitTest assembleDebug lintDebug
```

- Green = ktlint clean + unit tests pass + debug APK builds + lint passes. That is the definition of "the build is fine" — nothing less.
- Release builds: `./test_release_build.sh` (R8/ProGuard smoke test; see CONTRIBUTING.md "Release Builds & ProGuard"). Run it whenever touching ProGuard rules, reflection-adjacent code (Moshi models, Room entities), or dependencies.
- **Instrumented tests are currently dead**: `DebugAndroidTest` tasks are force-disabled in `app/build.gradle.kts` (search `DebugAndroidTest`), so the CI emulator job is a no-op. Do not claim instrumented coverage. Fixing this is task cu-3.

## Project snapshot (truthful as of 2026-07-13 — verify against build files if in doubt)

- Single module `:app`, Kotlin, minSdk 27, target/compileSdk **34** (SDK 36 bump is task cu-6).
- MVVM + Repository · Dagger 2 (hand-rolled components) · Room **2.7.0-alpha12 — alpha in production, never bump casually, always write migrations** · Retrofit/OkHttp + Moshi (reflection mode) · Media3 1.3.0 (ExoPlayer + MediaSession + Cast) · LiveData + DataBinding (no Compose) · Fetch2 for downloads.
- **KAPT, not KSP** (`kotlin-kapt` in `app/build.gradle.kts`) — migration is task cu-8. Any doc claiming KSP is wrong.
- Tests: 2 unit-test files (`app/src/test/...`), ~7 androidTest files (disabled, see above). Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
- CI: `.github/workflows/ci.yml` — ktlintCheck, assembleDebug (+APK artifact), testDebugUnitTest (+results artifact), emulator matrix (currently no-op).

## Map (fast navigation)

- `app/build.gradle.kts` — plugins, SDK versions, dependencies · `gradle/libs.versions.toml` — version catalog
- `application/ChronicleApplication.kt`, `application/MainActivity.kt` — entry points + DI root
- `injection/` — Dagger components/modules/scopes
- `data/local/` — Room DBs, DAOs · `data/sources/plex/` — Plex API (`PlexService.kt`), login/config, `CachedFileManager.kt`
- `data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt`, `data/sources/local/LocalMediaSource.kt` — **dead multi-backend scaffolding, all `TODO()`, not in DI**; being resurrected per D11 (task cu-15). Don't call it; don't delete it.
- `features/` — Fragment + ViewModel + adapters per feature (27 files import `data.sources.plex.*` directly — known debt, task cu-33)
- `navigation/Navigator.kt` — centralized navigation · `views/BindingAdapters.kt` — reusable bindings

## Conventions (the golden rules)

1. DI via constructor `@Inject`/factories; respect scopes (`@Singleton`, `@ActivityScope`, `@ServiceScope`); never instantiate singletons manually.
2. UI logic in Fragments/XML; business logic in ViewModels/Repositories; DB never accessed from UI.
3. LiveData for UI state: private `MutableLiveData`, public immutable `LiveData`. (StateFlow migration is future work — don't mix ad hoc.)
4. Coroutines: IO on `Dispatchers.IO`, UI on Main via `viewModelScope`. Known debt: `GlobalScope` in `CachedFileManager` and hardcoded dispatchers (items C4/H5, backlog 12) — don't add more of either.
5. User-facing text in `res/values/strings.xml`, always.
6. Room schema change ⇒ bump DB version + write a migration in the same PR.
7. Navigation through `Navigator.kt`; data via Bundles/args.
8. Playback via `MediaServiceConnection`/`MediaPlayerService` — never touch ExoPlayer from UI.
9. Network endpoints in `PlexService.kt`; errors handled in repositories; log with Timber (`Timber.e(e, "context")`).
10. ktlint style; no wildcard imports; new libraries needing keep rules ⇒ update `app/proguard-rules.pro` **and** run `./test_release_build.sh`.

## Gotchas (things that waste agent runs)

- **Room is alpha** — schema/API can differ from stable docs; check the exact version's behavior.
- **KAPT** — build errors in generated code usually mean an annotation problem upstream, and builds are slow; don't loop blindly.
- **No 401 re-auth exists** — an expired Plex token surfaces as a UI error string (`MainActivity`), not a refresh. Fixing = task cu-10.
- **Plex unofficial endpoints** (`/:/timeline`, scrobble, websockets) are community-documented, not guaranteed — keep them wrapped behind repositories/the MediaSource seam.
- **Plex audiobook metadata is a convention hack**: narrator = `Style` tags, series = `Mood` tags (Audnexus/seanap). Never treat these as music semantics.
- `NOTES.md` history: the old `freeAsInBeer` product flavor **no longer exists**; there are no flavors. Release signing per CONTRIBUTING.md.
- DataBinding: layout changes can produce stale generated classes — a `clean` fixes phantom binding errors.

## Definition of done

1. Verify loop green (above).
2. Tests added/extended for touched repositories, ViewModels, sync/download/chapter logic (D6). Fixture-backed where network is involved (cu-16 fixture pattern).
3. **Self-review pass done** (principle 2): diff re-read for correctness, silent failures, dead code, simpler alternatives; error paths log with context and never swallow.
4. Docs synced in the same PR: relevant `backlog/docs/reference/` file if architecture/behavior changed; the task file's status/criteria updated; this file if any statement here became false.
5. Attribution trailer if code was ported (principle 4).
6. Commit messages: imperative summary + why; no Co-Authored-By lines.

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

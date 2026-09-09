# Constitution — Chronicle Unabridged

Development standards for this repository. `CLAUDE.md` is the entry point and stays short; this is
the durable statement of *how we work*. Where this file contradicts the code, **the code wins** —
then fix this file in the same PR.

**Version:** 1.0 · **Last updated:** 2026-09-06

---

## Development principles (owner rules, 2026-07-13)

1. **Agentic-first, geared to Claude Code.** The repo must stay agent-implementable: truthful docs,
   headless verify loop, hermetic tests. Anything that degrades an agent's ability to close the
   loop is a bug.
2. **Claude is implementer *and* architect.** The owner rarely reviews code or gives code-level
   direction. **Self-review is mandatory, not optional**: before declaring any non-trivial change
   done, run the verify loop, re-read the diff critically (correctness, silent failures, error
   handling, simplification), and prefer industry-standard patterns over cleverness. When an
   architectural decision is needed, make it, record it, and state the trade-off — don't wait for
   direction.
3. **Prefer third-party libraries over hand-rolled solutions** — when maintained and
   licence-compatible with GPLv3 (Apache-2.0, MIT, BSD, MPL are fine; check before adding). A
   boring, well-tested dependency beats bespoke code an agent must maintain forever. Exception:
   trivial utilities where a dependency is pure weight.
4. **Acknowledge origins and influences.** Upstream author Matt Vaughn (mattttvaughn) stays
   credited in README/About. Code or patterns ported from the fabiogermann "Chronicle Epilogue"
   fork carry attribution in the commit message (`Ported-from: fabiogermann/chronicle <ref>`).
   Design influences (Prologue, Pocket Casts, Libby per `../research/RESEARCH_FINDINGS.md` §3.1)
   are credited in docs, never copied as assets. Epilogue and upstream *branding* are off-limits
   (All Rights Reserved).
5. **The primary user is the owner's household.** Features are judged by the north-star (*zero
   interventions*, defined in `backlog/decisions/`), not by imagined market users. When in doubt,
   the Trust → Comfort → Delight → Differentiation ordering (the `R0`–`R4` labels) decides.
6. **File over app** (<https://stephango.com/file-over-app>) — no lock-in to GitHub-only features.
   All non-code knowledge lives as **markdown in `backlog/`** (D13). CI logic lives in
   `verify.sh`/Gradle so any CI system is a thin wrapper. Plain git + markdown must be enough to
   move the whole project to another forge without loss.
7. **Open formats, DRM-free, no data extraction** (decision-19). Open file formats for state
   (JSON/zip exports per D8, markdown for docs); DRM-free audio only (DRM stores are a
   permanent won't-do, decision-14); OFL fonts; prefer open/keyless APIs.

### The dependency rule (principle 7, in full)

**No dependency may extract the household's data or gate functionality behind a third party.**
Analytics, telemetry, crash reporting, advertising and anything needing a cloud account are
permanently out *whatever their licence* — the objection is the data flow, and an open-source
analytics SDK is equally banned.

A **proprietary SDK for a device capability the platform exposes no other way** is permitted when
all four hold:

1. it sends the household's data nowhere;
2. it **degrades to absent** — a device without it loses that one feature, with no crash, error or
   nag;
3. it is **confined behind a seam**, so an open replacement would be a swap;
4. there is **genuinely no open alternative** reaching the same hardware.

Each one admitted is recorded as an ADR. Google Cast is the **only** one — every SDK reference sits
inside `CastPlayerProvider`, and no open protocol reaches a Chromecast.

`play-services-oss-licenses` was admitted retroactively on the same reading and has since been
**removed**, so the exception now has a single occupant. Decision-19 still names it and is left
alone deliberately: a decision file records what was decided at the time, and amending it is the
owner's call. The removal was not a reversal of that reading — the licences plugin passed the
four-part test on its merits. It failed a *different* one. **Decision-1 puts sideload/F-Droid/
homelab distribution first, and F-Droid does not accept a GMS dependency at all**, so the tool that
generated the app's own licences page could not ship where the app ships. Google Cast is unaffected:
it is the fourth condition — no open protocol reaches a Chromecast — and a build without Play
Services degrades it to absent, as the rule requires.

The reusable lesson is that the four-part test asks *may we depend on this*, and distribution asks
*where can the result be installed*. A dependency can pass the first and still be disqualified by
the second, and only the first was written down.

> The earlier wording was a flat "no proprietary SDKs" whose three examples (Firebase, analytics,
> ads) were all *extraction* SDKs — it banned a category while describing a narrower harm, and
> silently forbade both of these.

**A second route covers data the user *asks* to send** (decision-20): permitted only when the user
opted in per feature (off by default, informed, revocable), it is not the business model, the
payload is an **allowlist never a dump**, it carries **no credentials**, and it degrades to absent.

**Ads, behavioural analytics, usage telemetry and any listening profile are barred regardless of
consent** — they will not be built, so there is nothing to opt into.

Two features qualify:

- **Crash reporting** — opt-in once, but *every individual report still needs a tap*, payload
  viewable. Enabling the feature is not consent to a standing upload channel.
- **Settings cloud sync** to the user's own Google Drive `appDataFolder`.

**Sync carries no auth token.** decision-8's "auth tokens excluded (re-login on restore)" stands,
enforced by both backup-rules files, `BACKUP_SETTING_KEYS` and the `ChronicleAuth.xml`
split — Plex has *no per-device revocation and no refresh token*, so a leaked account token can
only be killed by logging out every device the household owns. Migration is still one tap because
sync carries server id, library id, connections, settings, bookmarks and per-book speed — all
non-secret prefs sitting beside the credentials. Note `BACKUP_SETTING_KEYS` is **preferences only
today**; extending it is the implementing task's first job.

---

## Conventions (the golden rules)

1. **DI via constructor `@Inject`/factories**; respect scopes (`@Singleton`, `@ActivityScope`,
   `@ServiceScope`); never instantiate singletons manually.
2. **UI in Compose — a `*Screen` composable is a pure function of its state; a Circuit **presenter**
   wraps the ViewModel and a `*Ui` renders it; business logic in ViewModels/Repositories; the DB is
   never accessed from UI.** Every interaction is a member of that screen's sealed `*Event`
   hierarchy, so the presenter's `when` is exhaustive and an unwired interaction is a compile error
   (decision-27). `*Destination` composables are gone.
3. **`StateFlow` for UI state, never `LiveData`** — private `MutableStateFlow`, public
   immutable `StateFlow`. See the `android-ui` skill for collection rules and `stateIn` policy.
4. **Inject `DispatcherProvider`**; never reference `Dispatchers.*` directly. `GlobalScope`
   is gone and stays gone. Exactly two hardcoded dispatchers remain, both field initialisers that
   cannot read an injected one (`MediaPlayerService.serviceScope`,
   `ChronicleApplication.applicationScope` — the latter runs *before* the Dagger graph exists), and
   each is pinned at an exact count by its own test. The five repositories, the player layer
   and the ViewModel/Fragment/`application/` layer are all converted.
   `CoroutineWorker`s are a deliberate exemption: WorkManager builds them reflectively with
   a fixed signature.
5. **Never call `Injector.get()`.** Take dependencies as constructor parameters — a class
   that fetches its own at runtime **cannot be constructed in a unit test at all**. That, and
   `Dispatchers.Main`, are the two reasons nine of the twelve ViewModels had no test. Two
   exemptions: `CoroutineWorker`s and `ChronicleApplication` itself (which *is* the DI root). A
   framework-inflated `View`, a binding adapter or an extension function has no constructor either
   — pass what it needs at the call site.
6. **The framework-free core is a fence, not an accident.** 87 files carry no
   `android.*`/`androidx.*` import beyond Room annotations — the decision logic. They sit at
   **80.8% coverage against 35.7% for everything else**, because a file testable without a
   framework gets tested. When a listed file needs the framework, **move the framework-facing part
   out** — an earlier cleanup did exactly that for `toMediaItem`/`toAlbumMediaMetadata`/`toMediaMetadata`,
   which are a presentation concern of `features/player`, not properties of a book.
7. **User-facing text in `res/values/strings.xml`**, always.
8. **Room schema change ⇒ bump the DB version + write a migration in the same PR.**
9. **Navigation through Circuit** (decision-27). A destination is a `ChronicleScreen` key in
   `navigation/Screens.kt`, and an **argument is a field on the key** — not a string in a route, so
   there is nothing to percent-encode and no pattern to fail to match. A ViewModel that needs one
   takes it through Hilt **assisted injection** (`@AssistedInject` + `@HiltViewModel(assistedFactory
   = …)`), because Circuit's record-scoped owner provides no `SavedStateRegistryOwner` and a
   `SavedStateHandle` reached that way is empty. Resolve ViewModels with `recordViewModel()`, never
   `hiltViewModel()` directly — see `navigation/circuit/`.
10. **Playback via `MediaServiceConnection`/`MediaPlayerService`** — never touch ExoPlayer from UI.
11. **Network endpoints in `PlexService.kt`**; errors handled in repositories; log with Timber
    (`Timber.e(e, "context")`).
12. **ktlint style; no wildcard imports.** New libraries needing keep rules ⇒ update
    `app/proguard-rules.pro` **and** run `./test_release_build.sh`.
13. **`Result<V, E>` at a source boundary; a named sealed type for a domain outcome.** *Three*
    unrelated `Result` types coexist here, so always be sure which one is in scope:
    kotlin-result's `com.github.michaelbull.result.Result` (`Ok`/`Err`), stdlib `kotlin.Result`
    (`success`/`failure`, used inside `MoveSyncLocationWorker` and by `runCatching`), and
    WorkManager's `ListenableWorker.Result` — which is why that worker writes `kotlin.Result`
    fully-qualified. **None of them has `Result.Success` / `Result.Failure` subtypes**; a `when`
    matching on those compiles against nothing and is a mistake this ambiguity keeps inviting.
    Use kotlin-result **only** where the answer really is
    "the value, or the throwable that stopped me" — that is the `MediaSource` seam
    (`fetchAudiobooks`/`fetchTracks` and `TrackRepository`'s loader), where a caller either gets
    data or falls back to cache. Everywhere else, write a sealed interface whose members are named
    for what happened, because `Err` flattens exactly the distinction that matters:
    `CacheScanOutcome.Unavailable` ("cannot tell") is deliberately *not* an error, and collapsing it
    to a failure is what silently un-cached whole libraries; `ImportResult.WrongVersion` carries the
    file's version, and `Applied` carries three counts. On a value class: kotlin-result 2.x made
    `Result` a value class, so `Ok`/`Err` are factory functions, not types — branch on `.isOk` /
    `.isErr`, never `x is Ok`. `ResultSemanticsTest` pins that.

Rules 3–6 and several others are **enforced by build gates** — see
[`09-enforced-rules.md`](09-enforced-rules.md).

---

## Testing

Every change to repositories/ViewModels/sync/download logic must add or extend tests (D6/D10).
Fixture-backed where network is involved (the fixture pattern).

### Mock a collaborator you only call; fake a collaborator that calls *you* back

MockK is right for a final class with a large surface (`MediaServiceConnection`, `PlexConfig`) and
for asserting something did *not* happen (`coVerify(exactly = 0)`).

It is **wrong for anything callback-, listener- or flow-shaped**, because a `relaxed` mock's
silence is indistinguishable from correct behaviour: `mockk<SharedPreferences>(relaxed = true)`
returns `false`/`null` from the getters and drops the listener registration, so a `preferenceFlow`
built on it never emits, the `combine` downstream never fires, and every assertion about the result
passes against a flow that produced nothing. `CollectionsViewModelTest` uses a hand-written
`FakePrefs` with a real listener list for exactly that reason.

The data layer goes further and tests against **real in-memory Room databases** (nine suites), and
`RoomSchemaTest` against a real *file*.

### Testing StateFlow needs a subscriber *and* a drained dispatcher

A `WhileSubscribed` flow computes only while collected, and `MainDispatcherRule` installs a
`StandardTestDispatcher` that queues rather than runs — so `.value` read without both is the
`stateIn` seed, which looks exactly like broken arithmetic.

`util/FlowTestExt.kt` has `keepCollected`, `settledValue` and `settledValues`. Use `settledValues`
when one assertion compares two flows: subscribing to them one at a time makes whichever is second
read its seed.

### Sabotage-verify every guard

A check that cannot fail proves nothing. Gradle's up-to-date checks make a sabotaged test look like
it passed — use `--rerun-tasks`, and restore in a separate call.

### A test may not disable a check that production performs

If a fixture cannot satisfy a production constraint, **fix the fixture** — do not switch off the
constraint.

The worked example is worth the space, because the cost was a 100% launch crash reaching a device
with 1,678 tests green. `PLACEHOLDER_URL` lost its trailing slash; `Ktorfit.Builder.baseUrl`
validates for one and throws. The clients are `@Singleton`, so Hilt built them inside
`Application.onCreate` — no window was ever created and the user bounced to the launcher with no
crash dialog.

Four tests built Ktorfit instances and **every one passed `checkUrl = false`**, to accommodate a
`FakePlexServer.url` that trims its trailing slash. The suite had switched off precisely the
validation that fires in production. The missing slash was one character; the disabled check is the
actual defect.

Watch for the same shape in `expectSuccess`, `validateEagerly`-style flags, `allowMainThreadQueries()`,
a `@Config` lowering the SDK below `minSdk`, and `relaxed = true` mocks standing in for the
collaborator whose contract is under test.

### Commit characterisation tests before optimising

Untracked tests get lost. Commit them green against the old code first.

---

## Definition of done

1. **Verify loop green** — `./verify.sh`. That script *is* the definition of "the build is fine"
   (D12 rule 6), not CI.
2. **Tests added/extended** for touched repositories, ViewModels, sync/download/chapter logic (D6).
3. **`./verify.sh --instrumented` run** before moving a task to `In Review` or `Done`. Opt-in
   locally, because two emulator boots would wreck the inner loop — but a green unit suite is not
   evidence the app starts. A 100% launch crash once shipped with 1,678 tests green (the base-url
   story under *Testing*), and the test that would have caught it existed and was simply never run.
   CI runs it on every PR; running it before review is what stops you learning this from a red tick.
4. **Self-review pass done** (principle 2) — diff re-read for correctness, silent failures, dead
   code, simpler alternatives; error paths log with context and never swallow.
5. **Docs synced in the same PR** — the relevant `reference/` file if architecture or behaviour
   changed; the task file's status and criteria; `CLAUDE.md` if any statement there became false.
6. **Attribution trailer** if code was ported (principle 4).
7. **Commit messages** per [Scoped Commits](https://scopedcommits.com/) — see the
   `backlog-workflow` skill.

**The correct closing status is `In Review`, not `Done`, whenever the work changed a screen or made
a product choice.** `Done` is for work a machine proved right. See the `backlog-workflow` skill for
the full rule.

---

## Never touch without explicit owner sign-off

- Signing configs, keystores, release credentials
- Billing/IAP code (`ChronicleBillingManager`, premium SKU plumbing — dormant by decision D4/D9)
- Licence headers, `LICENSE`
- Branding assets (icon, wordmark — owner's ARR work; upstream/Epilogue branding never enters the
  repo)
- Play Store metadata/listing anything
- **Product decisions** in `backlog/decisions/` (D1–D14). Agents work in `backlog/tasks/`; new
  *ideas* go to `backlog/drafts/` for owner triage; agents may add *technical* ADRs to
  `backlog/decisions/`.

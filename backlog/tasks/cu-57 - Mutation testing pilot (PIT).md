---
id: cu-57
title: Mutation testing pilot (PIT) after cu-44
status: In Review
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-44]
priority: medium
milestone: m-1
---

## Description

Evaluate test *quality* (do tests actually fail when behaviour breaks?) rather than test *coverage*
(was the line executed?). The cu-3 ratchet cannot see the difference — cu-56 is a live example of code
that contributes coverage while verifying nothing.

Owner decision 2026-08-30: **deferred until after cu-44.** Researched and scoped now so the groundwork
isn't repeated.

### Why defer rather than do it now

Not because PIT would be too noisy — `targetClasses` scopes it to whichever classes you choose, so an
allowlist of tested classes is trivial. The real reason is that mutation testing measures the quality
of tests that *exist*, and at 3.76% coverage across 131 source files there is almost nothing to
measure. The dominant problem is absent tests, and PIT does not help write them. After cu-44 backfills
the R1 risk surface there is a suite worth auditing.

### Decisions already made (don't re-litigate)

- **Plugin: `pl.droidsonroids.pitest` 0.2.27** (Apache-2.0), *not* mainline `info.solidsoft.pitest`.
  Solidsoft explicitly refuses Android — it is built on `JavaPlugin` and needs `sourceSets.main`/`test`,
  which `com.android.application` does not provide. The fork is actively maintained (0.2.21 fixed
  AGP 8.8+; 0.2.26 handled Gradle 9) and generates a `pitestDebug` task with the classpath already
  wired, including AGP's mockable android.jar.
- **Arcmutate is rejected.** It is the only tool with working Robolectric support and proper Kotlin
  handling, but it is proprietary and licence-gated — excluded by D12 rule 7 (no proprietary SDKs),
  independent of cost. Do not revisit unless that rule changes.
- **Robolectric tests must be excluded from scope.** PIT + Robolectric is broken and unfixed:
  koral--/gradle-pitest-plugin#80 (open since 2022), #58 (2020), upstream pitest#1065 (closed
  2026-08-17 by pointing at Arcmutate, not by a fix). It fails *silently*, reporting false
  `SURVIVED`/`NO_COVERAGE`. Running PIT over `RoomMigrationTest` would report our verified-good
  migration tests as bad. This is the single most important constraint here.
- **Kotlin noise**: use `mutators = DEFAULTS` (not `STRONGER`/`ALL`) and
  `avoidCallsTo = kotlin.jvm.internal` to suppress `Intrinsics.checkNotNull*` mutants. The free
  `pitest/pitest-kotlin` filter was archived 2023-03-01. `pitest-descartes` (Apache-2.0, maintained) is
  a legitimate fallback if Gregor's Kotlin noise proves unbearable.

### Starting config

```kotlin
pitest {
  pitestVersion.set("1.22.1")
  mutators.set(listOf("DEFAULTS"))
  avoidCallsTo.set(listOf("kotlin.jvm.internal"))
  targetClasses.set(listOf(/* allowlist: only classes with real tests */))
  threads.set(4)
  timestampedReports.set(false)
}
```

Start with `features.player.TrackListStateManager` (pure JVM, no Android types, 0.012s suite) plus
whatever cu-44 adds that is *not* Robolectric-based. Never mutate generated code — an allowlist
`targetClasses` avoids needing exclusions for `*_Impl`/`Dagger*`/`*Binding`/`*_Factory`.

### Scope boundary

Keep PIT **local/manual — out of `verify.sh` and out of CI.** No mutation-score threshold until there
is a suite worth gating on; a threshold added early just blocks unrelated PRs. Revisit gating as a
separate decision. Note `withHistory` needs a preserved workspace between builds, which GitHub Actions
does not provide by default.

### Context

This task exists because the owner noticed agents hand-sabotaging code (breaking a migration, breaking
a unit test) to prove tests could fail — see cu-3 and cu-1 implementation notes. That is manual
mutation testing; it works but only covers the handful of spots someone thinks to poke. Automating it
is the right instinct, just better spent once there is a suite to point it at.

## Acceptance Criteria

- [x] PIT runs via `./gradlew pitestDebug` on an allowlist of genuinely-tested, non-Robolectric classes
- [x] Mutation score reviewed; surviving mutants either killed with new assertions or justified
- [x] Robolectric-based tests explicitly excluded, with the reason recorded in the config
- [x] Remains out of `verify.sh`/CI unless a separate decision adds a gate

## Implementation Notes

Ran on 14 target classes covering the position, completion, chapter, download and auth logic.
**Baseline: 217 mutations, 87 killed (40%), test strength 56%.** After acting on the worst finding:
**94 killed (43%), test strength 60%.**

### The finding that justified the whole exercise

| Class | Line coverage | Mutation score |
|---|---|---|
| `ProgressReporter` | **96%** (45/47) | **7%** (3/45) |

Near-perfect line coverage, and the tests noticed almost nothing. They execute
`markFinishedIfNeeded` and assert only the returned `Outcome`, so the arithmetic deciding *"is this
finished?"* was unverified. PIT named the survivors precisely:

- `changed conditional boundary` — the `>` in `trackProgress > duration - TRACK_FINISHED_WINDOW`
- `Replaced long subtraction with addition` — `bookDuration - bookProgress` becoming `+`
- `removed conditional - replaced comparison check with false` — the `hasUserEndedPlayback` gate

That last one matters most: marking a book watched **resets its position**, so a broken gate throws
away the listener's place mid-listen. Same family as cu-9's original bug. This is exactly the code
the coverage ratchet said was covered.

`MarkFinishedBoundaryTest` adds 10 tests at those boundaries. Re-running PIT confirms all three
mutants are now **KILLED** — verified individually in the report, not inferred from the score moving.

### A trap the new tests walked into

My first fixtures used `bookDuration = 60_000` with `bookProgress = 0`, and three tests failed —
because `60000 - 0 < 120000` is **true**: a 60-second book at the very start is already inside the
two-minute "finished" window. The book window is longer than a naive fixture. Recorded in the test's
KDoc, since anyone writing the next fixture will reach for the same round number.

### Getting it to run: two real obstacles

1. **`targetTests` of `io.github.mattpvaughn.chronicle.*` matched 969 classes** — everything on the
   test *classpath*, not the test sources — and the coverage minion died with `UNKNOWN_ERROR`.
   Narrowed to `...*Test`, giving 45.
2. **`junit5PluginVersion` killed the minion.** I set it from the draft's config, but this project is
   on **JUnit 4.13.2**; the minion died with `NoClassDefFoundError: PreconditionViolationException`,
   surfaced only as `UNKNOWN_ERROR` until `verbose.set(true)`. Removed, with a comment so it is not
   re-added.

Both failures reported the same useless message. **`verbose.set(true)` is the first thing to try** if
PIT fails again.

### What the remaining survivors are

Most of the rest are noise rather than gaps: `removed call to Timber$Forest::w/e/i` (logging, which
no test should assert on) and `removed call to kotlin/ResultKt::throwOnFailure` (compiler-generated
`runCatching` plumbing). Adding `avoidCallsTo` entries for `timber.log` would raise the score without
improving a single test — deliberately not done, because the number is not the point.

`AccountAuthState` at 2/6 is a fair reading: it is a two-line state holder whose transitions are
tested, and its surviving mutants are all Timber calls.

### Kept out of the gate, deliberately

Not in `verify.sh`, not in CI, no threshold — as the draft specified. It takes ~40s on 14 classes and
answers a different question from the ratchet. A score threshold would block unrelated work while
rewarding assertions on log calls.

### Honest limit

Mutation testing would **not** have caught the two worst bugs of this session: the cu-87 launch crash
and the migration-vs-entity mismatch. Both were in code no test executed at all, and PIT only mutates
what tests already reach. It is a check on assertion quality, not a substitute for pointing tests at
the right code.

## Second pass — acting on the report (2026-08-31)

Baseline **94/217 (43%)** → **102/217 (47%)**, 339 → 353 tests, coverage 15.41% → 15.43%. Every new
test was verified by **deliberately sabotaging the source and watching it fail** — a test written
against a mutation report that is never seen to fail proves nothing.

Machine-readable output added (`outputFormats = ["HTML", "XML"]`) because scraping the HTML for
survivors is fragile; `mutations.xml` can be parsed directly.

### Real defects the report found

| Where | Mutant | Why it matters |
|---|---|---|
| `ProgressReporter:68` | `duration * 2` → `/ 2` | The doubling stops Plex auto-finishing at 90%. Flipping it **marks books complete early** — a symptom the owner reported. Nothing asserted it: the fake counted calls but discarded arguments. |
| `ProgressReporter:82` | `code >= 500` boundary | Retry-vs-give-up. Tests used 503 and 401, both far from the edge. |
| `Audiobook:220` | `>=` boundary, `-` → `+` | The 2-minute completion window. Nearest test sat 60s inside it. |
| `Audiobook:109` | comparison inverted | `merge`'s freshness rule. Every existing test asserted `.progress`, which is *identical in both branches* by design — so the branch selector was unobserved. |
| `CacheScanOutcome:43/46/50` | all three guards | Tests asserted `is Unavailable` but never *which* guard fired, so any one could be deleted and the next produced an indistinguishable result. |

`CacheScanOutcome:50` is the cu-85 bug itself (`listFiles()` null → un-cached library) and now has a
test that reproduces it, guarded by `assumeTrue` so it skips rather than lies where chmod has no
effect. Verified it genuinely runs (`skipped="0"`), not silently skips.

### Two equivalent mutants — do not chase these

- **`Audiobook:109` boundary (`>` → `>=`).** Both branches return `network.copy(...)` and the stale
  branch overrides only `lastViewedAt`; at equal timestamps the branches produce an identical book.
  Unkillable by construction. Documented in `BookProgressDerivationTest`. Inverting the comparison
  *is* killed, so the rule is pinned.
- **`AccountAuthState` L40/48.** The `if` guards wrap only a Timber call; the state assignment is
  outside them. Confirmed by replacing both with `false` — all tests still pass.

### A false 0% worth knowing about

`ChapterAssembly.kt` reports **0/3, NO_COVERAGE** despite `AssembleChaptersTest` passing. It is an
`inline fun`: the body is copied into each caller, so the original carries no executable bytecode for
PIT to attribute coverage to. Sabotaging the accumulator fails 2 tests immediately — the tests are
real. **Any `inline` function will read as 0% here.** Do not "fix" it by adding tests.

### Still open, and honestly assessed

`MediaItemTrack` 48% and `DownloadGroupId` 40% carry the most remaining substance;
`ProgressReporter` sits at 27% because ~14 of its mutants are suspend-machinery nulls and
`runCatching` plumbing that no assertion should target.

## Third pass — targeting by value, not by score (2026-09-01)

Overall coverage **15.43% → 20.51%**, 339 → 409 tests, mutation 47% → 34% *on a deliberately wider
target set* (the denominator nearly doubled, from 217 to 414 mutants, by adding the repositories
and a ViewModel).

The owner's read was right: the second pass raised coverage by 0.02 points because `data/model` was
already the best-covered package in the app at 76%. The value was in code with **zero** tests.

### Two bugs found by writing the first test for a method

- **`BookRepository.syncAudiobook` reported every failure as success.** Its three failure paths use
  `return@withContext false`, which returns from the *lambda*; the result was discarded and the
  function fell through to `return true`. The book-details "sync now" button therefore showed *"sync
  successful"* on every network failure. Fixed.
- **`updateProgressIfChangingBook` tests the opposite of its name** — drafted as cu-91, not fixed
  here because it changes playback behaviour.
- **`onCacheButtonClick` throws on a null cache status** — drafted as cu-92.

### What PIT cannot see here

**Suspend machinery inflates the survivor count on repositories.** All six remaining mutants on
`markTracksInBookAsWatched`/`AsUnwatched` are `removed conditional` on the coroutine state check and
`replaced return value with null` on the `Continuation` — compiler artifacts, not behaviour. The
tests on those methods provably catch real defects (verified by sabotage: dropping the
`lastViewedAt = 0L` clear fails immediately), yet the score barely moves. **Do not read
`BookRepository` 9% or `TrackRepository` 17% as "untested"** — read the survivor *descriptions*.

**`inline` functions always read 0%** — see the second pass; `ChapterAssembly` is the standing
example.

### The real blocker for ViewModels was never their design

Not `MediaServiceConnection` or `PlexConfig` being final — MockK handles final classes. It was
`Dispatchers.Main`, which `asLiveData()` touches during *construction*, so a ViewModel could not be
instantiated at all on the JVM. `MainDispatcherRule` (new, in `app/src/test/.../util/`) pays that
once for all twelve. Two further framework statics need stubbing per-test where they are hit:
`DateUtils.formatElapsedTime` and `SystemClock.elapsedRealtime` (reached via
`PlaybackStateCompat.Builder.setState`).

A construction test is worth writing on its own: every LiveData in a ViewModel body is evaluated
eagerly, so cu-87's declaration-order crash fails it with the exact production error. Note the
alias itself is lazy — reproducing that bug needs an *eagerly-initialised* property reading it
before its target is declared, which is not what I first tried.

### Coverage by the classes that moved

| Class | Before | After |
|---|---|---|
| `MainActivityViewModel` | 0% | 57% |
| `TrackRepository` | 15% | 47% |
| `AudiobookDetailsViewModel` | 0% | 40% |
| `BookRepository` | 10% | 39% |
| `CurrentlyPlayingViewModel` | 0% | 31% |
| `CacheReconciliation` (extracted) | — | 100% (6/6 mutants) |

### Deliberately not covered

`MediaPlayerService` (1,756 instructions), `AudiobookMediaSessionCallback` and `NotificationBuilder`
are genuinely device-bound. `PackageValidator` (702) and `MediaMetadataCompatExt` (793) are Google
Apache-2.0 sample code over `PackageManager`/`XmlResourceParser`/`MediaMetadataCompat` — testing
them would mostly re-test the framework. The populated branches of the mini-player's chapter readout
need real media metadata; better done with cu-89.


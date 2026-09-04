---
id: cu-33
title: Complete backend interface carve
status: In Review
assignee: ['@claude']
created_date: '2026-07-13'
labels:
  - R2
  - architecture
  - debt
milestone: m-2
dependencies:
  - cu-15
priority: high
ordinal: 53000
---

## Description

Route the direct `data.sources.plex.*` imports through the interface; login-flow redesign for
URL+token backends; per-source capability flags (`hasNarrator`, `hasSeries`, `hasServerProgress`) so
facets/'synced'/series shelves degrade gracefully (D11).

## Promoted to R2 (owner decision, 2026-09-01)

Moved from R4 to R2 on the evidence of the pre-R2 review. This was scheduled as *preparation for new
backends*, which is an R4 concern — but it is the single largest thing holding the test suite down
today, which is an R2 concern.

**`Injector.get()` appears 73 times across 30 files.** A class that fetches its own dependencies at
runtime cannot be constructed in a unit test with fakes, and the correlation is exact: nine of the
twelve ViewModels have no tests, and they are the nine that call the service locator.
`SettingsViewModel` has 13 calls and zero tests; `features/settings`, `features/login` and
`features/collections` sit at **0%**, together 9,624 missed instructions — a sixth of the codebase.

The worst single instance is not in a ViewModel: `AudiobookMediaSessionCallback` takes **16 injected
dependencies** and still makes a live HTTP call through `Injector.get().plexMediaService()`
(`:462`), and reads three token fields to write HTTP auth headers (`:419-429`). Credential plumbing
inside a MediaSession command callback.

The R4 backend work (cu-33.1–33.3) stays at R4. This task is the carve only.

Note the import count in CLAUDE.md said 27 and is now **28** — corrected in `fdc28ce`.

## Implementation Notes

### What landed

**66 `Injector.get()` call sites across 29 files, down to 9** — all in the two categories that
genuinely cannot take constructor injection. Four commits:

1. `features: construct ViewModels with their dependencies, not the locator` — all 14 ViewModels.
2. `data/sources/plex: own the session handshake and the auth token in one place` — `PlaybackSession`,
   plus `PlexConfig`, `CachedFileManager`, `TrackRepository`, `SharedPreferencesPrefsRepo`.
3. `treewide: take the service locator out of everything that can do without it` — the awkward
   three (custom View, binding adapter, extension function) and the guard test.
4. `debug: make the settings screen reachable from a script` — the on-device verification hook and
   the factory-consistency test.

The mechanism, since the task file described the symptom rather than the cause:
`ChronicleApplication.get()` is `INSTANCE!!`, so a class fetching its own dependencies at runtime
**cannot be constructed in a unit test at all** — the first line reaching the locator throws NPE.
Every ViewModel already had an `@Inject`-constructed `Factory`; they simply reached *past* it. The
correlation the task noted was exact for that reason.

Note cu-15's `MainDispatcherRule` had already fixed the *other* cause and says so in its KDoc
("that, rather than anything about their design, is why none of the twelve ViewModels had a test").
This was the second one.

### Two real bugs found

**An empty server token beat a good account token.** The precedence chain
`server?.accessToken ?: user?.authToken ?: accountAuthToken` was written out **twice** — in
`AudiobookMediaSessionCallback` and in `ServiceModule.plexDataSourceFactory` — with nothing checking
they agreed. Writing the first test against the consolidated version found both were wrong the same
way: `ServerModel.accessToken` defaults to `""` and `asServerModel` writes `accessToken ?: ""`, so a
server reporting no token of its own — **the ordinary case for a server the user owns** — stored an
empty string, won the elvis, and authorized every media request with an empty `X-Plex-Token` while a
perfectly good account token sat unused. Empty counts as absent now. Sabotage-verified.

**`toServerString` doubles the slash** in exactly the case its KDoc claims to handle (base ends with
`/`, path starts with `/`). Filed as **cu-160**, not fixed here — changing how every Plex URL in the
app is built does not belong in a DI refactor. `PlexConfigUrlTest` characterises it, so the fix will
fail against a test that says what it is doing.

### The 9 remaining calls are exempt, and the exemption is enforced

- **Three `CoroutineWorker`s** — WorkManager builds them reflectively with a fixed
  `(Context, WorkerParameters)` signature, the cu-152 reasoning that already exempted them from
  `DispatcherProvider`.
- **`ChronicleApplication`** — it *is* the DI root, and its one use is deliberately a lazy lambda to
  avoid closing a construction cycle.

`ServiceLocatorUsageTest` checks four things: no `Injector.get()` outside the set; every exemption
still exists and still needs to be there (so the list cannot rot into a wishlist); every exempted
worker really *is* a `CoroutineWorker`, since that is the whole justification; and that the source
root resolves. Offender and stale-exemption checks both sabotage-verified.

### Scope: criteria 1 and 4 deferred to cu-80, with reasons

Criterion 4 — "UI renders correctly against a source lacking narrator/series/server progress" —
**cannot be honestly verified today**. The capability flags exist (cu-15), but the fetch methods on
*both* `LocalMediaSource` and `PlexMediaSource` are still `TODO("Not yet implemented")`. There is no
second source to render against, so any check would be a mock asserting against itself. Ticking it
on the strength of a test that cannot see what it asks about is exactly what the status rule forbids.

cu-80 already owns this: it says *"[[cu-33]] is the task that makes the seam real; this one gives it
repository-side counterparts to talk to"*, and carries "the D11 capability flags are honoured by the
ingestion path rather than assumed true" as its own criterion.

Criterion 1 (27 files importing `data.sources.plex.*`) is dominated by `PlexConfig` (17) — a
connection-state holder, not a fetch API. Routing it through an interface before a second
implementation exists would be a rename, not a carve, and would collide with **cu-52**, which
rewrites the same observable surface. Both criteria are left unticked rather than reworded.

### Tests added

- `LoginViewModelTest` (5) — the proof the carve enables one. `features/login` was at 0% because
  every launch in it read the locator.
- `PlaybackSessionTest` (7) — the token precedence and the session handshake.
- `PlexConfigUrlTest` (5) — `toServerString`, which found cu-160.
- `CachedFileManagerUncacheTest` (5) — `uncacheAllInLibrary`, which deletes real files off the
  user's storage. **A trap worth recording**: a track with no `media` yields the filename `"101."`,
  which matches no cached-file pattern, so every assertion passed while nothing was deleted. Give
  test tracks a real `media` path or the test is vacuous.
- `ServiceLocatorUsageTest` (4) — the guard.
- `ViewModelFactoryTest` (2) — a ViewModel and its Factory declare the same list twice and only the
  call between them is compiler-checked, so a Factory can drift to a default and still compile.

Coverage: aggregate 37.75% → 37.96%; `data/sources/plex` 51.74% → 52.70%. The `application`
baseline was **lowered deliberately** 24.78% → 24.24%: the two added lines are `DebugHooks` calls in
`MainActivity`, which no unit test can reach.

### On-device verification

`SettingsList` is the one behavioural change — it attaches its adapter on the first
`setPreferences` rather than at construction, because a `View` inflated by the framework has no
constructor to inject into. Verified on the tablet against the real ANTARES server: 34 rows across
all sections with live values (`Square`, `1 hours`, `68.54 GB`), and a switch toggled and persisted,
so the adapter's `prefsRepo` — passed in now rather than fetched — is writing. Library covers render,
so `bindImageRounded`'s new `CoverUrlBuilder` resolves. Needed a new `show_settings` debug hook: the
screen is reachable *only* from its bottom-nav tab, which `input tap` cannot drive (cu-54).

### Follow-ups

- **cu-160** — `toServerString` doubles the slash (filed).
- **cu-80** — carries criteria 1 and 4; should be next for this seam.
- **cu-52** (StateFlow) should land *after* cu-80, not before: it rewrites ViewModel construction
  again and would conflict with any further carving.

## Acceptance Criteria

- [ ] No direct plex imports in `features/` (28 files today) — **deferred to cu-80**, see notes
- [x] `Injector.get()` gone from ViewModels and repositories; constructor injection throughout
- [x] `AudiobookMediaSessionCallback` no longer reaches the network through the service locator, and
      does not assemble auth headers itself
- [ ] UI renders correctly against a source lacking narrator/series/server progress — **not
      verifiable today**: both sources' fetch methods are still `TODO`, so there is no second source
      to render against. Owned by cu-80, see notes
- [x] At least one previously untestable ViewModel gains tests as proof the seam works — the point
      of doing this now is testability, so a carve that does not enable a test has not landed
      (`LoginViewModelTest`, 5 cases; `features/login` was at 0%)
- [x] A guard test keeps `Injector.get()` out of the carved packages, in the style of
      `InternalApiUsageTest` (`ServiceLocatorUsageTest`, sabotage-verified)

## What needs your eye

Two things, both judgement rather than correctness:

1. **The empty-token fix changes authentication behaviour.** A server that reports no access token
   of its own now falls through to the account token instead of sending an empty one. This is
   almost certainly right — an empty `X-Plex-Token` cannot have been intended — but it is a live
   auth path on the household server, and worth knowing about rather than discovering.

2. **`SettingsList` attaches its adapter later than it used to.** Verified on the tablet and the
   screen renders identically, but it is the one behavioural change in an otherwise mechanical
   carve.

Criteria 1 and 4 are left **unticked** rather than reworded — the work is real, it belongs to cu-80,
and the reasoning is in the notes. If you would rather they be retired from this task outright,
that is a scope call rather than something to decide unilaterally.

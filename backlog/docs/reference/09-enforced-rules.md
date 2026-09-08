# Enforced rules — the build gates

Rules in this project are enforced by **tests that scan the source tree**, not by prose. Each one
fails `./verify.sh` on a forbidden pattern, so the rule cannot rot: violating it breaks the build.

**This table is generated — do not hand-edit it.** Regenerate with:

```bash
./list-build-gates.sh
```

The rule text is each guard's first KDoc line, so the table cannot drift from what is actually
enforced. `./list-build-gates.sh --check` fails when a gate has no KDoc summary.

**The reasoning for each rule lives in the test's own KDoc**, next to the enforcement — that is the
canonical explanation, and it is usually far richer than a table row. Read the test before working
around a gate.

## The gates

| Guard | Rule |
|---|---|
| `ViewModelFactoryTest` | **(no KDoc summary — add one)** |
| `CollectionLoggingTest` | A `Timber` call must not interpolate a whole collection. |
| `ScopedQueryTest` | Every DAO query that returns rows without naming a single row must be scoped by source |
| `ViewStyleTest` | An unrecognised view style must degrade, never throw. |
| `ModelsWithoutDiTest` | **(no KDoc summary — add one)** |
| `TokenLoggingTest` | Auth tokens must never reach logcat. |
| `DeclaredDependencyTest` | Every AndroidX package the app imports is **declared**, not inherited. |
| `DetektRuleSetTest` | detekt owns complexity, potential bugs and coroutines — never formatting, style or naming. |
| `RawDurationFormatTest` | No progress readout may print a raw duration — the player's, and the book-details screen's. |
| `WorkerDispatcherTest` | `CoroutineWorker` is the one place `Dispatchers.*` is allowed directly. |
| ~~`OrphanedAdapterTest`~~ | *Retired* — zero `RecyclerView.Adapter`s remain. |
| ~~`UnguardedMenuAccessTest`~~ | *Retired* — no Fragments, and no toolbar menus to dereference. |
| `InternalApiUsageTest` | `@InternalCoroutinesApi` is not a stability opt-in like `@ExperimentalCoroutinesApi`. |
| `PostValueUsageTest` | `postValue` is banned outright, and this is the **only** mechanism that can enforce it. |
| `ServiceLocatorUsageTest` | `Injector.get()` is a service locator, and the backend-interface carve took it out of everything that can |
| `CollectorCachesItsValueTest` | A `collectWhileStarted` that discards its emission does not leave a stale local behind. |
| `ContentDescriptionTest` | Every image is either labelled for a screen reader or explicitly marked decorative. |
| ~~`FirstFrameFlashTest`~~ | *Retired* — a composable renders its state or nothing, so there is no XML default to flash. |
| `TouchTargetSizeTest` | Every clickable control is at least [MIN_TOUCH_TARGET_DP] on both axes. |

## Non-structural gates

These enforce a rule too, but by exercising behaviour rather than scanning source:

| Guard | Rule |
|---|---|
| `RoomSchemaTest` | A migration must be verified against a real **file** opened through Room — an in-memory database is created fresh and never migrated. Also pins each exported schema's filename against the `version` inside it. |
| `RoomMigrationTest` | Every historical migration chain runs through real SQLite (via Robolectric). |
| `BackupRulesTest` | `data_extraction_rules.xml` and `backup_rules.xml` agree, and neither lets Auto Backup take `ChronicleAuth.xml`. Parses `path=`, not substrings. |
| `PlexFixtureContractTest` | Both fixture servers route `/library/metadata/*` by id — the routing exists twice and both copies had the same defect. |
| `BookmarkSurvivesSyncTest` | A bookmark outlives a catalogue row the server dropped. Moving bookmarks into `BookDatabase` breaks it, which is the point. |
| `PerBookSpeedTest` | Both arms of `Audiobook.merge` preserve local-only columns. Verified by sabotaging one arm. |
| `PauseFlushesProgressTest` | A pause flushes progress read from the **player**, not the lagging session. Seeds a deliberately stale session position. |
| `EmbeddedArtworkTest` | Embedded cover art is not decoded — runs a real MP3 with a real PNG cover through the production function, plus a vacuity check. |
| `FrameworkFreeCoreTest` | 87 named files carry no `android.*`/`androidx.*` import beyond Room annotations. The list is committed, not computed, so a newly-impure file fails instead of silently dropping out. |
| `RepositoryDispatcherTest` | Repositories, the player layer and the ViewModel/Fragment/`application/` layer all take an injected `DispatcherProvider`. Scans four source lists. |
| `SyncLocationMoveTest` | A sync-location move carries partial downloads, which are named exactly like finished ones. |
| `SearchReadCostTest` | Re-runs the search projection benchmark in one command. |
| `ChronicleThemeTest` | The Compose palette matches `colors.xml`. |
| `AutoBrowseTreeTest` | (instrumented) The Android Auto browse tree loads — the only way to reach `onGetRoot`/`onLoadChildren`. |
| `PitestScopeTest` | No Robolectric test may enter PIT's scope, and the exclusion stays derived rather than hand-maintained. PIT + Robolectric fails silently, so a stale list makes the mutation report lie. |

## Adding a gate

A rule worth writing in prose is usually worth enforcing. When you find a defect class that could
recur:

1. Write the guard as a test that scans the source tree and fails on the pattern.
2. **Put the reasoning in its KDoc** — first line is the rule, the rest is the story. That first
   line lands in the table above.
3. **Verify it by sabotage.** A check that cannot fail proves nothing. Note that Gradle's
   up-to-date checks will make a sabotaged test look like it passed — use `--rerun-tasks`, and
   restore in a separate call.
4. Delete the prose it replaces.

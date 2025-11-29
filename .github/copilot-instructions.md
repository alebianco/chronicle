# Copilot Repository Instructions — Chronicle Android App

Purpose: Help coding agents work efficiently and keep builds green. These instructions are general (not task-specific) and fit within 2 pages.

## Project Snapshot
- Android app in Kotlin, single module `:app` (minSdk 27, targetSdk 34).
- Architecture: MVVM + Repository + Room + Retrofit/OkHttp + Dagger 2 (KSP) + ExoPlayer + LiveData/Coroutines + Data Binding.
- Entry points: `application/ChronicleApplication.kt`, `application/MainActivity.kt`, features under `features/`.
- Dependency Injection: `injection/components/*`, `injection/modules/*`, scopes in `injection/scopes/*`.

## Golden Rules (high impact, low risk)
1. Do not create dependencies manually. Use constructor `@Inject` or provided factories; respect scopes (`@Singleton`, `@ActivityScope`, `@ServiceScope`).
2. UI logic stays in Fragments/XML; business logic in ViewModels/Repositories.
3. Use `LiveData` for UI state: private `MutableLiveData`, public immutable `LiveData`.
4. Use coroutines: IO work on `Dispatchers.IO`, UI updates on Main (`viewModelScope`).
5. Always place user-facing text in `res/values/strings.xml` (no hardcoded strings).
6. If changing Room schema (entities/DAO): increment DB version and add a migration in the corresponding `*Database.kt`.
7. Navigation goes through `navigation/Navigator.kt`; pass data via Bundles/args, not singletons.
8. Media playback is controlled via `MediaServiceConnection`/`MediaPlayerService`—never interact directly with ExoPlayer from UI.

## Build, Lint, Test (macOS zsh)
- Preferred commands to avoid CI failures:
```bash
./gradlew ktlintFormat
./gradlew ktlintCheck
./gradlew assembleDebug
./gradlew test
```
- If adding libraries that need keep rules, update `proguard-rules.pro`.
- KSP is used (Moshi, Room, Dagger). Ensure generated sources compile by building after edits.

## Safe Edit Checklist
- Entities/DAO:
  - Bump Room DB version and add migration.
  - Keep queries returning `LiveData` when observed by UI.
- New/modified ViewModel:
  - Provide inner `Factory` with `@Inject` and wire via Activity component/module when needed.
  - Expose immutable `LiveData`; use `viewModelScope`.
- New Fragment/UI:
  - Inject via `ActivityComponent` in `onCreate`.
  - Set `binding.lifecycleOwner = viewLifecycleOwner`; use data binding.
- Settings/Prefs:
  - Add to `PrefsRepo`; expose typed getters/setters; wire into `features/settings`.
- Network/API:
  - Define endpoints in `data/sources/plex/PlexService.kt`.
  - Handle errors in repositories; return safe results and log with Timber.
- Playback:
  - Use service-scoped DI; update notification/media session via existing builders.

## Conventions & Patterns
- Kotlin style (ktlint): idiomatic Kotlin, no wildcard imports.
- Repository pattern: single source of truth; abstract DB/network/cache; return `LiveData` or `suspend`.
- Room: DAOs for queries, `@TypeConverters` for complex fields, migrations for schema changes.
- Retrofit/OkHttp: define API in `PlexService`; configure headers via interceptors.
- RecyclerView: use ListAdapter + DiffUtil; avoid UI jank (`itemAnimator?.changeDuration = 0`).
- BindingAdapters: keep reusable UI bindings in `views/BindingAdapters.kt`.

## Error Handling & Logging
- Catch exceptions in repositories; surface user-friendly messages via ViewModels.
- Use Timber for logs (include context): `Timber.e(e, "Failed to do X")`.
- Use one-time Event wrappers for toasts/snackbars.

## Resources & Localization
- Strings/colors/dimens under `res/values/*`.
- Vector drawables preferred; layouts in `res/layout/` use ConstraintLayout and Data Binding.

## Performance & Threading
- No blocking on main thread.
- Large lists via RecyclerView with DiffUtil.
- Images via Fresco/Glide.

## Security & Privacy
- No secrets in source; use SharedPreferences for simple prefs.
- Network calls over HTTPS.

## Files to Know (fast navigation)
- `app/build.gradle.kts` — plugins, SDK versions, dependencies.
- `app/src/main/AndroidManifest.xml` — permissions, components.
- `application/ChronicleApplication.kt` — app init + DI root.
- `injection/components/*`, `injection/modules/*` — DI graph.
- `data/local/*` — Room DBs, DAOs, repositories.
- `data/sources/plex/*` — Plex API, login/config, cached file manager.
- `features/*` — Fragment + ViewModel + adapters per feature.
- `navigation/Navigator.kt` — centralized navigation.
- `views/*` — BindingAdapters/custom views.

## Commit Hygiene
- Run ktlint and build before commits.
- Keep diffs minimal; preserve formatting/imports.
- Update docs when public behavior or architecture changes.

## Path-Specific Note — `docs/`
- This folder contains developer documentation used for onboarding and reference.
- When changing public behavior, architecture, or adding major features, update relevant docs:
  - Index `docs/README.md`
  - Architecture `docs/02-architecture.md`
  - Key components `docs/04-key-components.md`
  - Data flow `docs/05-data-flow.md`
  - Adding features `docs/06-adding-features.md`
- Style: beginner-friendly, concise, explain “why” and “how,” include short code examples, cross-reference related docs.

## Do/Don’t Quick Reference
- Do: use DI, coroutines, LiveData, Room migrations, Navigator, ViewModel factories, strings.xml.
- Don’t: instantiate singletons manually, access DB from UI, hardcode strings, block main thread, control ExoPlayer from UI.

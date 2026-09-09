---
id: 02-architecture
title: Architecture
type: reference
created_date: '2026-09-01'
---

# Architecture

## Overview

Chronicle follows a layered architecture with clear separation of concerns. It uses several modern Android architecture patterns and libraries.

## Architecture Pattern: MVVM

The app uses **Model-View-ViewModel (MVVM)** architecture:

```mermaid
graph TD
    A[View<br/>Compose screens, single Activity<br/>UI Layer]
    B[ViewModel<br/>Business Logic, UI State<br/>Presentation Layer]
    C[Model<br/>Repository, Data Sources, Database<br/>Data Layer]
    
    A -->|collects StateFlow<br/>calls methods| B
    B -->|uses| C
    
    style A fill:#e1f5ff
    style B fill:#fff4e1
    style C fill:#e8f5e9
```

### View (UI Layer)
- **Compose screens**: every screen is a composable function of state — `features/<x>/compose/<X>Screen.kt` — paired with a `<X>Circuit.kt` holding its screen key, a sealed event hierarchy, a presenter that wraps the ViewModel and a `<X>Ui`. The Fragment-per-screen structure ([[decision-22]]) is gone entirely; there is no ViewBinding and no XML layout left
- **Activities**: single `MainActivity` calls `setContent {}` once and hosts a Navigation Compose `NavHost` — see the Navigation section below
- **One deliberate exception**: `features/player/compose/CastButton.kt` wraps the Cast SDK's `MediaRouteButton` in an `AndroidView`, because the SDK has no Compose surface (decision-19)
- **Responsibilities**: Display data, handle user input, navigation

### ViewModel (Presentation Layer)
- **Purpose**: Holds UI state and handles UI logic
- **Lifecycle**: Survives configuration changes (screen rotation)
- **Communication**: Exposes `StateFlow` to Views, calls Repository methods
- **Examples**: `HomeViewModel`, `LibraryViewModel`, `CurrentlyPlayingViewModel`

### Model (Data Layer)
- **Repositories**: Single source of truth for data (`BookRepository`, `TrackRepository`)
- **Data Sources**: Where data comes from (Plex API, Local Database, File System)
- **Database**: Room database for local caching
- **Responsibilities**: Fetch, cache, and manage data

## Key Architectural Components

### 1. Dependency Injection (Dagger 2 via Hilt)

Dagger 2 still handles all object creation, but it is now Hilt's generated components,
not hand-rolled ones: `@HiltAndroidApp` on `ChronicleApplication`, `@AndroidEntryPoint` on
`MainActivity` and `MediaPlayerService`, `recordViewModel()` in every `<X>Circuit.kt`.
`injection/modules/*.kt` are `@Module @InstallIn(SingletonComponent::class | ActivityComponent::class
| ServiceComponent::class)` — those three component types are Hilt's own
(`dagger.hilt.android.components.*`), not classes this codebase declares:

```mermaid
graph TD
    A[SingletonComponent<br/>Application Scope]
    B[Singletons<br/>Repositories, Services, Databases]
    C[ActivityComponent<br/>Activity Scope]
    D[Activity-specific dependencies]
    E[ServiceComponent<br/>Service Scope]
    F[Media player service dependencies]
    
    A --> B
    A -->|Creates| C
    C --> D
    C -->|Creates| E
    E --> F
    
    style A fill:#e3f2fd
    style C fill:#f3e5f5
    style E fill:#e8f5e9
```

### 2. Repository Pattern

Repositories abstract data sources from the rest of the app:

```mermaid
graph LR
    A[ViewModel] --> B[Repository]
    B --> C[Data Sources]
    C --> D[API]
    C --> E[Database]
    C --> F[Cache]
    
    style A fill:#fff4e1
    style B fill:#e8f5e9
    style C fill:#e1f5ff
```

**Example**: `BookRepository`
- Fetches books from Plex API
- Caches in Room database
- Returns `Flow`/`StateFlow` to ViewModels
- Handles offline mode

### 3. Reactive Programming (StateFlow + Coroutines)

**StateFlow**: Observable state holder (there is **no `LiveData` left in this codebase**)
- Always has a current value, replayed to every new collector
- **Not** lifecycle-aware by itself: collect via `collectWhileStarted` /
  `collectEventsWhileStarted` (`util/FlowCollect.kt`), which wrap `repeatOnLifecycle(STARTED)`.
  Never a bare `lifecycleScope.launch`, never the deprecated `launchWhenStarted`
- **Conflates equal consecutive values**, where a `LiveData.map` used to re-emit. A one-shot signal
  therefore needs `Event<T>`, not a plain value
- `stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), initial)` is the default;
  **`Eagerly`** is required when a click handler reads `.value` without anything collecting it,
  or the read returns the seed
- `postValue` is banned outright and `PostValueUsageTest` fails the build on it

**Coroutines**: For asynchronous operations
- Network calls
- Database operations
- File I/O
- Background processing

### 4. Media Architecture (ExoPlayer + MediaSession)

```mermaid
graph TD
    A[MediaPlayerService<br/>Background Service]
    B[ExoPlayer<br/>Actual audio playback]
    C[MediaSession<br/>Android media controls]
    D[AudiobookMediaSessionCallback<br/>Handles transport controls and playFromMediaId]
    E[NotificationBuilder<br/>Now Playing notification]
    
    A --> B
    A --> C
    A --> D
    A --> E
    
    style A fill:#e3f2fd
    style B fill:#ffebee
    style C fill:#f3e5f5
```

**MediaServiceConnection**: `MainActivity` (and, through it, screens/ViewModels) binds to the service
- Sends playback commands
- Receives playback state updates
- Survives across the entire app lifecycle

## Data Sources

### 1. Plex API (Remote)
- **PlexService**: Retrofit interface for API calls
- **PlexMediaRepository**: Manages Plex data
- **PlexLoginRepo**: Handles authentication

### 2. Room Database (Local) — **five separate databases**

Each has its own version and migration list, so a schema change means finding the right one. None
use `fallbackToDestructiveMigration`, deliberately: a bad migration must crash, never silently wipe
listening progress.

- **BookDatabase** (v14): audiobook metadata
- **TrackDatabase** (v7): tracks. `viewOffset` here is the source of truth for position
  ([[decision-16]])
- **ChapterDatabase** (v3): chapters — and **nowhere else** since the migration dropped
  `Audiobook.chapters`
- **CollectionsDatabase** (v3): Plex collections
- **BookmarkDatabase** (v1): bookmarks, kept **outside `BookDatabase` on purpose** so the sync path
  cannot delete a note the user wrote

### 3. File System (Local Cache)
- **CachedFileManager**: Manages downloaded audio files
- **Fetch2**: download library, **vendored** at `libs/fetch2-mirror` (upstream is
  abandoned and was arriving via JitPack)

## Navigation

Circuit owns routing ([[decision-27]]), replacing Navigation Compose — which had itself replaced
`Navigator`'s `FragmentManager` transactions. Navigation has now been migrated three times; that
cost is recorded in the decision rather than rediscovered. Four pieces:

- **`navigation/Screens.kt`**: a framework-free sealed interface — one `data object`/`data class`
  per screen. **An argument is a field on the key**, not a string in a route, so there is nothing
  to percent-encode: `encodeArg`/`decodeArg` and the whole class of "a raw `/` navigates nowhere,
  silently" defect are gone rather than guarded. The keys are `@Serializable`, which is what keeps
  this file free of `Parcelable` and still able to survive process death.
  `screenForLogin(state)` is the pure routing decision `Navigator`'s init block used to make from
  `IPlexLoginRepo.loginEvent`.
- **`navigation/circuit/ChronicleCircuit.kt`**: the graph — `presenterFor` and `uiFor`, two `when`
  tables over the screen keys. One file rather than a factory per feature: this is a single module,
  and thirteen factories would be thirteen files to check when a screen renders "unavailable
  content".
- **`navigation/circuit/ScreenKeySaver.kt`** and **`RecordScopedViewModels.kt`**: the two seams the
  migration needed. The saver writes the back stack as JSON, because Compose's
  `SaveableStateRegistry` rejects a key it cannot bundle. `recordViewModel()` keeps Circuit's
  record-scoped `ViewModelStore` but borrows the Activity's Hilt factory, because the record owner
  is not `HasDefaultViewModelProviderFactory` and `hiltViewModel()` would silently fall through to
  the default factory and throw on launch.
- **`application/compose/ChronicleApp.kt`**: the app shell — the Compose `NavigationBar` (bottom
  tabs), the nav host slot, and the currently-playing sheet stacked on top of both. It takes the
  `currentScreen` rather than a controller, so it needs to know nothing about the router.

`MainActivity` builds the back stack (`rememberSaveableNavStack`) and navigator
(`rememberCircuitNavigator`) inside `CircuitCompositionLocals`, **which must wrap them** — the nav
stack reads `LocalCircuitSaver`, and building the `Circuit` further in crashes before the first
frame. It collects `plexLoginRepo.loginEvent` itself and navigates on it — the one thing that has to
live above any single screen — and registers the back handler via `OnBackPressedDispatcher` (not an
`onBackPressed()` override, which the platform's mandatory predictive-back gesture at targetSdk 36
never calls).

**The back handler asks `peekBackStack().size` before popping, and must keep doing so.** Circuit's
`pop()` at the root delegates to `onBackPressedDispatcher.onBackPressed()`, which re-enters the
handler — calling `pop()` to discover whether there was anything to pop is infinite recursion that
dies as a `StackOverflowError` and looks exactly like the app exiting normally.

Backstack clearing (`Navigator`'s `while (backStackEntryCount > 0) popBackStackImmediate()`, then
Navigation Compose's `popUpTo(startDestination)`) is `resetRoot`. Pass
`Navigator.StateOptions.SaveAndRestore` for a tab switch — the default drops the record and clears
its ViewModel — and the plain default for a login step, where returning is exactly what
`inclusive = true` ruled out.

## Threading Model

```mermaid
graph TD
    A[Main Thread]
    B[UI updates]
    C[StateFlow collection]
    D[IO Dispatcher<br/>Background Threads]
    E[Network calls]
    F[Database operations]
    G[File operations]
    H[Default Dispatcher]
    I[Heavy computations]
    
    A --> B
    A --> C
    D --> E
    D --> F
    D --> G
    H --> I
    
    style A fill:#ffebee
    style D fill:#e8f5e9
    style H fill:#fff4e1
```

## State Management

- **ViewModel State**: `StateFlow` properties exposed by ViewModels
- **SharedPreferences**: User settings and preferences (`PrefsRepo`)
- **Database**: Persisted data state
- **PlexConfig**: Plex-specific configuration and state

## What the layering buys

The one non-obvious point: **lifecycle management**. ViewModels scope work to `viewModelScope`, and
Views collect through `collectWhileStarted` — that extension is what makes collection
lifecycle-aware, and a bare `lifecycleScope.launch` keeps collecting while backgrounded.

## Common Patterns Used

- **Factory Pattern**: For creating ViewModels with dependencies
- **Observer Pattern**: `StateFlow` collectors in Views
- **Repository Pattern**: Single source of truth for data
- **Singleton Pattern**: Application-scoped objects (via Dagger)
- **Service Pattern**: Background media playback


---
id: 03-project-structure
title: Project Structure
type: reference
created_date: '2026-09-01'
---

# Project Structure

## Directory Layout

```
chronicle/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/io/github/mattpvaughn/chronicle/
│   │   │   │   ├── application/      # App initialization & MainActivity
│   │   │   │   ├── data/             # Data layer
│   │   │   │   ├── features/         # UI features (screens)
│   │   │   │   ├── injection/        # Dependency injection (Dagger)
│   │   │   │   ├── navigation/       # Navigation logic
│   │   │   │   ├── util/             # Utility functions
│   │   │   │   └── views/            # Custom views & UI components
│   │   │   ├── res/                  # Android resources (layouts, drawables, etc.)
│   │   │   └── AndroidManifest.xml   # App manifest
│   │   ├── test/                     # Unit tests
│   │   ├── androidTest/              # Instrumented tests
│   │   └── testShared/               # Shared test utilities
│   ├── build.gradle.kts              # App module build configuration
│   └── schemas/                      # Room exported schemas — **committed**, all five DBs
├── backlog/docs/reference/           # Documentation (you are here!)
├── backlog/                          # Tasks, decisions, analysis, research (D13)
├── gradle/                           # Gradle configuration
├── build.gradle.kts                  # Root build configuration
├── settings.gradle.kts               # Gradle settings
└── README.md                         # Project README
```

## Core Packages Explained

### `/application` - Application Entry Point
```
application/
├── ChronicleApplication.kt    # Application class (@HiltAndroidApp)
├── MainActivity.kt             # setContent {}; hosts the Navigation Compose NavHost
├── MainActivityViewModel.kt    # ViewModel for shared app state
├── Constants.kt                # App-wide constants
└── compose/
    ├── ChronicleApp.kt         # The shell: bottom NavigationBar + nav host + player sheet
    └── MiniPlayerHost.kt
```

**Purpose**: App initialization, single activity hosting the Compose UI, global state management

### `/data` - Data Layer
```
data/
├── local/                      # Local data sources
│   ├── BookRepository.kt       # Audiobook data repository
│   ├── TrackRepository.kt      # Track/chapter data repository
│   ├── CollectionsRepository.kt # Collections data
│   ├── BookDatabase.kt         # Room database for books
│   ├── TrackDatabase.kt        # Room database for tracks
│   ├── SharedPreferencesPrefsRepo.kt  # PrefsRepo interface + impl
│   └── LibrarySyncRepository.kt # Library sync state
├── sources/                    # Data source implementations
│   ├── plex/                   # Plex API integration
│   │   ├── PlexService.kt      # Retrofit API interface
│   │   ├── PlexMediaRepository.kt
│   │   ├── PlexLoginRepo.kt    # Authentication
│   │   ├── PlexConfig.kt       # Plex configuration
│   │   ├── CachedFileManager.kt # Downloaded files management
│   │   └── model/              # Plex API response models
│   ├── local/                  # Local data sources
│   ├── MediaSource.kt          # Abstract media source
│   └── SourceManager.kt        # Manages multiple sources
└── model/                      # Data models
    ├── Audiobook.kt            # Main audiobook entity
    ├── MediaItemTrack.kt       # Track/chapter entity
    ├── Chapter.kt              # Chapter information
    ├── Collection.kt           # Collection entity
    └── LoadingStatus.kt        # Loading state models
```

**Purpose**: All data access logic - databases, API calls, caching

### `/features` - UI Features (Screens)
```
features/
├── home/                       # Home screen
│   ├── HomeViewModel.kt
│   └── compose/
│       ├── HomeScreen.kt       # Pure function of state
│       └── HomeDestination.kt  # Wires hiltViewModel() into HomeScreen
├── library/                    # Library/browse screen
│   ├── LibraryViewModel.kt
│   └── compose/
│       ├── LibraryScreen.kt
│       ├── LibraryDestination.kt
│       ├── BookCard.kt
│       ├── BookGrid.kt         # LazyVerticalGrid(GridCells.Adaptive)
│       └── LibraryFilterSheet.kt
├── bookdetails/                # Book details screen
│   ├── AudiobookDetailsViewModel.kt
│   └── compose/
│       ├── DetailsScreen.kt
│       ├── DetailsDestination.kt
│       └── ChapterList.kt
├── currentlyplaying/           # Mini player + full player
│   ├── CurrentlyPlayingViewModel.kt
│   ├── CurrentlyPlaying.kt     # Player state manager
│   └── compose/
│       ├── MiniPlayer.kt
│       ├── PlayerScreen.kt
│       └── PlayerDestination.kt
├── player/                     # Media playback service
│   ├── MediaPlayerService.kt   # Background playback service
│   ├── MediaServiceConnection.kt
│   ├── NotificationBuilder.kt
│   ├── SleepTimer.kt
│   └── compose/
│       └── CastButton.kt       # The one AndroidView island (Cast SDK)
├── search/                     # Search functionality
├── collections/                # Collections screens
├── download/                   # Download management
├── settings/                   # Settings screen
│   ├── SettingsViewModel.kt
│   └── compose/
│       ├── SettingsScreen.kt
│       └── SettingsDestination.kt
└── login/                      # Login flow
    ├── LoginViewModel.kt, ChooseServerViewModel.kt, ChooseLibraryViewModel.kt, ChooseUserViewModel.kt
    └── compose/
        ├── LoginDestination.kt
        ├── PickerScreen.kt, PickerDestinations.kt   # Choose-server/library/user share one screen
        └── OnboardingScaffold.kt
```

**Purpose**: Each feature is a self-contained module with a ViewModel and a `compose/` subpackage —
there is no Fragment layer and no RecyclerView adapter anywhere in the app (cu-206)

**Pattern**: Each feature typically has:
- `ViewModel.kt` - UI state and business logic, injected via Hilt (`@HiltViewModel`)
- `compose/<X>Screen.kt` - a pure composable function of state — no ViewModel reference, so it is
  previewable and unit-testable without Hilt or a `SavedStateHandle`
- `compose/<X>Destination.kt` - the thin composable that calls `hiltViewModel()`, collects the
  ViewModel's `StateFlow`, and passes state + callbacks into the `Screen`

### `/injection` - Dependency Injection (Dagger 2 via Hilt)
```
injection/
├── ChronicleEntryPoint.kt       # @EntryPoint for code Hilt cannot inject into directly
├── modules/                     # Dagger modules (provide dependencies)
│   ├── AppModule.kt             # @InstallIn(SingletonComponent::class)
│   ├── RepositoryModule.kt      # @InstallIn(SingletonComponent::class)
│   ├── ActivityModule.kt        # @InstallIn(ActivityComponent::class)
│   └── ServiceModule.kt         # @InstallIn(ServiceComponent::class)
└── qualifiers/
    └── Scopes.kt                 # @ApplicationScope / @PlayerServiceScope CoroutineScope qualifiers
```

**Purpose**: Configure dependency injection, define object lifetimes and creation. Since cu-185
there are no hand-written `AppComponent`/`ActivityComponent`/`ServiceComponent` classes — Hilt
generates the graph from `@HiltAndroidApp`/`@AndroidEntryPoint`/`@HiltViewModel` annotations plus
these modules, and `SingletonComponent`/`ActivityComponent`/`ServiceComponent` in the `@InstallIn`
lines above are Hilt's own component types (`dagger.hilt.android.components.*`), not project code.

### `/navigation` - Navigation
```
navigation/
├── Destination.kt               # Every route, framework-free (no Android imports)
└── compose/
    └── ChronicleNavHost.kt       # The NavHost graph
```

**Purpose**: Define the app's routes and build the Navigation Compose graph from them. Replaces
`Navigator.kt` (deleted in cu-206), which drove `FragmentManager` transactions by hand.

### `/util` - Utilities
```
util/
├── StorageUtils.kt             # File system utilities
├── JavaLangExt.kt              # Kotlin extensions
├── ImageViewExt.kt             # Coil image loading extensions
└── (other utility files)
```

**Purpose**: Reusable helper functions and extension functions

### `/views` - Custom Views
```
views/
├── BindingAdapters.kt           # Legacy adapters (no ViewBinding call sites remain to use them)
├── BottomSheetChooser.kt
├── ChipGroupExt.kt
├── SpeedChooserState.kt
└── compose/
    ├── CoverImage.kt            # Cover art — every call site uses this, never a bare AsyncImage
    ├── ChronicleScaffold.kt
    ├── BottomChooser.kt
    ├── SpeedChooserSheet.kt
    ├── BookmarkList.kt
    └── BookmarkNoteSheet.kt
```

**Purpose**: Reusable custom UI components. `CoverImage` is the one to know: it carries the
placeholder for offline, no-artwork and failed-decode cases (cu-207) — `CoverImageTest` gates
against a bare `AsyncImage` reaching a screen. `BookCard.kt` in `features/library/compose/` is the
one other file that calls `AsyncImage` directly, and it does so *through* `CoverImage`.

## Resource Structure (`/res`)

```
res/
├── drawable/                   # Images, icons, shapes
├── values/                     # Strings, colors, dimensions, styles
│   ├── strings.xml
│   ├── colors.xml
│   ├── dimens.xml
│   └── styles.xml
├── font/                       # OFL fonts
├── xml/                        # Network security config, backup rules, etc.
└── (other resource folders)
```

There is no `res/layout/` directory — every screen is Compose, so there are no layout XML files to
hold (cu-206). A `res/menu/` directory still exists with six files (`bottom_nav_menu.xml`,
`home_menu.xml`, etc.), but none of them are referenced from any Kotlin source anymore; they are
dead resources left over from the Fragment era, not a current option-menu mechanism.

## Key Files

### Build Files
- **`build.gradle.kts`**: Gradle build configuration (dependencies, plugins)
- **`gradle.properties`**: Gradle properties
- **`settings.gradle.kts`**: Multi-module project settings

### Configuration Files
- **`AndroidManifest.xml`**: App permissions, components declaration
- **`proguard-rules.pro`**: Code obfuscation rules for release builds

### Documentation
- **`README.md`**: Main project readme
- **`CONTRIBUTING.md`**: Contribution guidelines
- **`LICENSE`**: Project license
- **`backlog/`**: all non-code knowledge — tasks, decisions, reference docs (D13). Upstream's
  `todo.md` is gone; its live items became tasks in cu-46.

## Package Naming Convention

All code follows the base package: `io.github.mattpvaughn.chronicle`

Subpackages are organized by architectural layer or feature:
- `application.*` - Application-level code
- `data.*` - Data layer
- `features.<feature>.*` - UI features
- `injection.*` - Dependency injection
- `navigation.*` - Navigation
- `util.*` - Utilities
- `views.*` - Custom views

## Module Organization Pattern

Chronicle is currently a **single-module** app. All code is in the `app` module. 

This is typical for small to medium Android apps. As the app grows, it could be split into multiple modules:
- `app` - Main app
- `data` - Data layer
- `common` - Shared utilities
- `feature-home`, `feature-library` - Feature modules

## Finding Code

**To find a specific screen:**
1. Look in `features/` directory
2. Find the feature name (e.g., `home`, `library`, `bookdetails`)
3. The ViewModel is directly in that folder; the `Screen.kt`/`Destination.kt` pair is in its `compose/` subfolder

**To find data logic:**
1. Look in `data/` directory
2. Check `data/local/` for repositories
3. Check `data/sources/plex/` for Plex API code
4. Check `data/model/` for data classes

**To understand dependencies:**
1. Look in `injection/modules/` for how objects are created and which Hilt component
   (`@InstallIn(...)`) they belong to

**To modify UI:**
1. Find the screen in `features/<feature>/compose/` — `<X>Screen.kt` for the composable content,
   `<X>Destination.kt` for how it gets its ViewModel and its route arguments
2. Strings in `res/values/strings.xml`


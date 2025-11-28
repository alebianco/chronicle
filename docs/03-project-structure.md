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
│   └── schemas/                      # Room database schemas
├── docs/                             # Documentation (you are here!)
├── gradle/                           # Gradle configuration
├── build.gradle.kts                  # Root build configuration
├── settings.gradle.kts               # Gradle settings
└── README.md                         # Project README
```

## Core Packages Explained

### `/application` - Application Entry Point
```
application/
├── ChronicleApplication.kt    # Application class (app-wide initialization)
├── MainActivity.kt             # Single activity hosting all fragments
├── MainActivityViewModel.kt    # ViewModel for shared app state
├── Injector.kt                 # Dagger component accessor
├── Constants.kt                # App-wide constants
└── ChronicleBillingManager.kt  # In-app billing/purchases (if any)
```

**Purpose**: App initialization, single activity container, global state management

### `/data` - Data Layer
```
data/
├── local/                      # Local data sources
│   ├── BookRepository.kt       # Audiobook data repository
│   ├── TrackRepository.kt      # Track/chapter data repository
│   ├── CollectionsRepository.kt # Collections data
│   ├── BookDatabase.kt         # Room database for books
│   ├── TrackDatabase.kt        # Room database for tracks
│   ├── PrefsRepo.kt            # SharedPreferences wrapper
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
│   ├── HomeFragment.kt
│   └── HomeViewModel.kt
├── library/                    # Library/browse screen
│   ├── LibraryFragment.kt
│   ├── LibraryViewModel.kt
│   └── AudiobookAdapter.kt     # RecyclerView adapter
├── bookdetails/                # Book details screen
│   ├── AudiobookDetailsFragment.kt
│   └── AudiobookDetailsViewModel.kt
├── currentlyplaying/           # Mini player (bottom bar)
│   ├── CurrentlyPlayingFragment.kt
│   ├── CurrentlyPlayingViewModel.kt
│   └── CurrentlyPlaying.kt     # Player state manager
├── player/                     # Media playback service
│   ├── MediaPlayerService.kt   # Background playback service
│   ├── MediaServiceConnection.kt
│   ├── NotificationBuilder.kt
│   └── SleepTimer.kt
├── search/                     # Search functionality
├── collections/                # Collections screens
├── download/                   # Download management
├── settings/                   # Settings screen
│   ├── SettingsFragment.kt
│   └── SettingsViewModel.kt
└── login/                      # Login flow
    ├── LoginFragment.kt
    ├── ChooseServerFragment.kt
    ├── ChooseLibraryFragment.kt
    └── ChooseUserFragment.kt
```

**Purpose**: Each feature is a self-contained module with Fragment, ViewModel, and related UI code

**Pattern**: Each feature typically has:
- `Fragment.kt` - UI and user interaction
- `ViewModel.kt` - UI state and business logic
- `Adapter.kt` - RecyclerView adapter (if needed)

### `/injection` - Dependency Injection (Dagger 2)
```
injection/
├── components/                 # Dagger components (dependency graphs)
│   ├── AppComponent.kt         # App-level dependencies
│   ├── ActivityComponent.kt    # Activity-level dependencies
│   └── ServiceComponent.kt     # Service-level dependencies
├── modules/                    # Dagger modules (provide dependencies)
│   ├── AppModule.kt            # App-level providers
│   ├── ActivityModule.kt       # Activity-level providers
│   └── ServiceModule.kt        # Service-level providers
└── scopes/                     # Custom Dagger scopes
    ├── ActivityScope.kt
    └── ServiceScope.kt
```

**Purpose**: Configure dependency injection, define object lifetimes and creation

### `/navigation` - Navigation
```
navigation/
└── Navigator.kt                # Centralized navigation logic
```

**Purpose**: Manage fragment transactions and screen transitions

### `/util` - Utilities
```
util/
├── StorageUtils.kt             # File system utilities
├── JavaLangExt.kt              # Kotlin extensions
├── FrescoExt.kt                # Fresco image loading extensions
└── (other utility files)
```

**Purpose**: Reusable helper functions and extension functions

### `/views` - Custom Views
```
views/
├── BindingAdapters.kt          # Data binding adapters
├── BottomSheetChooser.kt       # Custom bottom sheet
├── ModalBottomSheetSpeedChooser.kt
└── (other custom views)
```

**Purpose**: Reusable custom UI components

## Resource Structure (`/res`)

```
res/
├── layout/                     # XML layout files
│   ├── activity_main.xml
│   ├── fragment_home.xml
│   ├── fragment_library.xml
│   └── (other layouts)
├── drawable/                   # Images, icons, shapes
├── values/                     # Strings, colors, dimensions, styles
│   ├── strings.xml
│   ├── colors.xml
│   ├── dimens.xml
│   └── styles.xml
├── menu/                       # Menu definitions
├── xml/                        # Other XML resources
└── (other resource folders)
```

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
- **`todo.md`**: Development roadmap and tasks

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
3. Fragment and ViewModel will be in that folder

**To find data logic:**
1. Look in `data/` directory
2. Check `data/local/` for repositories
3. Check `data/sources/plex/` for Plex API code
4. Check `data/model/` for data classes

**To understand dependencies:**
1. Look in `injection/components/` for dependency graphs
2. Look in `injection/modules/` for how objects are created

**To modify UI:**
1. Find the Fragment in `features/<feature>/`
2. Layout XML in `res/layout/`
3. Strings in `res/values/strings.xml`


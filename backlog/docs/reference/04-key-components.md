---
id: 04-key-components
title: Key Components
type: reference
created_date: '2026-09-01'
---

# Key Components

This document explains the most important classes in Chronicle and what they do.

## Application Layer

### ChronicleApplication
**Location**: `application/ChronicleApplication.kt`

**What it does**:
- Initializes the entire app when it starts
- Is the Hilt DI root (`@HiltAndroidApp`) — since cu-185 the graph is Hilt-generated, not a
  hand-rolled `AppComponent`
- Sets up image loading (Coil 3)
- Configures logging (Timber)
- Registers for network connectivity changes

**When it's used**: Automatically when the app launches

**Key responsibilities**:
- One-time app setup
- Root of the Hilt dependency graph
- Monitor network connectivity

### MainActivity
**Location**: `application/MainActivity.kt`

**What it does**:
- The single Activity; calls `setContent {}` and hosts every screen as a Compose destination
  inside a Navigation Compose `NavHost` (cu-206 — there is no Fragment layer left)
- Manages the bottom navigation bar (`ChronicleApp`'s `NavigationBar`)
- Handles the mini player (currently playing bar at bottom)
- Manages back button behavior, via `OnBackPressedDispatcher` (not an `onBackPressed()` override,
  which the mandatory predictive-back gesture at targetSdk 36 never calls)

**Key features**:
- Hosts the Navigation Compose graph (`ChronicleNavHost`) for all screens
- Bottom sheet player (expandable mini player), driven by `MainActivityViewModel.BottomSheetState`
- Connects to MediaPlayerService for playback control
- Handles search intent from system

### MainActivityViewModel
**Location**: `application/MainActivityViewModel.kt`

**What it does**:
- Manages state shared across all screens
- Controls the bottom sheet player state (collapsed/expanded)
- Tracks the currently playing audiobook

## Data Layer

### BookRepository
**Location**: `data/local/BookRepository.kt`

**What it does**:
- Single source of truth for all audiobook data
- Fetches books from Plex API
- Caches books in local Room database
- Provides `Flow` of books to ViewModels

**Key methods**:
- `getAllBooks()` - Get all books
- `getAudiobook(id)` - Get specific book
- `refreshData()` - Sync with Plex server
- `updateProgress(bookId, time, progress)` - Update listening progress
- `search(query)` - Search books by title/author
- `getRecentlyAdded()` - Recently added books
- `getRecentlyListened()` - Recently played books

**Why it's important**: Every screen that shows book data uses this repository

### TrackRepository
**Location**: `data/local/TrackRepository.kt`

**What it does**:
- Manages individual audio tracks/chapters within audiobooks
- Tracks listening progress per track
- Handles chapter navigation

**Key methods**:
- `getTracksForBook(bookId)` - Get all tracks in a book
- `getTrack(trackId)` - Get specific track
- `updateProgress(trackId, progress)` - Update track progress

### PlexService
**Location**: `data/sources/plex/PlexService.kt`

**What it does**:
- Retrofit interface defining Plex API endpoints
- Methods for fetching libraries, books, tracks, collections
- Authentication and server communication

**Key endpoints**:
- Get libraries
- Get albums (audiobooks)
- Get tracks
- Get collections
- Update progress (scrobble)

### PlexLoginRepo
**Location**: `data/sources/plex/PlexLoginRepo.kt`

**What it does**:
- Manages Plex authentication
- Handles login flow
- Stores and manages auth tokens
- Tracks login state

**Login states**:
- `LOGGED_OUT` - Not logged in
- `LOGGED_IN` - Has Plex token
- `LOGGED_IN_SELECTED_SERVER` - Server chosen
- `LOGGED_IN_SELECTED_LIBRARY` - Library chosen
- `LOGGED_IN_FULLY` - Ready to use app

### CachedFileManager
**Location**: `data/sources/plex/CachedFileManager.kt`

**What it does**:
- Manages downloaded audiobook files
- Handles download queue
- Manages storage space
- Provides offline access to downloaded content

**Key features**:
- Download entire audiobooks
- Track download progress
- Delete cached files
- Check if book is cached

### PrefsRepo
**Location**: `data/local/SharedPreferencesPrefsRepo.kt` (`PrefsRepo` is the interface there)

**What it does**:
- Wrapper around SharedPreferences
- Stores and retrieves user settings
- Provides `Flow` of settings for UI updates (`util/PreferenceFlow.kt`)

**Settings managed**:
- Playback speed
- Auto-rewind duration
- Sleep timer duration
- Offline mode toggle
- Skip silence toggle
- View preferences (grid vs list)
- Book cover style

## Player Components

### MediaPlayerService
**Location**: `features/player/MediaPlayerService.kt`

**What it does**:
- Background service for audio playback
- Keeps playing even when app is closed
- Manages ExoPlayer (actual audio player)
- Shows notification with playback controls
- Handles headphone button clicks

**Key responsibilities**:
- Play/pause/stop audio
- Skip forward/backward
- Change playback speed
- Handle audio focus (other apps playing sound)
- Auto-save progress to database
- Sync progress to Plex server

**Why it's a service**: Must run in background to keep playing when app is minimized

### MediaServiceConnection
**Location**: `features/player/MediaServiceConnection.kt`

**What it does**:
- Connects `MainActivity` (and, through it, screens/ViewModels) to MediaPlayerService
- Sends commands to the player (play, pause, seek)
- Receives playback state updates
- Provides `StateFlow` of playback state to UI

**Usage**: ViewModels use this to control playback

### CurrentlyPlaying
**Location**: `features/currentlyplaying/CurrentlyPlaying.kt`

**What it does**:
- Manages the state of the currently playing audiobook
- Tracks which book and track are active
- Handles book/track changes
- Manages playlist (tracks in a book)

**Key state**:
- Current audiobook
- Current track
- Playback position
- Playlist of tracks

### SleepTimer
**Location**: `features/player/SleepTimer.kt`

**What it does**:
- Countdown timer to stop playback
- Can stop after X minutes or at end of chapter
- Shows notification with time remaining

## View Layer (Screens)

### HomeScreen & HomeViewModel
**Location**: `features/home/` (ViewModel), `features/home/compose/` (`HomeScreen.kt` +
`HomeDestination.kt`)

**What they do**:
- Home screen with recently added, recently listened, and downloaded books
- Pull to refresh (`PullToRefreshBox`, since cu-206 removed the XML `SwipeRefreshLayout` host)
- Quick access to search
- Displays curated book lists

### LibraryScreen & LibraryViewModel
**Location**: `features/library/` (ViewModel), `features/library/compose/` (`LibraryScreen.kt`,
`LibraryDestination.kt`, `BookCard.kt`, `BookGrid.kt`, `LibraryFilterSheet.kt`)

**What they do**:
- Complete library view with all audiobooks
- Search functionality
- Sort and filter options
- Grid or list view toggle — the grid is `LazyVerticalGrid(GridCells.Adaptive(minSize))`, never
  `Fixed`, so cover size does not depend on a hardcoded column count

### DetailsScreen & AudiobookDetailsViewModel
**Location**: `features/bookdetails/` (ViewModel), `features/bookdetails/compose/`
(`DetailsScreen.kt`, `DetailsDestination.kt`, `ChapterList.kt`)

**What they do**:
- Show detailed information about an audiobook
- Display chapters/tracks
- Play button and download button
- Show listening progress
- Mark as favorite

### PlayerScreen & CurrentlyPlayingViewModel
**Location**: `features/currentlyplaying/` (ViewModel, `CurrentlyPlaying.kt`),
`features/currentlyplaying/compose/` (`PlayerScreen.kt`, `PlayerDestination.kt`, `MiniPlayer.kt`)

**What they do**:
- Full player screen (expands from mini player)
- Shows book cover art
- Playback controls (play, pause, skip, speed)
- Progress bar with seeking
- Chapter list
- Sleep timer control
- The Cast route button (`features/player/compose/CastButton.kt`) is the one `AndroidView` in the
  whole player — the Cast SDK has no Compose surface (decision-19)

### SettingsScreen & SettingsViewModel
**Location**: `features/settings/` (ViewModel), `features/settings/compose/` (`SettingsScreen.kt`,
`SettingsDestination.kt`)

**What they do**:
- User preferences
- Playback settings (speed, auto-rewind, skip silence)
- Appearance settings
- Storage management
- Server information
- Logout

## Dependency Injection

Since cu-185 the three components below are **Hilt's own** generated types
(`dagger.hilt.android.components.*` / `dagger.hilt.components.SingletonComponent`), not classes
this codebase declares. `injection/modules/*.kt` attach providers to them with
`@Module @InstallIn(...)`; there is no `injection/components/` package anymore.

### SingletonComponent
**Provided by**: Hilt, populated by `injection/modules/AppModule.kt` and `RepositoryModule.kt`

**What it does**:
- Top-level component, rooted at `ChronicleApplication` (`@HiltAndroidApp`)
- Provides application-scoped dependencies (singletons)
- Parent of ActivityComponent and ServiceComponent in Hilt's hierarchy

**What it provides**:
- Repositories
- Databases
- Network clients (Retrofit)
- Preferences
- Plex services

### ActivityComponent
**Provided by**: Hilt, populated by `injection/modules/ActivityModule.kt`

**What it does**:
- Scoped to `MainActivity` (`@AndroidEntryPoint`)
- Provides activity-scoped dependencies
- Every screen's ViewModel is obtained via `hiltViewModel()` in its `<X>Destination.kt`, backed by
  Hilt's generated ViewModel factories — there are no Fragments to inject into

**What it provides**:
- Activity-scoped providers (e.g. `ProgressUpdater`)
- UI-related dependencies

### ServiceComponent
**Provided by**: Hilt, populated by `injection/modules/ServiceModule.kt`

**What it does**:
- Scoped to `MediaPlayerService` (`@AndroidEntryPoint`)
- Provides service-scoped dependencies

**What it provides**:
- ExoPlayer
- MediaSession
- Notification builder

## Navigation

`Navigator.kt` is deleted (cu-206). Navigation Compose replaced it with two files:

### Destination
**Location**: `navigation/Destination.kt`

**What it does**:
- Framework-free sealed interface — one entry per screen, each carrying its own route string
- `encodeArg`/`decodeArg` percent-encode route arguments that can hold arbitrary text (a book
  title, a facet value), so a raw `/` or `?` cannot silently break route matching
- `destinationForLogin(state)` — the pure function deciding where a login state should navigate,
  called from `MainActivity` (the old `Navigator` init block)

**Key entries**: `Home`, `Library`, `Collections`, `Settings`, `Browse`, `SeriesIndexTester`,
`Login`/`ChooseUser`/`ChooseServer`/`ChooseLibrary`, `BookDetails(bookId)`,
`CollectionDetails(collectionId)`, `FacetBooks(kind, value)`

### ChronicleNavHost
**Location**: `navigation/compose/ChronicleNavHost.kt`

**What it does**:
- Builds the `NavHost` graph: one `composable(...)` block per `Destination`, each instantiating
  that screen's `<X>Destination.kt`
- Owns the one shared navigation callback, `openBook`, since six different screens navigate to a
  book the same way

**Handled instead by the framework** (no longer hand-written methods):
- Clearing the back stack before a tab switch → `popUpTo(startDestination)`
- "Is this screen already showing" → `currentBackStackEntry`
- Login-flow routing → `MainActivity` collects `IPlexLoginRepo.loginEvent` and calls
  `destinationForLogin`

## Data Models

### Audiobook
**Location**: `data/model/Audiobook.kt`

**What it is**:
- Main data class for an audiobook
- Room entity (database table)
- Contains all audiobook metadata

**Key properties**:
- `id` — **`String`**, not `Int` (cu-71, so a non-numeric backend can be represented). A DAO
  parameter bound against it must also be `String`: SQLite compares across storage classes, so a
  numeric bind matches **no row, silently**.
- `title`, `author` — book metadata
- `duration` — total length in milliseconds
- `progress` — cached derivation of the **tracks'** position, never authoritative
  ([[decision-16]]). `merge` carries the local value and never adopts `network.progress`.
- `isCached` — whether the book is downloaded
- `thumb` — cover art URL
- `source` — **`SourceId`** (cu-127, [[decision-21]]): which source instance owns this row. A
  local-only column, so it must be named in **both** arms of `merge` or a refresh blanks it.
- `playbackSpeed` — per-book override, `NO_SPEED_OVERRIDE` (`0f`) when the book follows the global
  preference. Read only through `effectiveSpeed(global)`.
- `seriesIndex` — parsed from `titleSort` in hundredths (cu-146), not from Plex's `index`.

**There is no `chapters` property.** The column was dropped in `BookDatabase` v14 (cu-159);
chapters live in `ChapterDatabase` and are read through `resolveChapters` /
`resolveChaptersFromCache` (`data/model/ChapterAssembly.kt`). Do not reintroduce a serialized copy
on the book.

### MediaItemTrack
**Location**: `data/model/MediaItemTrack.kt`

**What it is**:
- Data class for a single audio track/chapter
- Room entity

**Key properties**:
- `id` — **`String`** (cu-71), same binding caveat as `Audiobook.id`
- `title` — track title
- `duration` — track length
- `progress` — the track's `viewOffset`. This is the **single source of truth** for listening
  position ([[decision-16]]); Plex stores no album-level offset.
- `index` — track number/order
- `parentKey` — the owning book
- `source` — `SourceId`, as on `Audiobook`

## How Components Work Together

### Example: Playing an Audiobook

1. User taps book in **LibraryScreen**
2. `onBookClick` (wired in `ChronicleNavHost`) navigates to `Destination.BookDetails(bookId)`,
   rendering **DetailsScreen**
3. **AudiobookDetailsViewModel** loads book from **BookRepository**
4. User taps play button
5. ViewModel calls **MediaServiceConnection** to start playback
6. **MediaServiceConnection** sends command to **MediaPlayerService**
7. **MediaPlayerService** uses **TrackRepository** to load tracks
8. ExoPlayer in service starts playing audio
9. Service updates **CurrentlyPlaying** state
10. The mini player (**MiniPlayer**, hosted from `MainActivity`) shows the current book
11. Service periodically updates progress in **BookRepository**
12. **BookRepository** saves to database and syncs to Plex via **PlexService**

### Example: Downloading a Book

1. User taps download button in **DetailsScreen**
2. ViewModel calls **CachedFileManager**.downloadBook()
3. **CachedFileManager** uses **Fetch** library to download tracks
4. Download progress shown in UI via `StateFlow`
5. When complete, **CachedFileManager** updates **BookRepository**
6. Book's `isCached` property set to true
7. UI updates to show downloaded state


# Key Components

This document explains the most important classes in Chronicle and what they do.

## Application Layer

### ChronicleApplication
**Location**: `application/ChronicleApplication.kt`

**What it does**:
- Initializes the entire app when it starts
- Creates the Dagger dependency injection graph
- Sets up image loading (Fresco)
- Configures logging (Timber)
- Registers for network connectivity changes

**When it's used**: Automatically when the app launches

**Key responsibilities**:
- One-time app setup
- Provide access to AppComponent (dependency injection)
- Monitor network connectivity

### MainActivity
**Location**: `application/MainActivity.kt`

**What it does**:
- The single Activity that hosts all screens (fragments)
- Manages the bottom navigation bar
- Handles the mini player (currently playing bar at bottom)
- Manages back button behavior

**Key features**:
- Fragment container for all screens
- Bottom sheet player (expandable mini player)
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
- Provides LiveData of books to ViewModels

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
**Location**: `data/local/PrefsRepo.kt`

**What it does**:
- Wrapper around SharedPreferences
- Stores and retrieves user settings
- Provides LiveData of settings for UI updates

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
- Connects Fragments/Activities to MediaPlayerService
- Sends commands to the player (play, pause, seek)
- Receives playback state updates
- Provides LiveData of playback state to UI

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

### HomeFragment & HomeViewModel
**Location**: `features/home/`

**What they do**:
- Home screen with recently added, recently listened, and downloaded books
- Pull to refresh
- Quick access to search
- Displays curated book lists

### LibraryFragment & LibraryViewModel
**Location**: `features/library/`

**What they do**:
- Complete library view with all audiobooks
- Search functionality
- Sort and filter options
- Grid or list view toggle

### AudiobookDetailsFragment & AudiobookDetailsViewModel
**Location**: `features/bookdetails/`

**What they do**:
- Show detailed information about an audiobook
- Display chapters/tracks
- Play button and download button
- Show listening progress
- Mark as favorite

### CurrentlyPlayingFragment & CurrentlyPlayingViewModel
**Location**: `features/currentlyplaying/`

**What they do**:
- Full player screen (expands from mini player)
- Shows book cover art
- Playback controls (play, pause, skip, speed)
- Progress bar with seeking
- Chapter list
- Sleep timer control

### SettingsFragment & SettingsViewModel
**Location**: `features/settings/`

**What they do**:
- User preferences
- Playback settings (speed, auto-rewind, skip silence)
- Appearance settings
- Storage management
- Server information
- Logout

## Dependency Injection

### AppComponent
**Location**: `injection/components/AppComponent.kt`

**What it does**:
- Top-level Dagger component
- Provides application-scoped dependencies (singletons)
- Creates ActivityComponent and ServiceComponent

**What it provides**:
- Repositories
- Databases
- Network clients (Retrofit)
- Preferences
- Plex services

### ActivityComponent
**Location**: `injection/components/ActivityComponent.kt`

**What it does**:
- Dagger component for MainActivity
- Provides activity-scoped dependencies
- Injects dependencies into Fragments

**What it provides**:
- ViewModelFactories
- Navigator
- UI-related dependencies

### ServiceComponent
**Location**: `injection/components/ServiceComponent.kt`

**What it does**:
- Dagger component for MediaPlayerService
- Provides service-scoped dependencies

**What it provides**:
- ExoPlayer
- MediaSession
- Notification builder

## Navigation

### Navigator
**Location**: `navigation/Navigator.kt`

**What it does**:
- Centralized navigation logic
- Handles all screen transitions
- Manages fragment back stack
- Handles login flow routing

**Key methods**:
- `showHome()` - Go to home screen
- `showLibrary()` - Go to library
- `showBookDetails(bookId)` - Show book details
- `showSettings()` - Go to settings
- `showLogin()` - Show login flow

## Data Models

### Audiobook
**Location**: `data/model/Audiobook.kt`

**What it is**:
- Main data class for an audiobook
- Room entity (database table)
- Contains all audiobook metadata

**Key properties**:
- `id` - Unique identifier
- `title` - Book title
- `author` - Book author
- `duration` - Total length in milliseconds
- `progress` - Current listening position
- `isCached` - Whether book is downloaded
- `thumb` - Cover art URL
- `chapters` - List of chapters

### MediaItemTrack
**Location**: `data/model/MediaItemTrack.kt`

**What it is**:
- Data class for a single audio track/chapter
- Room entity

**Key properties**:
- `id` - Unique identifier
- `title` - Track/chapter title
- `duration` - Track length
- `progress` - Listening position in this track
- `index` - Track number/order

## How Components Work Together

### Example: Playing an Audiobook

1. User taps book in **LibraryFragment**
2. **Navigator** opens **AudiobookDetailsFragment**
3. **AudiobookDetailsViewModel** loads book from **BookRepository**
4. User taps play button
5. ViewModel calls **MediaServiceConnection** to start playback
6. **MediaServiceConnection** sends command to **MediaPlayerService**
7. **MediaPlayerService** uses **TrackRepository** to load tracks
8. ExoPlayer in service starts playing audio
9. Service updates **CurrentlyPlaying** state
10. **CurrentlyPlayingFragment** (mini player) shows current book
11. Service periodically updates progress in **BookRepository**
12. **BookRepository** saves to database and syncs to Plex via **PlexService**

### Example: Downloading a Book

1. User taps download button in **AudiobookDetailsFragment**
2. ViewModel calls **CachedFileManager**.downloadBook()
3. **CachedFileManager** uses **Fetch** library to download tracks
4. Download progress shown in UI via LiveData
5. When complete, **CachedFileManager** updates **BookRepository**
6. Book's `isCached` property set to true
7. UI updates to show downloaded state


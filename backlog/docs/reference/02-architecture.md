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
    A[View<br/>Fragments, Activities, XML Layouts<br/>UI Layer]
    B[ViewModel<br/>Business Logic, UI State<br/>Presentation Layer]
    C[Model<br/>Repository, Data Sources, Database<br/>Data Layer]
    
    A -->|observes LiveData<br/>calls methods| B
    B -->|uses| C
    
    style A fill:#e1f5ff
    style B fill:#fff4e1
    style C fill:#e8f5e9
```

### View (UI Layer)
- **Fragments**: Each screen is a Fragment (HomeFragment, LibraryFragment, etc.)
- **Activities**: Single MainActivity hosts all fragments
- **Data Binding**: XML layouts bind directly to ViewModel properties
- **Responsibilities**: Display data, handle user input, navigation

### ViewModel (Presentation Layer)
- **Purpose**: Holds UI state and handles UI logic
- **Lifecycle**: Survives configuration changes (screen rotation)
- **Communication**: Exposes `LiveData` to Views, calls Repository methods
- **Examples**: `HomeViewModel`, `LibraryViewModel`, `CurrentlyPlayingViewModel`

### Model (Data Layer)
- **Repositories**: Single source of truth for data (`BookRepository`, `TrackRepository`)
- **Data Sources**: Where data comes from (Plex API, Local Database, File System)
- **Database**: Room database for local caching
- **Responsibilities**: Fetch, cache, and manage data

## Key Architectural Components

### 1. Dependency Injection (Dagger 2)

Dagger 2 handles all object creation and dependency management:

```mermaid
graph TD
    A[AppComponent<br/>Application Scope]
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

**Why Dagger?**
- Compile-time dependency verification
- No reflection overhead
- Clear dependency graph
- Easy testing with mock implementations

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
- Returns LiveData to ViewModels
- Handles offline mode

### 3. Reactive Programming (LiveData + Coroutines)

**LiveData**: Observable data holder
- Lifecycle-aware (automatically stops updates when UI is inactive)
- Used for UI updates

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
    D[MediaSessionConnector<br/>Connects ExoPlayer to MediaSession]
    E[NotificationBuilder<br/>Now Playing notification]
    
    A --> B
    A --> C
    A --> D
    A --> E
    
    style A fill:#e3f2fd
    style B fill:#ffebee
    style C fill:#f3e5f5
```

**MediaServiceConnection**: Activities/Fragments bind to the service
- Sends playback commands
- Receives playback state updates
- Survives across the entire app lifecycle

## Data Sources

### 1. Plex API (Remote)
- **PlexService**: Retrofit interface for API calls
- **PlexMediaRepository**: Manages Plex data
- **PlexLoginRepo**: Handles authentication

### 2. Room Database (Local)
- **BookDatabase**: Stores audiobook metadata
- **TrackDatabase**: Stores track/chapter information
- **CollectionsDatabase**: Stores collection data

### 3. File System (Local Cache)
- **CachedFileManager**: Manages downloaded audio files
- **Fetch**: Library for downloading files

## Navigation

**Navigator**: Single class managing all screen transitions
- Fragment transactions
- Back stack management
- Login flow routing
- Deep linking support

## Threading Model

```mermaid
graph TD
    A[Main Thread]
    B[UI updates]
    C[LiveData observations]
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

- **ViewModel State**: `LiveData` properties exposed by ViewModels
- **SharedPreferences**: User settings and preferences (`PrefsRepo`)
- **Database**: Persisted data state
- **PlexConfig**: Plex-specific configuration and state

## Benefits of This Architecture

1. **Separation of Concerns**: Each layer has clear responsibilities
2. **Testability**: Easy to mock dependencies and test in isolation
3. **Maintainability**: Changes in one layer don't break others
4. **Lifecycle Management**: ViewModels and LiveData handle Android lifecycle automatically
5. **Offline Support**: Repository pattern makes it easy to switch between online/offline data
6. **Scalability**: New features follow established patterns

## Common Patterns Used

- **Factory Pattern**: For creating ViewModels with dependencies
- **Observer Pattern**: LiveData observers in Views
- **Repository Pattern**: Single source of truth for data
- **Singleton Pattern**: Application-scoped objects (via Dagger)
- **Service Pattern**: Background media playback


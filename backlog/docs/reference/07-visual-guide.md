---
id: 07-visual-guide
title: Visual Architecture Guide
type: reference
created_date: '2026-09-01'
---

# Visual Architecture Guide

This document provides visual representations of Chronicle's architecture for quick reference.

## App Structure Overview

```mermaid
graph TB
    subgraph Chronicle App
        subgraph UI Layer - Compose Screens
            A[Home<br/>Screen]
            B[Library<br/>Screen]
            C[Book Details<br/>Screen]
            D[Player<br/>Screen]
        end
        
        subgraph ViewModel Layer
            E[HomeViewModel]
            F[LibraryViewModel]
            G[CurrentlyPlayingViewModel]
        end
        
        subgraph Repository Layer
            H[BookRepository]
            I[TrackRepository]
        end
        
        subgraph Data Sources
            J[Plex API]
            K[Room Database]
            L[File Cache]
        end
        
        A -->|collects StateFlow| E
        B -->|collects StateFlow| F
        C -->|collects StateFlow| E
        D -->|collects StateFlow| G
        
        E -->|calls methods| H
        F -->|calls methods| H
        G -->|calls methods| I
        
        H --> J
        H --> K
        I --> K
        H --> L
    end
    
    style A fill:#e1f5ff
    style B fill:#e1f5ff
    style C fill:#e1f5ff
    style D fill:#e1f5ff
    style E fill:#fff4e1
    style F fill:#fff4e1
    style G fill:#fff4e1
    style H fill:#e8f5e9
    style I fill:#e8f5e9
```

## MVVM Flow

```mermaid
sequenceDiagram
    participant User
    participant View as VIEW (Compose Screen)<br/>• Displays UI<br/>• Handles input<br/>• Collects StateFlow
    participant VM as VIEWMODEL<br/>• Holds UI state<br/>• Business logic<br/>• Survives config changes
    participant Repo as REPOSITORY<br/>• Single source of truth<br/>• Manages data sources<br/>• Decides network vs cache
    participant DS as DATA SOURCES<br/>Plex API / Room / File System
    
    User->>View: (1) Tap button
    View->>VM: (2) Call ViewModel method
    VM->>Repo: (3) Call Repository method
    Repo->>DS: (4) Fetch data
    DS-->>Repo: (5) Return data
    Repo-->>VM: Data flows back
    VM-->>View: (6) Update UI via StateFlow
    View->>User: Display updated UI
```

## Dependency Injection Hierarchy

These three components are Hilt's own generated types, not hand-written classes —
`ActivityModule.kt`/`ServiceModule.kt`/`AppModule.kt` attach providers to them with
`@InstallIn(...)`:

```mermaid
graph TB
    subgraph "ChronicleApplication - @HiltAndroidApp"
        A[SingletonComponent<br/>Lifetime: Entire app]
        A1[Repositories<br/>BookRepository, TrackRepository]
        A2[Databases - Room]
        A3[Network<br/>Retrofit, PlexService]
        A4[Preferences - PrefsRepo]
        A5[File Manager<br/>CachedFileManager]
        A --> A1
        A --> A2
        A --> A3
        A --> A4
        A --> A5
    end
    
    subgraph "MainActivity - @AndroidEntryPoint"
        B[ActivityComponent<br/>Lifetime: While Activity exists]
        B1[hiltViewModel factories, per screen]
        B2[MediaServiceConnection]
        B --> B1
        B --> B2
    end
    
    subgraph "MediaPlayerService - @AndroidEntryPoint"
        C[ServiceComponent<br/>Lifetime: While Service exists]
        C1[ExoPlayer]
        C2[MediaSession]
        C3[NotificationBuilder]
        C4[AudiobookMediaSessionCallback]
        C --> C1
        C --> C2
        C --> C3
        C --> C4
    end
    
    A -->|parent of| B
    A -->|parent of| C
    
    style A fill:#e3f2fd
    style B fill:#f3e5f5
    style C fill:#e8f5e9
```

## Feature Module Structure

Each feature in `features/` follows this pattern:

```
features/home/
│
├── HomeViewModel.kt
│   ├─ Holds UI state (StateFlow properties)
│   ├─ Calls Repository methods
│   ├─ Transforms data for UI
│   └─ @HiltViewModel — injected via Hilt, obtained with hiltViewModel()
│
└── compose/
    ├── HomeScreen.kt
    │   ├─ Pure function of state — no ViewModel reference
    │   ├─ Renders the shelves (LazyColumn / LazyVerticalGrid, never a RecyclerView adapter)
    │   └─ Emits callbacks for user interactions (onBookClick, etc.)
    │
    └── HomeCircuit.kt
        ├─ Calls hiltViewModel() to get the ViewModel
        ├─ Collects its StateFlow via collectAsStateWithLifecycle()
        └─ Passes state + callbacks into HomeScreen
```

## Data Flow Example: Loading Books

```mermaid
sequenceDiagram
    participant User
    participant LibraryDestination
    participant LibraryViewModel
    participant BookRepository
    participant RoomDatabase
    
    User->>LibraryDestination: Opens App
    LibraryDestination->>LibraryDestination: hiltViewModel()
    LibraryDestination->>LibraryViewModel: collectAsStateWithLifecycle(viewModel.books)
    LibraryViewModel->>BookRepository: getAllBooks()
    BookRepository->>RoomDatabase: bookDao.getAllBooks()
    Note over RoomDatabase: Query: SELECT * FROM Audiobook<br/>Returns: Flow
    Note over RoomDatabase: Room automatically emits data
    RoomDatabase-->>LibraryDestination: new State value
    LibraryDestination->>LibraryDestination: recomposes LibraryScreen (LazyVerticalGrid)
    LibraryDestination->>User: UI Updated! ✓
```

## Media Playback Architecture

```mermaid
graph TB
    A[Android System<br/>Bluetooth, Notifications, Android Auto]
    
    subgraph MediaPlayerService - Background Service
        B[MediaSession<br/>Android media framework integration]
        C[AudiobookMediaSessionCallback<br/>Handles transport controls and playFromMediaId]
        D[ExoPlayer<br/>Actual audio playback]
        E[NotificationBuilder<br/>Shows now playing notification]
    end
    
    F[MediaServiceConnection<br/>Bridge between Service and UI]
    G[MiniPlayer / PlayerScreen<br/>Mini player and full player UI]
    
    A -->|Media commands| B
    B --> C
    C --> D
    B --> E
    F -->|Playback state updates| G
    D -->|State changes| F
    
    style A fill:#e3f2fd
    style D fill:#ffebee
    style F fill:#fff4e1
    style G fill:#e1f5ff
```

## Offline Mode Logic

```mermaid
flowchart TD
    A[User toggles Offline Mode in Settings]
    B[PrefsRepo.offlineMode = true]
    C{BookRepository checks offlineMode}
    D[Return onlyCachedBooks]
    E[Return allBooks]
    F[UI shows only downloaded books<br/>Network requests blocked]
    G[UI shows all books<br/>Network requests allowed]
    
    A --> B
    B --> C
    C -->|true| D
    C -->|false| E
    D --> F
    E --> G
    
    style A fill:#e1f5ff
    style C fill:#fff4e1
    style F fill:#e8f5e9
    style G fill:#e8f5e9
```

## Threading Model

```mermaid
graph TB
    subgraph Main Thread - UI
        A[View rendering]
        B[User input]
        C[StateFlow collection]
        D[ViewModel access]
    end
    
    subgraph Dispatchers.IO - Background
        E[Network calls]
        F[Database operations]
        G[File I/O]
        H[Heavy processing]
    end
    
    I[Coroutine launches]
    
    I --> A
    I --> B
    I --> C
    I --> D
    I -.switches to.-> E
    I -.switches to.-> F
    I -.switches to.-> G
    I -.switches to.-> H
    
    E -.results back.-> I
    F -.results back.-> I
    G -.results back.-> I
    H -.results back.-> I
    
    style A fill:#ffebee
    style B fill:#ffebee
    style C fill:#ffebee
    style D fill:#ffebee
    style E fill:#e8f5e9
    style F fill:#e8f5e9
    style G fill:#e8f5e9
    style H fill:#e8f5e9
```

## Typical User Flow

```mermaid
flowchart TD
    A[1. App Launch<br/>ChronicleApplication.onCreate]
    B[Hilt builds the DI graph<br/>Setup Coil, Timber]
    C{2. Check Login State}
    D[LoginDestination]
    E[3. MainActivity.setContent]
    F[Setup bottom navigation<br/>Show HomeDestination<br/>Initialize MediaServiceConnection]
    G[4. Browse Books<br/>HomeDestination or LibraryDestination]
    H[Load books from Repository<br/>Display in LazyColumn / LazyVerticalGrid]
    I[5. Select Book<br/>Navigate to DetailsDestination]
    J[Load book details<br/>Show chapters, metadata]
    K[6. Play Book<br/>ViewModel → MediaServiceConnection → MediaPlayerService]
    L[Service loads tracks<br/>ExoPlayer starts playback<br/>Mini player appears]
    M[7. Background Playback<br/>Service keeps running<br/>Notification shows controls<br/>Progress auto-saved<br/>Synced to Plex]
    N[8. Download Book - optional<br/>CachedFileManager downloads tracks<br/>Book marked as cached<br/>Available offline]
    
    A --> B
    B --> C
    C -->|Not logged in| D
    C -->|Logged in| E
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
    J --> K
    K --> L
    L --> M
    L -.optional.-> N
    
    style A fill:#e3f2fd
    style C fill:#fff4e1
    style E fill:#e1f5ff
    style K fill:#ffebee
    style M fill:#e8f5e9
```

## Quick Reference: Where to Find Things

| I want to...                | Look in...                              |
|-----------------------------|-----------------------------------------|
| Add a new screen            | `features/newfeature/`                  |
| Modify book data            | `data/local/BookRepository.kt`          |
| Change Plex API calls       | `data/sources/plex/PlexService.kt`      |
| Modify playback logic       | `features/player/MediaPlayerService.kt` |
| Add a setting               | `features/settings/SettingsViewModel.kt` (`settingsRows`) + `SharedPreferencesPrefsRepo.kt` |
| Change UI layout            | `features/*/compose/`                   |
| Add dependency injection    | `injection/` (Hilt modules)              |
| Modify navigation           | `navigation/Screens.kt` (screen keys) + `navigation/circuit/ChronicleCircuit.kt` (graph) |
| Change app initialization   | `application/ChronicleApplication.kt`   |
| Add database table/field    | one of **five** DBs in `data/local/` — chapters live in `ChapterDatabase`, bookmarks in `BookmarkDatabase` |


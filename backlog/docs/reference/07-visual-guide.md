# Visual Architecture Guide

This document provides visual representations of Chronicle's architecture for quick reference.

## App Structure Overview

```mermaid
graph TB
    subgraph Chronicle App
        subgraph UI Layer - Views
            A[Home<br/>Fragment]
            B[Library<br/>Fragment]
            C[Book Details<br/>Fragment]
            D[Player<br/>Fragment]
        end
        
        subgraph ViewModel Layer
            E[HomeViewModel]
            F[LibraryViewModel]
            G[PlayerViewModel]
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
        
        A -->|observes LiveData| E
        B -->|observes LiveData| F
        C -->|observes LiveData| E
        D -->|observes LiveData| G
        
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
    participant View as VIEW (Fragment)<br/>• Displays UI<br/>• Handles input<br/>• Observes LiveData
    participant VM as VIEWMODEL<br/>• Holds UI state<br/>• Business logic<br/>• Survives config changes
    participant Repo as REPOSITORY<br/>• Single source of truth<br/>• Manages data sources<br/>• Decides network vs cache
    participant DS as DATA SOURCES<br/>Plex API / Room / File System
    
    User->>View: (1) Tap button
    View->>VM: (2) Call ViewModel method
    VM->>Repo: (3) Call Repository method
    Repo->>DS: (4) Fetch data
    DS-->>Repo: (5) Return data
    Repo-->>VM: Data flows back
    VM-->>View: (6) Update UI via LiveData
    View->>User: Display updated UI
```

## Dependency Injection Hierarchy

```mermaid
graph TB
    subgraph ChronicleApplication - @Singleton
        A[AppComponent<br/>Lifetime: Entire app]
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
    
    subgraph MainActivity - @ActivityScope
        B[ActivityComponent<br/>Lifetime: While Activity exists]
        B1[ViewModelFactories]
        B2[Navigator]
        B3[MediaServiceConnection]
        B --> B1
        B --> B2
        B --> B3
    end
    
    subgraph MediaPlayerService - @ServiceScope
        C[ServiceComponent<br/>Lifetime: While Service exists]
        C1[ExoPlayer]
        C2[MediaSession]
        C3[NotificationBuilder]
        C4[MediaSessionConnector]
        C --> C1
        C --> C2
        C --> C3
        C --> C4
    end
    
    A -->|creates| B
    A -->|creates| C
    
    style A fill:#e3f2fd
    style B fill:#f3e5f5
    style C fill:#e8f5e9
```

## Feature Module Structure

Each feature in `features/` follows this pattern:

```
features/home/
│
├── HomeFragment.kt
│   ├─ Inflates layout
│   ├─ Observes ViewModel LiveData
│   ├─ Handles user interactions
│   └─ Updates UI when data changes
│
├── HomeViewModel.kt
│   ├─ Holds UI state (LiveData properties)
│   ├─ Calls Repository methods
│   ├─ Transforms data for UI
│   └─ Factory for Dagger injection
│
└── (Adapters, custom views if needed)
    └─ AudiobookAdapter.kt
       └─ RecyclerView adapter for book lists
```

## Data Flow Example: Loading Books

```mermaid
sequenceDiagram
    participant User
    participant LibraryFragment
    participant LibraryViewModel
    participant BookRepository
    participant RoomDatabase
    
    User->>LibraryFragment: Opens App
    LibraryFragment->>LibraryFragment: onCreate()
    LibraryFragment->>LibraryViewModel: observe(viewModel.books)
    LibraryViewModel->>BookRepository: getAllBooks()
    BookRepository->>RoomDatabase: bookDao.getAllBooks()
    Note over RoomDatabase: Query: SELECT * FROM Audiobook<br/>Returns: LiveData
    Note over RoomDatabase: Room automatically emits data
    RoomDatabase-->>LibraryFragment: Observer triggered
    LibraryFragment->>LibraryFragment: adapter.submitList()
    LibraryFragment->>User: UI Updated! ✓
```

## Media Playback Architecture

```mermaid
graph TB
    A[Android System<br/>Bluetooth, Notifications, Android Auto]
    
    subgraph MediaPlayerService - Background Service
        B[MediaSession<br/>Android media framework integration]
        C[MediaSessionConnector<br/>Connects ExoPlayer to MediaSession]
        D[ExoPlayer<br/>Actual audio playback]
        E[NotificationBuilder<br/>Shows now playing notification]
    end
    
    F[MediaServiceConnection<br/>Bridge between Service and UI]
    G[CurrentlyPlayingFragment<br/>Mini player and full player UI]
    
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
        C[LiveData observations]
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
    B[Initialize Dagger<br/>Setup Fresco, Timber]
    C{2. Check Login State}
    D[LoginFragment]
    E[3. MainActivity]
    F[Setup bottom navigation<br/>Show HomeFragment<br/>Initialize MediaServiceConnection]
    G[4. Browse Books<br/>HomeFragment or LibraryFragment]
    H[Load books from Repository<br/>Display in RecyclerView]
    I[5. Select Book<br/>Navigate to AudiobookDetailsFragment]
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
| Add a setting               | `data/local/PrefsRepo.kt`               |
| Change UI layout            | `res/layout/`                           |
| Add dependency injection    | `injection/`                            |
| Modify navigation           | `navigation/Navigator.kt`               |
| Change app initialization   | `application/ChronicleApplication.kt`   |
| Add database table/field    | `data/local/BookDatabase.kt`            |


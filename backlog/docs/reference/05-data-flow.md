# Data Flow

Understanding how data moves through Chronicle will help you implement features correctly.

## Overview

Chronicle uses a **unidirectional data flow** pattern:

```mermaid
graph LR
    A[User Action] --> B[View]
    B --> C[ViewModel]
    C --> D[Repository]
    D --> E[Data Source]
    E --> D
    D --> C
    C --> B
    B --> F[View Update]
    
    style A fill:#ffebee
    style B fill:#e1f5ff
    style C fill:#fff4e1
    style D fill:#e8f5e9
    style E fill:#f3e5f5
```

This keeps data flow predictable and makes debugging easier.

## Key Concepts

### LiveData
- **Observable** data holder
- **Lifecycle-aware**: Automatically stops sending updates when UI is inactive
- **Main thread**: Always delivers updates on the UI thread
- ViewModels expose LiveData, Views observe it

### Coroutines
- Handle **asynchronous** operations (network, database, file I/O)
- Use **Dispatchers**:
  - `Dispatchers.Main` - UI thread
  - `Dispatchers.IO` - Background I/O operations
  - `Dispatchers.Default` - CPU-intensive work

## Common Data Flow Patterns

### 1. Displaying a List of Books

```mermaid
sequenceDiagram
    participant User
    participant LibraryFragment
    participant LibraryViewModel
    participant BookRepository
    participant RoomDatabase
    
    User->>LibraryFragment: Opens library screen
    LibraryFragment->>LibraryViewModel: onCreate()
    Note over LibraryViewModel: books = bookRepository.getAllBooks()
    LibraryViewModel->>BookRepository: getAllBooks()
    BookRepository->>RoomDatabase: bookDao.getAllBooks()
    RoomDatabase-->>BookRepository: LiveData<List<Audiobook>>
    BookRepository-->>LibraryViewModel: LiveData
    Note over RoomDatabase: Data changes, emits update
    RoomDatabase-->>LibraryFragment: Observer callback triggered
    LibraryFragment->>LibraryFragment: Update RecyclerView
```

**Code example**:
```kotlin
// In LibraryFragment
viewModel.books.observe(viewLifecycleOwner) { books ->
    adapter.submitList(books)  // Update UI
}

// In LibraryViewModel
val books: LiveData<List<Audiobook>> = bookRepository.getAllBooks()

// In BookRepository
fun getAllBooks(): LiveData<List<Audiobook>> {
    return bookDao.getAllBooks()  // Room automatically updates this
}
```

### 2. Refreshing Data from Server

```mermaid
sequenceDiagram
    participant User
    participant HomeFragment
    participant HomeViewModel
    participant BookRepository
    participant PlexService
    participant RoomDatabase
    
    User->>HomeFragment: Pull to refresh
    HomeFragment->>HomeViewModel: refreshData()
    Note over HomeViewModel: viewModelScope.launch
    HomeViewModel->>BookRepository: refreshData()
    Note over BookRepository: Coroutine on IO thread
    BookRepository->>PlexService: getAlbums()
    PlexService-->>BookRepository: List<Audiobook>
    BookRepository->>RoomDatabase: insertAll(books)
    RoomDatabase-->>BookRepository: Data saved
    Note over RoomDatabase: LiveData automatically notified
    RoomDatabase-->>HomeFragment: Observer triggered
    HomeFragment->>User: UI updates with new data
```

**Code example**:
```kotlin
// In HomeFragment
binding.swipeToRefresh.setOnRefreshListener {
    viewModel.refreshData()
}

viewModel.isRefreshing.observe(viewLifecycleOwner) { isRefreshing ->
    binding.swipeToRefresh.isRefreshing = isRefreshing
}

// In HomeViewModel
fun refreshData() {
    viewModelScope.launch {
        _isRefreshing.value = true
        bookRepository.refreshData()  // Suspending function
        _isRefreshing.value = false
    }
}

// In BookRepository
suspend fun refreshData() = withContext(Dispatchers.IO) {
    val books = plexService.getAlbums()  // Network call
    bookDao.insertAll(books)  // Save to DB
}
```

### 3. Playing an Audiobook

```mermaid
sequenceDiagram
    participant User
    participant AudiobookDetailsFragment
    participant AudiobookDetailsViewModel
    participant MediaServiceConnection
    participant MediaPlayerService
    participant TrackRepository
    participant CurrentlyPlayingFragment

    User->>AudiobookDetailsFragment: Tap play button
    AudiobookDetailsFragment->>AudiobookDetailsViewModel: play()
    AudiobookDetailsViewModel->>MediaServiceConnection: play(bookId)
    MediaServiceConnection->>MediaPlayerService: transportControls.play()
    MediaPlayerService->>TrackRepository: Get tracks for book
    TrackRepository-->>MediaPlayerService: List<MediaItemTrack>
    MediaPlayerService->>MediaPlayerService: Prepare ExoPlayer
    MediaPlayerService->>MediaPlayerService: Start playback
    MediaPlayerService->>CurrentlyPlayingFragment: Update state & broadcast
    CurrentlyPlayingFragment->>User: Show playing book in mini player
```

### 4. Saving Playback Progress

```mermaid
sequenceDiagram
    participant MediaPlayerService
    participant BookRepository
    participant RoomDatabase
    participant UIComponents
    participant PlexService

    Note over MediaPlayerService: Audio playing...
    MediaPlayerService->>MediaPlayerService: Every 5 seconds
    MediaPlayerService->>BookRepository: updateProgress(bookId, time, progress)
    Note over BookRepository: Launch coroutine
    BookRepository->>RoomDatabase: Update progress column
    RoomDatabase-->>UIComponents: LiveData notifies observers
    UIComponents->>UIComponents: Progress bars update
    par Background sync
        BookRepository->>PlexService: scrobble(bookId, progress)
        PlexService-->>BookRepository: Success/Error
    end
```

**Code example**:
```kotlin
// In MediaPlayerService
private fun updateProgress() {
    val position = player.currentPosition
    val bookId = currentlyPlaying.audiobook.id
    
    serviceScope.launch {
        bookRepository.updateProgress(
            bookId = bookId,
            currentTime = System.currentTimeMillis(),
            progress = position
        )
    }
}

// In BookRepository
suspend fun updateProgress(bookId: Int, currentTime: Long, progress: Long) {
    withContext(Dispatchers.IO) {
        // Update local database
        bookDao.updateProgress(bookId, currentTime, progress)
        
        // Sync to Plex (fire and forget)
        launch {
            try {
                plexService.scrobble(bookId, progress)
            } catch (e: Exception) {
                Timber.e(e, "Failed to scrobble")
            }
        }
    }
}
```

### 5. Downloading an Audiobook

```mermaid
sequenceDiagram
    participant User
    participant AudiobookDetailsFragment
    participant AudiobookDetailsViewModel
    participant CachedFileManager
    participant FetchLibrary
    participant BookRepository
    participant RoomDatabase
    
    User->>AudiobookDetailsFragment: Tap download button
    AudiobookDetailsFragment->>AudiobookDetailsViewModel: downloadBook()
    AudiobookDetailsViewModel->>CachedFileManager: downloadBook(book)
    CachedFileManager->>FetchLibrary: Queue downloads for all tracks
    Note over FetchLibrary: Download files in background
    loop Progress updates
        FetchLibrary-->>CachedFileManager: Progress callbacks
        CachedFileManager-->>AudiobookDetailsViewModel: LiveData<DownloadProgress>
        AudiobookDetailsViewModel-->>AudiobookDetailsFragment: Update progress bar
    end
    Note over FetchLibrary: Downloads complete
    CachedFileManager->>BookRepository: markAsCached(bookId)
    BookRepository->>RoomDatabase: Update isCached = true
    RoomDatabase-->>AudiobookDetailsFragment: LiveData updates
    AudiobookDetailsFragment->>User: Show delete button
```

## State Management

### ViewModel State

ViewModels hold UI state using:
- **LiveData**: For observable data (lists, loading states)
- **MutableLiveData**: Internal mutable version
- **Private setters**: Only ViewModel can change state

```kotlin
class HomeViewModel : ViewModel() {
    // Private mutable version
    private val _isRefreshing = MutableLiveData(false)
    
    // Public immutable version exposed to UI
    val isRefreshing: LiveData<Boolean>
        get() = _isRefreshing
    
    fun refreshData() {
        _isRefreshing.value = true
        // ... do work ...
        _isRefreshing.value = false
    }
}
```

### Repository State

Repositories manage data state:
- **Database**: Persistent state (cached data)
- **Memory Cache**: Temporary state (in-memory variables)
- **Network**: Remote state (Plex server)

```kotlin
class BookRepository {
    // Database = source of truth
    fun getAllBooks(): LiveData<List<Audiobook>> {
        return bookDao.getAllBooks()
    }
    
    // Network updates database
    suspend fun refreshData() {
        val books = plexService.getAlbums()
        bookDao.insertAll(books)  // Database auto-notifies observers
    }
}
```

## Threading Rules

1. **UI Operations**: Must be on Main thread
   - Updating Views
   - LiveData observations
   - ViewModel property access

2. **Database Operations**: Use IO thread
   - Room queries (except LiveData, which handles threading automatically)
   - File operations

3. **Network Operations**: Use IO thread
   - Retrofit calls
   - HTTP requests

4. **Heavy Computation**: Use Default thread
   - Processing large data
   - Complex calculations

**Example**:
```kotlin
viewModelScope.launch {  // Runs on Main by default
    _isLoading.value = true
    
    val result = withContext(Dispatchers.IO) {  // Switch to IO thread
        repository.fetchData()  // Network/DB operation
    }
    
    // Back on Main thread
    _data.value = result
    _isLoading.value = false
}
```

## Error Handling

Errors flow back through the same path:

```
Data Source → Repository (catch, log, transform) → ViewModel (handle, show to user) → View (display error)
```

**Example**:
```kotlin
// In Repository
suspend fun refreshData(): Result<Unit> {
    return try {
        val books = plexService.getAlbums()
        bookDao.insertAll(books)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "Failed to refresh")
        Result.failure(e)
    }
}

// In ViewModel
fun refreshData() {
    viewModelScope.launch {
        when (val result = repository.refreshData()) {
            is Result.Success -> {
                _message.value = Event("Refreshed successfully")
            }
            is Result.Failure -> {
                _message.value = Event("Failed to refresh: ${result.error.message}")
            }
        }
    }
}
```

## Offline Mode

Chronicle handles offline mode through the Repository pattern:

```kotlin
// Repository decides source based on offline mode
fun getAllBooks(): LiveData<List<Audiobook>> {
    return if (prefsRepo.offlineMode) {
        bookDao.getCachedBooks()  // Only cached books
    } else {
        bookDao.getAllBooks()  // All books
    }
}
```

## Data Flow Best Practices

1. **Single Source of Truth**: Repository is always the source of truth, not the ViewModel
2. **Unidirectional Flow**: Data always flows down (Repository → ViewModel → View)
3. **Events go up**: User actions flow up (View → ViewModel → Repository)
4. **No direct DB access**: ViewModels never access database directly, always through Repository
5. **Async in Repository**: All async work happens in Repository, ViewModels just call suspend functions
6. **LiveData for UI**: Always use LiveData to expose data to UI
7. **Coroutines for work**: Use coroutines for async operations

## Summary

- **Data down**: Repository → ViewModel → View (via LiveData)
- **Events up**: View → ViewModel → Repository (via method calls)
- **Async**: Use coroutines in Repositories
- **Thread-safe**: Room and LiveData handle threading
- **Single source**: Database is source of truth
- **Reactive**: UI automatically updates when data changes


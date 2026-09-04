package io.github.mattpvaughn.chronicle.features.home

import android.content.SharedPreferences
import android.os.Bundle
import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.search.SearchController
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.booksKey
import io.github.mattpvaughn.chronicle.util.combineDistinct
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class HomeViewModel(
  private val plexConfig: PlexConfig,
  private val bookRepository: IBookRepository,
  private val librarySyncRepository: LibrarySyncRepository,
  private val prefsRepo: PrefsRepo,
  private val mediaServiceConnection: MediaServiceConnection,
  private val exceptionHandler: CoroutineExceptionHandler,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val plexConfig: PlexConfig,
      private val bookRepository: IBookRepository,
      private val librarySyncRepository: LibrarySyncRepository,
      private val prefsRepo: PrefsRepo,
      private val mediaServiceConnection: MediaServiceConnection,
      private val exceptionHandler: CoroutineExceptionHandler,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
          return HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
            mediaServiceConnection,
            exceptionHandler,
          ) as T
        } else {
          throw IllegalArgumentException(
            "Cannot instantiate $modelClass from HomeViewModel.Factory",
          )
        }
      }
    }

  private val _offlineMode = MutableStateFlow(prefsRepo.offlineMode)
  val offlineMode: StateFlow<Boolean>
    get() = _offlineMode

  /**
   * The shelves are deduped at the source (cu-110).
   *
   * Each of these is a Room `LiveData` on the `Audiobook` table, and Room invalidates per table —
   * so `ProgressUpdater`'s once-a-second write during playback re-emitted all three, and each
   * emission rebuilt the list and deserialized `Audiobook.chapters` for every book in it. Measured
   * with the player sheet open over Home: 88% janky frames, main thread at ~24% of a core
   * continuously, a GC every ~4s freeing ~165,000 objects, and taps and back presses dropped.
   *
   * [distinctBy] with [booksKey] stops the emission when nothing the UI draws has changed, which
   * is the common case at tick rate. It keys on progress as well as identity, so a genuine
   * progress change still propagates — dropping that is what froze `LibraryViewModel`'s bars.
   */
  private val recentlyListenedSource =
    bookRepository.getRecentlyListened().distinctUntilChangedBy { it.booksKey() }

  val recentlyListened: StateFlow<List<Audiobook>> =
    combineDistinct(recentlyListenedSource, _offlineMode) { recents, offline ->
      if (offline) recents.filter { it.isCached } else recents
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val isRefreshing = librarySyncRepository.isRefreshing

  /** A refresh failure, surfaced by the fragment. Raised off the main thread, so it is an
   *  event carrying a string resource rather than a `Toast` (which would throw there). */
  val syncError = librarySyncRepository.errorMessage

  private val _messageForUser = MutableStateFlow<Event<String>?>(null)
  val messageForUser: StateFlow<Event<String>?>
    get() = _messageForUser

  /**
   * A resume that could not start, as a string resource.
   *
   * An `Int` rather than a `String` because this is raised from a ViewModel with no `Context`, and
   * user-facing text belongs in `strings.xml` — the same shape as
   * `LibrarySyncRepository.errorMessage`.
   */
  private val _resumeError = MutableStateFlow<Event<Int>?>(null)
  val resumeError: StateFlow<Event<Int>?>
    get() = _resumeError

  val recentlyAdded: StateFlow<List<Audiobook>> =
    combineDistinct(
      bookRepository.getRecentlyAdded().distinctUntilChangedBy { it.booksKey() },
      _offlineMode,
    ) { recents, offline ->
      // We only want books which have actually been listened to!
      if (offline) recents.filter { it.isCached } else recents
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val downloaded: StateFlow<List<Audiobook>> =
    bookRepository
      .getCachedAudiobooks()
      .distinctUntilChangedBy { it.booksKey() }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  private val _isSearchActive = MutableStateFlow(false)
  val isSearchActive: StateFlow<Boolean>
    get() = _isSearchActive

  /** Typo-tolerant grouped search, shared with the library and collections screens (cu-25). */
  private val searchController = SearchController(bookRepository, viewModelScope)

  val searchRows: StateFlow<List<SearchRow>>
    get() = searchController.rows

  val isQueryEmpty: StateFlow<Boolean>
    get() = searchController.isQueryEmpty

  /**
   * A `SharedPreferences` listener fires on whichever thread called `apply()`, and a settings
   * *import* writes this key off the main thread (`SettingsBackup.BACKUP_SETTING_KEYS` includes
   * it). That is why the `MutableLiveData` this replaces had to use `postValue` and carried an
   * explicit carve-out; assigning a `MutableStateFlow` is thread-safe, so the exception is gone.
   */
  private val offlineModeListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      when (key) {
        PrefsRepo.KEY_OFFLINE_MODE -> _offlineMode.value = prefsRepo.offlineMode
        else -> { // Do nothing
        }
      }
    }

  private suspend fun refreshIfStale() {
    val millisSinceLastRefresh = System.currentTimeMillis() - prefsRepo.lastRefreshTimeStamp
    val minutesSinceLastRefresh = millisSinceLastRefresh / 1000 / 60
    val bookCount = bookRepository.getBookCount()
    val shouldRefresh = minutesSinceLastRefresh > prefsRepo.refreshRateMinutes || bookCount == 0
    Timber.i(
      "$minutesSinceLastRefresh minutes since last libraryrefresh,${prefsRepo.refreshRateMinutes} needed",
    )
    if (shouldRefresh) {
      refreshData()
    }
  }

  init {
    Timber.i("HomeViewModel init")
    // No "if already connected, call it once" special case any more: `isConnected` is a StateFlow,
    // so the collector below is handed the current value immediately. The LiveData version needed
    // that branch because `observeForever` also delivers at once — it ran the handler *twice* when
    // already connected, which is a duplicate refresh rather than a missing one.
    viewModelScope.launch(exceptionHandler) {
      plexConfig.isConnected.collect { isConnectedToServer ->
        if (isConnectedToServer) {
          refreshIfStale()
        }
      }
    }
    prefsRepo.registerPrefsListener(offlineModeListener)
  }

  override fun onCleared() {
    prefsRepo.unregisterPrefsListener(offlineModeListener)
    super.onCleared()
  }

  /**
   * Resume [audiobook] from its saved position, without going through the details screen.
   *
   * This is what makes the Continue Listening shelf worth having: a shelf whose whole premise is
   * "carry on where you left off" should not need a second screen and a second tap to do it
   * (cu-18). Details is still reachable by long-pressing the same cover.
   *
   * `USE_SAVED_TRACK_PROGRESS` rather than an offset we compute here — the service owns resolving
   * the saved position from the tracks, and duplicating that resolution is the mistake cu-136 was
   * about.
   */
  fun resume(audiobook: Audiobook) {
    if (plexConfig.isConnected.value != true && !audiobook.isCached) {
      // Main thread: `resume` is a click handler. See `setSearchActive` above.
      _resumeError.value = Event(R.string.cannot_play_media_no_server)
      return
    }

    val play = {
      mediaServiceConnection.transportControls?.playFromMediaId(
        audiobook.id,
        Bundle().apply { putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS) },
      )
      Unit
    }
    if (mediaServiceConnection.isConnected.value != true) {
      mediaServiceConnection.connect(onConnected = play)
    } else {
      play()
    }
  }

  fun setSearchActive(isSearchActive: Boolean) {
    // `value =`, not `postValue` (cu-52). Called from a click listener, so this is already the main
    // thread — and `postValue` coalesces, so two toggles in one frame collapse to one while the
    // `searchController` call below runs twice. The flag and the controller would then disagree
    // about whether search is open, which is the shape of the three device races in cu-73.
    _isSearchActive.value = isSearchActive
    searchController.setSearchActive(isSearchActive)
  }

  fun disableOfflineMode() {
    prefsRepo.offlineMode = false
  }

  /** Searches for books which match the provided text, typo-tolerantly and grouped (cu-25). */
  fun search(query: String) {
    searchController.search(query)
  }

  /**
   * Pull most recent data from server and update repositories.
   *
   * Update book info for fields where child tracks serve as source of truth, like how
   * [Audiobook.duration] serves as a delegate for [List<MediaItemTrack>.getDuration()]
   *
   * TODO: migrate to [MainActivityViewModel] so code isn't duplicated b/w [HomeViewModel] and
   * [LibraryViewModel]
   */
  fun refreshData() {
    librarySyncRepository.refreshLibrary()
  }
}

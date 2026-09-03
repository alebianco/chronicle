package io.github.mattpvaughn.chronicle.features.home

import android.content.SharedPreferences
import android.os.Bundle
import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.Injector
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
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.booksKey
import io.github.mattpvaughn.chronicle.util.distinctBy
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class HomeViewModel(
  private val plexConfig: PlexConfig,
  private val bookRepository: IBookRepository,
  private val librarySyncRepository: LibrarySyncRepository,
  private val prefsRepo: PrefsRepo,
  private val mediaServiceConnection: MediaServiceConnection,
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
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
          return HomeViewModel(
            plexConfig,
            bookRepository,
            librarySyncRepository,
            prefsRepo,
            mediaServiceConnection,
          ) as T
        } else {
          throw IllegalArgumentException(
            "Cannot instantiate $modelClass from HomeViewModel.Factory",
          )
        }
      }
    }

  private var _offlineMode = MutableLiveData(prefsRepo.offlineMode)
  val offlineMode: LiveData<Boolean>
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
    bookRepository.getRecentlyListened().distinctBy { it.booksKey() }

  val recentlyListened =
    DoubleLiveData(recentlyListenedSource, _offlineMode) { recents, offline ->
      return@DoubleLiveData if (offline == true) {
        recents?.filter { it.isCached }
      } else {
        recents
      } ?: emptyList()
    }

  val isRefreshing = librarySyncRepository.isRefreshing

  /** A refresh failure, surfaced by the fragment. Raised off the main thread, so it is an
   *  event carrying a string resource rather than a `Toast` (which would throw there). */
  val syncError = librarySyncRepository.errorMessage

  private var _messageForUser = MutableLiveData<Event<String>>()
  val messageForUser: LiveData<Event<String>>
    get() = _messageForUser

  /**
   * A resume that could not start, as a string resource.
   *
   * An `Int` rather than a `String` because this is raised from a ViewModel with no `Context`, and
   * user-facing text belongs in `strings.xml` — the same shape as
   * `LibrarySyncRepository.errorMessage`.
   */
  private val _resumeError = MutableLiveData<Event<Int>>()
  val resumeError: LiveData<Event<Int>>
    get() = _resumeError

  var recentlyAdded: DoubleLiveData<List<Audiobook>, Boolean, List<Audiobook>> =
    DoubleLiveData(
      bookRepository.getRecentlyAdded().distinctBy { it.booksKey() },
      _offlineMode,
    ) { recents, offline ->
      /** We only want books which have actually been listened to! */
      if (offline == true) {
        return@DoubleLiveData recents?.filter { book -> book.isCached } ?: emptyList()
      } else {
        return@DoubleLiveData recents ?: emptyList()
      }
    }

  val downloaded: LiveData<List<Audiobook>> =
    bookRepository.getCachedAudiobooks().distinctBy { it.booksKey() }

  private var _isSearchActive = MutableLiveData<Boolean>()
  val isSearchActive: LiveData<Boolean>
    get() = _isSearchActive

  /** Typo-tolerant grouped search, shared with the library and collections screens (cu-25). */
  private val searchController = SearchController(bookRepository, viewModelScope)

  val searchRows: LiveData<List<SearchRow>>
    get() = searchController.rows

  val isQueryEmpty: LiveData<Boolean>
    get() = searchController.isQueryEmpty

  private val offlineModeListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      when (key) {
        PrefsRepo.KEY_OFFLINE_MODE -> _offlineMode.postValue(prefsRepo.offlineMode)
        else -> { // Do nothing
        }
      }
    }

  private val serverConnectionObserver =
    Observer<Boolean> { isConnectedToServer ->
      if (isConnectedToServer) {
        viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
          val millisSinceLastRefresh =
            System.currentTimeMillis() - prefsRepo.lastRefreshTimeStamp
          val minutesSinceLastRefresh = millisSinceLastRefresh / 1000 / 60
          val bookCount = bookRepository.getBookCount()
          val shouldRefresh =
            minutesSinceLastRefresh > prefsRepo.refreshRateMinutes || bookCount == 0
          Timber.i(
            "$minutesSinceLastRefresh minutes since last libraryrefresh,${prefsRepo.refreshRateMinutes} needed",
          )
          if (shouldRefresh) {
            refreshData()
          }
        }
      }
    }

  init {
    Timber.i("HomeViewModel init")
    if (plexConfig.isConnected.value == true) {
      // if already connected, call it just once
      serverConnectionObserver.onChanged(true)
    }
    plexConfig.isConnected.observeForever(serverConnectionObserver)
    prefsRepo.registerPrefsListener(offlineModeListener)
  }

  override fun onCleared() {
    plexConfig.isConnected.removeObserver(serverConnectionObserver)
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
      _resumeError.postValue(Event(R.string.cannot_play_media_no_server))
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
    _isSearchActive.postValue(isSearchActive)
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

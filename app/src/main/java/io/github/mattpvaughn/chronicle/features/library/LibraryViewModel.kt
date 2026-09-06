package io.github.mattpvaughn.chronicle.features.library

import android.content.Context
import android.content.SharedPreferences
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_BOOK_SORT_BY
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_HIDE_PLAYED_AUDIOBOOKS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_IS_LIBRARY_SORT_DESCENDING
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_LIBRARY_VIEW_STYLE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_OFFLINE_MODE
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_AUTHOR
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_DATE_ADDED
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_DATE_PLAYED
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_DURATION
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_PLAYS
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_TITLE
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_YEAR
import io.github.mattpvaughn.chronicle.data.model.BookSortComparators
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus.CACHED
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus.NOT_CACHED
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryContent
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryUiState
import io.github.mattpvaughn.chronicle.features.search.SearchController
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.booksKey
import io.github.mattpvaughn.chronicle.util.booleanFlow
import io.github.mattpvaughn.chronicle.util.bytesAvailable
import io.github.mattpvaughn.chronicle.util.combineDistinct
import io.github.mattpvaughn.chronicle.util.combineDistinctAsync
import io.github.mattpvaughn.chronicle.util.stringFlow
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserListener
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString.ResourceString
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class LibraryViewModel(
  private val bookRepository: IBookRepository,
  private val trackRepository: ITrackRepository,
  private val prefsRepo: PrefsRepo,
  private val cachedFileManager: ICachedFileManager,
  private val librarySyncRepository: LibrarySyncRepository,
  sharedPreferences: SharedPreferences,
  private val exceptionHandler: CoroutineExceptionHandler,
  private val appContext: Context,
  private val dispatchers: DispatcherProvider,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val trackRepository: ITrackRepository,
      private val prefsRepo: PrefsRepo,
      private val cachedFileManager: ICachedFileManager,
      private val librarySyncRepository: LibrarySyncRepository,
      private val sharedPreferences: SharedPreferences,
      private val exceptionHandler: CoroutineExceptionHandler,
      private val appContext: Context,
      private val dispatchers: DispatcherProvider,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
          return LibraryViewModel(
            bookRepository,
            trackRepository,
            prefsRepo,
            cachedFileManager,
            librarySyncRepository,
            sharedPreferences,
            exceptionHandler,
            appContext,
            dispatchers,
          ) as T
        } else {
          throw IllegalArgumentException(
            "Cannot instantiate $modelClass from LibraryViewModel.Factory",
          )
        }
      }
    }

  val isRefreshing = librarySyncRepository.isRefreshing

  /** A refresh failure, surfaced by the fragment. Raised off the main thread, so it is an
   *  event carrying a string resource rather than a `Toast` (which would throw there). */
  val syncError = librarySyncRepository.errorMessage

  private var _isSearchActive = MutableStateFlow(false)
  val isSearchActive: StateFlow<Boolean>
    get() = _isSearchActive

  val viewStyle =
    sharedPreferences.stringFlow(
      KEY_LIBRARY_VIEW_STYLE,
      prefsRepo.libraryBookViewStyle,
    )

  private var _isFilterShown = MutableStateFlow(false)
  val isFilterShown: StateFlow<Boolean>
    get() = _isFilterShown

  val isSortDescending =
    sharedPreferences.booleanFlow(
      KEY_IS_LIBRARY_SORT_DESCENDING,
      true,
    )

  val arePlayedAudiobooksHidden =
    sharedPreferences.booleanFlow(
      KEY_HIDE_PLAYED_AUDIOBOOKS,
      false,
    )

  private val sortKey =
    sharedPreferences.stringFlow(KEY_BOOK_SORT_BY, SORT_KEY_TITLE)
  val isOffline = sharedPreferences.booleanFlow(KEY_OFFLINE_MODE, false)

  // Deduped at the source: this is the *whole library*, and Room re-emits it on every write to
  // the Audiobook table — once a second during playback. Without this the sort and filter below
  // ran per tick over every book, scaling with library size rather than with what changed
  // (cu-110, and the mechanism behind cu-51).
  private val allBooks = bookRepository.getAllBooks().distinctUntilChangedBy { it.booksKey() }
  val books: Flow<List<Audiobook>> =
    combineDistinctAsync(
      allBooks,
      isSortDescending,
      sortKey,
      arePlayedAudiobooksHidden,
      isOffline,
      dispatchers.io,
    ) { books, desc, key, hidePlayed, offline ->
      if (books.isEmpty()) {
        emptyList()
      } else {
        books.filter {
          (!offline || it.isCached && offline) && (!hidePlayed || hidePlayed && it.viewCount == 0L)
        }
          .sortedWith(
            Comparator { book1, book2 ->
              val descMultiplier = if (desc) 1 else -1
              return@Comparator descMultiplier *
                when (key) {
                  // Surname-first, so "Brandon Sanderson" files under S (#21).
                  SORT_KEY_AUTHOR ->
                    BookSortComparators.authorSortKey(book1.author)
                      .compareTo(BookSortComparators.authorSortKey(book2.author))
                  // Natural ordering, so "Book 2" precedes "Book 10" (#21).
                  SORT_KEY_TITLE ->
                    BookSortComparators.compareTitlesNaturally(book1.titleSort, book2.titleSort)
                  SORT_KEY_PLAYS -> book2.viewedLeafCount.compareTo(book1.viewedLeafCount)
                  SORT_KEY_DURATION -> book2.duration.compareTo(book1.duration)
                  // Note: Reverse order for timestamps, because most recent should be at the top
                  // of descending, even though the timestamp is larger
                  SORT_KEY_DATE_ADDED -> book2.addedAt.compareTo(book1.addedAt)
                  SORT_KEY_DATE_PLAYED -> book2.lastViewedAt.compareTo(book1.lastViewedAt)
                  SORT_KEY_YEAR -> book2.year.compareTo(book1.year)
                  else -> throw NoWhenBranchMatchedException("Unknown sort key: $key")
                }
            },
          )
      }
    }
      // Keyed on `booksKey()` — id, cached and progress — not on the list's own `equals`. Ids alone
      // made the old hand-rolled version hold the *stale* list for any change that kept the same
      // books, so a book's progress bar in the library never moved (cu-110). This replaces the
      // `prevBooks` field that version compared by hand.
      .distinctUntilChangedBy { it.booksKey() }

  private var _messageForUser = MutableStateFlow<Event<String>?>(null)
  val messageForUser: StateFlow<Event<String>?>
    get() = _messageForUser

  /**
   * Typo-tolerant grouped search (cu-25).
   *
   * The four fields this used to hold by hand live in the controller now, shared with the home and
   * collections screens — they each had their own copy of the same logic.
   */
  private val searchController = SearchController(bookRepository, viewModelScope)

  val searchRows: StateFlow<List<SearchRow>>
    get() = searchController.rows

  val isQueryEmpty: StateFlow<Boolean>
    get() = searchController.isQueryEmpty

  private var _bottomChooserState = MutableStateFlow(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: StateFlow<BottomSheetChooser.BottomChooserState>
    get() = _bottomChooserState

  private var _tracks = trackRepository.getAllTracks()
  val tracks: Flow<List<MediaItemTrack>>
    get() = _tracks

  private val cacheStatus =
    tracks.map {
      when {
        it.isEmpty() -> NOT_CACHED
        it.all { track -> track.cached } -> CACHED
        it.any { track -> !track.cached } -> NOT_CACHED
        else -> NOT_CACHED
      }
    }

  fun setSearchActive(isSearchActive: Boolean) {
    _isSearchActive.value = isSearchActive
    searchController.setSearchActive(isSearchActive)
  }

  /** Searches for books which match the provided text, typo-tolerantly and grouped (cu-25). */
  fun search(query: String) {
    searchController.search(query)
  }

  fun disableOfflineMode() {
    prefsRepo.offlineMode = false
  }

  /**
   * Prompt the user with a choice to download all audiobooks on device, presenting the user
   * with information on the space available in the sync directory and the space required
   */
  fun promptDownloadAll() {
    // Calculate space available
    val bytesAvailable = prefsRepo.cachedMediaDir.bytesAvailable()

    // Calculate space required
    viewModelScope.launch(exceptionHandler) {
      val tracks =
        try {
          trackRepository.loadAllTracksAsync()
        } catch (e: Throwable) {
          Timber.e("Failed to load tracks!")
          emptyList<MediaItemTrack>()
        }
      var bytesToBeUsed = 0L
      tracks.forEach { bytesToBeUsed += it.size }
      val downloadSize =
        Formatter.formatFileSize(appContext, bytesToBeUsed)
      val availableStorage =
        Formatter.formatFileSize(appContext, bytesAvailable)
      val prompt =
        ResourceString(
          stringRes = R.string.download_all_prompt,
          placeHolderStrings = listOf(downloadSize, availableStorage),
        )

      showOptionsMenu(
        prompt,
        listOf(FormattableString.yes, FormattableString.no),
        object : BottomSheetChooser.BottomChooserItemListener() {
          override fun onItemClicked(formattableString: FormattableString) {
            when (formattableString) {
              FormattableString.yes -> downloadAll(tracks)
              FormattableString.no -> {
              }
              else -> throw NoWhenBranchMatchedException()
            }
          }
        },
      )
    }

    // Prompt user to download
  }

  private fun downloadAll(tracks: List<MediaItemTrack>) {
//        cachedFileManager.downloadTracks(tracks)
  }

  private fun showOptionsMenu(
    title: FormattableString,
    options: List<FormattableString>,
    listener: BottomChooserListener,
  ) {
    _bottomChooserState.value =
      BottomSheetChooser.BottomChooserState(
        title = title,
        options = options,
        listener = listener,
        shouldShow = true,
      )
  }

  fun refreshData() {
    librarySyncRepository.refreshLibrary()
  }

  /** Shows/hides the filter/sort/view menu to the user. Show if [isVisible] is true, hide otherwise */
  fun setFilterMenuVisible(isVisible: Boolean) {
    if (isVisible != _isFilterShown.value) {
      _isFilterShown.value = isVisible
    }
  }

  /** Toggles the direction which the library is sorted in (ascending vs. descending) */
  fun toggleSortDirection() {
    prefsRepo.isLibrarySortedDescending = !prefsRepo.isLibrarySortedDescending
  }

  /** Toggles whether to show or hide played audiobooks in the library */
  fun toggleHidePlayedAudiobooks() {
    Timber.i("toggleHidePlayedAudiobooks")
    prefsRepo.hidePlayedAudiobooks = !prefsRepo.hidePlayedAudiobooks
  }

  /**
   * Everything the library grid renders, as one value (cu-201).
   *
   * The Fragment gated three views on `books.isEmpty()` and `isOffline` through two cached locals
   * — the pattern `CollectorCachesItsValueTest` guards, because discarding a collector's emission
   * left the local on its seed and rendered "No books found" over a full library. A sealed
   * `LibraryContent` seeded to `Loading` makes that unrepresentable.
   *
   * `books` is a cold `Flow` on purpose (the O(library) sort must not run while the screen is
   * away); `WhileSubscribed` preserves that.
   */
  internal val uiState: StateFlow<LibraryUiState> =
    combineDistinct(books, isOffline, viewStyle, isRefreshing) { books, offline, style, refreshing ->
      LibraryUiState(
        content =
          when {
            books.isNotEmpty() -> LibraryContent.Loaded(books)
            offline -> LibraryContent.OfflineEmpty
            else -> LibraryContent.Empty
          },
        style = ViewStyleKind.of(style),
        isRefreshing = refreshing,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState())
}

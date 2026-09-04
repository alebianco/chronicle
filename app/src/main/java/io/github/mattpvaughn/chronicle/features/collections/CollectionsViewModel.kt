package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_BOOK_SORT_BY
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_HIDE_PLAYED_AUDIOBOOKS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_IS_LIBRARY_SORT_DESCENDING
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_LIBRARY_VIEW_STYLE
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SORT_KEY_TITLE
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.features.search.SearchController
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.util.*
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.booleanFlow
import io.github.mattpvaughn.chronicle.util.combineDistinctAsync
import io.github.mattpvaughn.chronicle.util.stringFlow
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class CollectionsViewModel(
  private val prefsRepo: PrefsRepo,
  private val librarySyncRepository: LibrarySyncRepository,
  collectionsRepository: CollectionsRepository,
  sharedPreferences: SharedPreferences,
  private val bookRepository: BookRepository,
  private val exceptionHandler: CoroutineExceptionHandler,
  private val dispatchers: DispatcherProvider,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val prefsRepo: PrefsRepo,
      private val collectionsRepository: CollectionsRepository,
      private val librarySyncRepository: LibrarySyncRepository,
      private val sharedPreferences: SharedPreferences,
      private val bookRepository: BookRepository,
      private val exceptionHandler: CoroutineExceptionHandler,
      private val dispatchers: DispatcherProvider,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollectionsViewModel::class.java)) {
          return CollectionsViewModel(
            prefsRepo,
            librarySyncRepository,
            collectionsRepository,
            sharedPreferences,
            bookRepository,
            exceptionHandler,
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
    sharedPreferences.stringFlow(
      KEY_BOOK_SORT_BY,
      SORT_KEY_TITLE,
    )

  private val allCollections = collectionsRepository.getAllCollections()

  /**
   * The sorted collections.
   *
   * `combineDistinctAsync` rather than the old `QuadLiveDataAsync`: the sort is O(n log n) and ran
   * on every Room re-emission, which is once a second during playback (cu-110). The dedup that
   * `prevCollections` used to do by hand — comparing id lists and returning the previous instance —
   * is now `distinctUntilChanged` inside the combinator, so the mutable field is gone with it.
   *
   * The nullable handling also disappears: `Flow.combine` waits for every source to emit, where the
   * LiveData version published immediately with nulls and defaulted them inline.
   */
  val collections: Flow<List<Collection>> =
    combineDistinctAsync(
      allCollections,
      isSortDescending,
      sortKey,
      arePlayedAudiobooksHidden,
      dispatchers.io,
    ) { collections, isDescending, _, _ ->
      if (collections.isEmpty()) {
        emptyList()
      } else {
        // TODO: Currently only support sort by title!
        val descMultiplier = if (isDescending) 1 else -1
        collections.sortedWith { coll1, coll2 ->
          descMultiplier * coll1.title.compareTo(coll2.title)
        }
      }
    }

  private var _messageForUser = MutableStateFlow<Event<String>?>(null)
  val messageForUser: StateFlow<Event<String>?>
    get() = _messageForUser

  /** Typo-tolerant grouped search, shared with the library and home screens (cu-25). */
  private val searchController = SearchController(bookRepository, viewModelScope)

  val searchRows: StateFlow<List<SearchRow>>
    get() = searchController.rows

  val isQueryEmpty: StateFlow<Boolean>
    get() = searchController.isQueryEmpty

  private var _bottomChooserState = MutableStateFlow(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: StateFlow<BottomSheetChooser.BottomChooserState>
    get() = _bottomChooserState

  fun setSearchActive(isSearchActive: Boolean) {
    _isSearchActive.value = isSearchActive
    searchController.setSearchActive(isSearchActive)
  }

  /** Searches for books which match the provided text, typo-tolerantly and grouped (cu-25). */
  fun search(query: String) {
    searchController.search(query)
  }

  private val serverConnectionObserver =
    Observer<Boolean> { isConnectedToServer ->
      if (isConnectedToServer) {
        viewModelScope.launch(exceptionHandler) {
          val millisSinceLastRefresh =
            System.currentTimeMillis() - prefsRepo.lastRefreshTimeStamp
          val minutesSinceLastRefresh = millisSinceLastRefresh / 1000 / 60
          val bookCount = bookRepository.getBookCount()
          val shouldRefresh =
            minutesSinceLastRefresh > prefsRepo.refreshRateMinutes || bookCount == 0
          Timber.i(
            """$minutesSinceLastRefresh minutes since last libraryrefresh,
                    |${prefsRepo.refreshRateMinutes} needed
            """.trimMargin(),
          )
          if (shouldRefresh) {
            refreshData()
          }
        }
      }
    }

  fun disableOfflineMode() {
    prefsRepo.offlineMode = false
  }

  fun refreshData() {
    librarySyncRepository.refreshLibrary()
  }

  /** Toggles whether to show or hide played audiobooks in the library */
  fun toggleHidePlayedAudiobooks() {
    Timber.i("toggleHidePlayedAudiobooks")
    prefsRepo.hidePlayedAudiobooks = !prefsRepo.hidePlayedAudiobooks
  }
}

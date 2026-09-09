package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.BrowseScreenKey
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastResEffect
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser

/**
 * The sort options, in the order the `sort_by_options` `ChipGroup` declared them.
 *
 * The keys come from `strings_no_translate.xml`, which is what made the XML's
 * `android:tag="@string/key_sort_by_title"` safe despite CLAUDE.md's warning about resource-backed
 * tags — that file is excluded from translation, so the tag could not be localised out from under
 * the lookup. Reading them through `stringResource` keeps the same single source rather than
 * re-typing eight literals here.
 */
@Composable
private fun sortOptions(): List<FilterOption> =
  listOf(
    FilterOption(stringResource(R.string.key_sort_by_title), R.string.sort_by_title),
    FilterOption(stringResource(R.string.key_sort_by_author), R.string.sort_by_author),
    FilterOption(stringResource(R.string.key_sort_by_duration), R.string.sort_by_duration),
    FilterOption(stringResource(R.string.key_sort_by_date_added), R.string.sort_by_date_added),
    FilterOption(stringResource(R.string.key_sort_by_date_played), R.string.sort_by_date_played),
    FilterOption(stringResource(R.string.key_sort_by_year), R.string.sort_by_year),
    FilterOption(stringResource(R.string.key_sort_by_rating), R.string.sort_by_rating),
    FilterOption(stringResource(R.string.key_sort_by_plays), R.string.sort_by_plays),
  )

/** The view styles, in `view_styles` `ChipGroup` order. */
@Composable
private fun viewStyleOptions(): List<FilterOption> =
  listOf(
    FilterOption(PrefsRepo.VIEW_STYLE_COVER_GRID, R.string.view_style_book_cover),
    FilterOption(PrefsRepo.VIEW_STYLE_DETAILS_LIST, R.string.view_style_cover_and_text_list),
    FilterOption(PrefsRepo.VIEW_STYLE_TEXT_LIST, R.string.view_style_text_list),
  )

/** Everything the library grid renders, plus the sink its UI posts back through. */
internal data class LibraryCircuitState(
  val ui: LibraryUiState,
  val isConnected: Boolean,
  val isRefreshing: Boolean,
  val isSearchActive: Boolean,
  val isQueryEmpty: Boolean,
  val query: String,
  val searchRows: List<SearchRow>,
  val isFilterShown: Boolean,
  val isSortDescending: Boolean,
  val hidePlayed: Boolean,
  val sortKey: String,
  val viewStyle: String,
  val eventSink: (LibraryEvent) -> Unit,
) : CircuitUiState

/**
 * Every interaction the library has — the heaviest set in the app.
 *
 * The screen it replaces carried a `SearchView`, a `SwipeRefreshLayout`, a persistent
 * `BottomSheetBehavior` filter panel with two `ChipGroup`s, and a four-item menu whose
 * `showAsAction` flags had to be juggled whenever search expanded. Eleven separate interactions,
 * which as lambdas would be eleven parameters any one of which could be left unwired.
 */
internal sealed interface LibraryEvent : CircuitUiEvent {
  data class BookOpened(val book: Audiobook) : LibraryEvent

  data object BrowseOpened : LibraryEvent

  data class SearchQueryChanged(val query: String) : LibraryEvent

  data class SearchActiveChanged(val isActive: Boolean) : LibraryEvent

  data object Refreshed : LibraryEvent

  data object OfflineModeDisabled : LibraryEvent

  data class FilterSheetShown(val isShown: Boolean) : LibraryEvent

  data class SortKeyChanged(val key: String) : LibraryEvent

  data object SortDirectionToggled : LibraryEvent

  data class ViewStyleChanged(val key: String) : LibraryEvent

  data object HidePlayedToggled : LibraryEvent
}

internal class LibraryPresenter(
  private val viewModel: @Composable () -> LibraryViewModel,
  private val prefsRepo: PrefsRepo,
  private val plexConfig: PlexConfig,
  private val navigator: Navigator,
) : Presenter<LibraryCircuitState> {
  @Composable
  override fun present(): LibraryCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val isConnected by plexConfig.isConnected.collectAsState()
    val rows by viewModel.searchRows.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val isQueryEmpty by viewModel.isQueryEmpty.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isFilterShown by viewModel.isFilterShown.collectAsState()
    // These four are `SharedPreferences`-backed cold flows, so each needs a seed. Reading
    // `prefsRepo.bookSortKey` directly instead would compile and render the right value once, then
    // never update — a chip that does not move when tapped.
    val isSortDescending by viewModel.isSortDescending.collectAsState(initial = true)
    val hidePlayed by viewModel.arePlayedAudiobooksHidden.collectAsState(initial = false)
    val sortKey by viewModel.sortKey.collectAsState(initial = prefsRepo.bookSortKey)
    val viewStyle by viewModel.viewStyle.collectAsState(initial = prefsRepo.libraryBookViewStyle)

    return LibraryCircuitState(
      ui = state.copy(serverConnected = isConnected),
      isConnected = isConnected,
      isRefreshing = isRefreshing,
      isSearchActive = isSearchActive,
      isQueryEmpty = isQueryEmpty,
      query = query,
      searchRows = rows,
      isFilterShown = isFilterShown,
      isSortDescending = isSortDescending,
      hidePlayed = hidePlayed,
      sortKey = sortKey,
      viewStyle = viewStyle,
    ) { event ->
      when (event) {
        is LibraryEvent.BookOpened -> navigator.goTo(BookDetailsScreenKey(event.book.id))
        LibraryEvent.BrowseOpened -> navigator.goTo(BrowseScreenKey)
        is LibraryEvent.SearchQueryChanged -> viewModel.search(event.query)
        is LibraryEvent.SearchActiveChanged -> viewModel.setSearchActive(event.isActive)
        LibraryEvent.Refreshed -> viewModel.refreshData()
        LibraryEvent.OfflineModeDisabled -> viewModel.disableOfflineMode()
        is LibraryEvent.FilterSheetShown -> viewModel.setFilterMenuVisible(event.isShown)
        // Written straight to prefs, which is what the sheet's callbacks did: the flows above are
        // reading the same preferences back, so the write *is* the state change.
        is LibraryEvent.SortKeyChanged -> prefsRepo.bookSortKey = event.key
        LibraryEvent.SortDirectionToggled -> viewModel.toggleSortDirection()
        is LibraryEvent.ViewStyleChanged -> prefsRepo.libraryBookViewStyle = event.key
        LibraryEvent.HidePlayedToggled -> viewModel.toggleHidePlayedAudiobooks()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryUi(
  state: LibraryCircuitState,
  viewModel: LibraryViewModel,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  val chooser by viewModel.bottomChooserState.collectAsState()

  ToastEffect(viewModel.messageForUser)
  ToastResEffect(viewModel.syncError)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = ChronicleColors.Primary,
    topBar = {
      SearchTopBar(
        title = stringResource(R.string.tab_library),
        isActive = state.isSearchActive,
        query = state.query,
        onQueryChange = { state.eventSink(LibraryEvent.SearchQueryChanged(it)) },
        onActiveChange = { state.eventSink(LibraryEvent.SearchActiveChanged(it)) },
        actions = { LibraryToolbarActions(state) },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { state.eventSink(LibraryEvent.Refreshed) },
        modifier = Modifier.fillMaxSize(),
      ) {
        LibraryScreen(
          state = state.ui,
          coverUrl = plexConfig::toServerString,
          onBookClick = { state.eventSink(LibraryEvent.BookOpened(it)) },
          onDisableOfflineMode = { state.eventSink(LibraryEvent.OfflineModeDisabled) },
        )
      }

      SearchOverlay(
        state = searchOverlayState(state.isSearchActive, state.isQueryEmpty, state.searchRows),
        serverConnected = state.isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = { state.eventSink(LibraryEvent.BookOpened(it)) },
      )

      BottomChooser(chooser)
    }

    if (state.isFilterShown) {
      LibraryFilterSheetFor(state)
    }
  }
}

/**
 * The two toolbar icons: browse, and the filter sheet.
 *
 * `download_all` is deliberately **not** here, and that preserves today's behaviour rather than
 * changing it: `library_menu.xml` declared it `android:visible="false"` and nothing anywhere set it
 * visible, so the item has never been reachable on a device. Its handler is live though —
 * `promptDownloadAll()` works — so this is a feature that was built and then hidden, not dead code.
 * Restoring it is a product decision (it would download an entire library on one tap), so it is
 * filed rather than taken here.
 */
@Composable
private fun LibraryToolbarActions(state: LibraryCircuitState) {
  IconButton(onClick = { state.eventSink(LibraryEvent.BrowseOpened) }) {
    Icon(
      painter = painterResource(R.drawable.ic_browse),
      contentDescription = stringResource(R.string.browse_title),
    )
  }
  IconButton(onClick = { state.eventSink(LibraryEvent.FilterSheetShown(true)) }) {
    Icon(
      painter = painterResource(R.drawable.ic_filter_list_white),
      contentDescription = stringResource(R.string.filter),
    )
  }
}

/** The filter sheet, wired to the six events it raises. */
@Composable
private fun LibraryFilterSheetFor(state: LibraryCircuitState) {
  LibraryFilterSheet(
    sortOptions = sortOptions(),
    selectedSortKey = state.sortKey,
    onSortKeyChange = { state.eventSink(LibraryEvent.SortKeyChanged(it)) },
    isSortDescending = state.isSortDescending,
    onToggleSortDirection = { state.eventSink(LibraryEvent.SortDirectionToggled) },
    viewStyleOptions = viewStyleOptions(),
    selectedViewStyleKey = state.viewStyle,
    onViewStyleChange = { state.eventSink(LibraryEvent.ViewStyleChanged(it)) },
    hidePlayed = state.hidePlayed,
    onToggleHidePlayed = { state.eventSink(LibraryEvent.HidePlayedToggled) },
    onDismiss = { state.eventSink(LibraryEvent.FilterSheetShown(false)) },
  )
}

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
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

/**
 * The library grid, as a navigation destination (cu-206).
 *
 * The heaviest of the twelve: it carried a `SearchView`, a `SwipeRefreshLayout`, a persistent
 * `BottomSheetBehavior` filter panel with two `ChipGroup`s, and a four-item menu whose
 * `showAsAction` flags had to be juggled whenever search expanded. All of that is state here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDestination(
  prefsRepo: PrefsRepo,
  plexConfig: PlexConfig,
  onBookClick: (Audiobook) -> Unit,
  onBrowseClick: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LibraryViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
  val rows by viewModel.searchRows.collectAsStateWithLifecycle()
  val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
  val isQueryEmpty by viewModel.isQueryEmpty.collectAsStateWithLifecycle()
  val query by viewModel.searchQuery.collectAsStateWithLifecycle()
  val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
  val isFilterShown by viewModel.isFilterShown.collectAsStateWithLifecycle()
  // These four are `SharedPreferences`-backed cold flows, so each needs a seed. Reading
  // `prefsRepo.bookSortKey` directly instead would compile and render the right value once, then
  // never update — a chip that does not move when tapped.
  val isSortDescending by viewModel.isSortDescending.collectAsStateWithLifecycle(initialValue = true)
  val hidePlayed by viewModel.arePlayedAudiobooksHidden.collectAsStateWithLifecycle(initialValue = false)
  val sortKey by viewModel.sortKey.collectAsStateWithLifecycle(initialValue = prefsRepo.bookSortKey)
  val viewStyle by
    viewModel.viewStyle.collectAsStateWithLifecycle(initialValue = prefsRepo.libraryBookViewStyle)
  val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()

  ToastEffect(viewModel.messageForUser)
  ToastResEffect(viewModel.syncError)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = ChronicleColors.Primary,
    topBar = {
      SearchTopBar(
        title = stringResource(R.string.tab_library),
        isActive = isSearchActive,
        query = query,
        onQueryChange = viewModel::search,
        onActiveChange = viewModel::setSearchActive,
        actions = {
          // `download_all` is deliberately **not** here, and that preserves today's behaviour
          // rather than changing it: `library_menu.xml` declared it `android:visible="false"` and
          // nothing anywhere set it visible, so the item has never been reachable on a device.
          // Its handler is live though — `promptDownloadAll()` works — so this is a feature that
          // was built and then hidden, not dead code. Restoring it is a product decision (it
          // would download an entire library on one tap), so it is filed rather than taken here.
          // See the cu-206 notes.
          IconButton(onClick = onBrowseClick) {
            Icon(
              painter = painterResource(R.drawable.ic_browse),
              contentDescription = stringResource(R.string.browse_title),
            )
          }
          IconButton(onClick = { viewModel.setFilterMenuVisible(true) }) {
            Icon(
              painter = painterResource(R.drawable.ic_filter_list_white),
              contentDescription = stringResource(R.string.filter),
            )
          }
        },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refreshData,
        modifier = Modifier.fillMaxSize(),
      ) {
        LibraryScreen(
          state = state.copy(serverConnected = isConnected),
          coverUrl = plexConfig::toServerString,
          onBookClick = onBookClick,
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )
      }

      SearchOverlay(
        state = searchOverlayState(isSearchActive, isQueryEmpty, rows),
        serverConnected = isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = onBookClick,
      )

      BottomChooser(chooser)
    }

    if (isFilterShown) {
      LibraryFilterSheet(
        sortOptions = sortOptions(),
        selectedSortKey = sortKey,
        onSortKeyChange = { prefsRepo.bookSortKey = it },
        isSortDescending = isSortDescending,
        onToggleSortDirection = viewModel::toggleSortDirection,
        viewStyleOptions = viewStyleOptions(),
        selectedViewStyleKey = viewStyle,
        onViewStyleChange = { prefsRepo.libraryBookViewStyle = it },
        hidePlayed = hidePlayed,
        onToggleHidePlayed = viewModel::toggleHidePlayedAudiobooks,
        onDismiss = { viewModel.setFilterMenuVisible(false) },
      )
    }
  }
}

package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.collections.CollectionsViewModel
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastResEffect

/** The collections list, as a navigation destination (cu-206). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsDestination(
  plexConfig: PlexConfig,
  onCollectionClick: (Collection) -> Unit,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CollectionsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
  val rows by viewModel.searchRows.collectAsStateWithLifecycle()
  val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
  val isQueryEmpty by viewModel.isQueryEmpty.collectAsStateWithLifecycle()
  val query by viewModel.searchQuery.collectAsStateWithLifecycle()
  val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

  ToastEffect(viewModel.messageForUser)
  ToastResEffect(viewModel.syncError)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = ChronicleColors.Primary,
    topBar = {
      SearchTopBar(
        title = stringResource(R.string.tab_collections),
        isActive = isSearchActive,
        query = query,
        onQueryChange = viewModel::search,
        onActiveChange = viewModel::setSearchActive,
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refreshData,
        modifier = Modifier.fillMaxSize(),
      ) {
        CollectionsScreen(
          state = state,
          coverUrl = plexConfig::toServerString,
          onCollectionClick = onCollectionClick,
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )
      }

      SearchOverlay(
        state = searchOverlayState(isSearchActive, isQueryEmpty, rows),
        serverConnected = isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = onBookClick,
      )
    }
  }
}

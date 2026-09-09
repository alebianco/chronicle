package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.collections.CollectionsViewModel
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.CollectionDetailsScreenKey
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastResEffect

/** The collections list. */
data class CollectionsCircuitState(
  val ui: CollectionsUiState,
  val isConnected: Boolean,
  val isRefreshing: Boolean,
  val isSearchActive: Boolean,
  val isQueryEmpty: Boolean,
  val query: String,
  val searchRows: List<SearchRow>,
  val eventSink: (CollectionsEvent) -> Unit,
) : CircuitUiState

sealed interface CollectionsEvent : CircuitUiEvent {
  data class CollectionOpened(val collection: Collection) : CollectionsEvent

  /** From the search overlay, which lists books rather than collections. */
  data class BookOpened(val book: Audiobook) : CollectionsEvent

  data class SearchQueryChanged(val query: String) : CollectionsEvent

  data class SearchActiveChanged(val isActive: Boolean) : CollectionsEvent

  data object Refreshed : CollectionsEvent

  data object OfflineModeDisabled : CollectionsEvent
}

class CollectionsPresenter(
  private val viewModel: @Composable () -> CollectionsViewModel,
  private val plexConfig: PlexConfig,
  private val navigator: Navigator,
) : Presenter<CollectionsCircuitState> {
  @Composable
  override fun present(): CollectionsCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val isConnected by plexConfig.isConnected.collectAsState()
    val rows by viewModel.searchRows.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val isQueryEmpty by viewModel.isQueryEmpty.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    return CollectionsCircuitState(
      ui = state,
      isConnected = isConnected,
      isRefreshing = isRefreshing,
      isSearchActive = isSearchActive,
      isQueryEmpty = isQueryEmpty,
      query = query,
      searchRows = rows,
    ) { event ->
      when (event) {
        is CollectionsEvent.CollectionOpened ->
          navigator.goTo(CollectionDetailsScreenKey(event.collection.id))
        is CollectionsEvent.BookOpened -> navigator.goTo(BookDetailsScreenKey(event.book.id))
        is CollectionsEvent.SearchQueryChanged -> viewModel.search(event.query)
        is CollectionsEvent.SearchActiveChanged -> viewModel.setSearchActive(event.isActive)
        CollectionsEvent.Refreshed -> viewModel.refreshData()
        CollectionsEvent.OfflineModeDisabled -> viewModel.disableOfflineMode()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsUi(
  state: CollectionsCircuitState,
  viewModel: CollectionsViewModel,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  ToastEffect(viewModel.messageForUser)
  ToastResEffect(viewModel.syncError)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = ChronicleColors.Primary,
    topBar = {
      SearchTopBar(
        title = stringResource(R.string.tab_collections),
        isActive = state.isSearchActive,
        query = state.query,
        onQueryChange = { state.eventSink(CollectionsEvent.SearchQueryChanged(it)) },
        onActiveChange = { state.eventSink(CollectionsEvent.SearchActiveChanged(it)) },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { state.eventSink(CollectionsEvent.Refreshed) },
        modifier = Modifier.fillMaxSize(),
      ) {
        CollectionsScreen(
          state = state.ui,
          coverUrl = plexConfig::toServerString,
          onCollectionClick = { state.eventSink(CollectionsEvent.CollectionOpened(it)) },
          onDisableOfflineMode = { state.eventSink(CollectionsEvent.OfflineModeDisabled) },
        )
      }

      SearchOverlay(
        state = searchOverlayState(state.isSearchActive, state.isQueryEmpty, state.searchRows),
        serverConnected = state.isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = { state.eventSink(CollectionsEvent.BookOpened(it)) },
      )
    }
  }
}

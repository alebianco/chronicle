package io.github.mattpvaughn.chronicle.features.home.compose

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
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.home.HomeViewModel
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastResEffect

/** Everything the home shelves render, plus the sink the UI posts back through. */
data class HomeCircuitState(
  val ui: HomeUiState,
  val isConnected: Boolean,
  val isRefreshing: Boolean,
  val isSearchActive: Boolean,
  val isQueryEmpty: Boolean,
  val query: String,
  val searchRows: List<SearchRow>,
  val eventSink: (HomeEvent) -> Unit,
) : CircuitUiState

/**
 * Every interaction the home screen has.
 *
 * Note that [BookOpened] and [BookResumed] are distinct even though both start from a book. That
 * distinction is the screen's whole point — Continue Listening resumes playback where every other
 * shelf opens details — and expressing it as two events rather than one boolean parameter is what
 * makes it impossible to wire the wrong one.
 */
sealed interface HomeEvent : CircuitUiEvent {
  data class BookOpened(val book: Audiobook) : HomeEvent

  /** Continue Listening: resumes rather than opening details. */
  data class BookResumed(val book: Audiobook) : HomeEvent

  data class SearchQueryChanged(val query: String) : HomeEvent

  data class SearchActiveChanged(val isActive: Boolean) : HomeEvent

  data object Refreshed : HomeEvent

  data object OfflineModeDisabled : HomeEvent
}

class HomePresenter(
  private val viewModel: @Composable () -> HomeViewModel,
  private val plexConfig: PlexConfig,
  private val navigator: Navigator,
) : Presenter<HomeCircuitState> {
  @Composable
  override fun present(): HomeCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val isConnected by plexConfig.isConnected.collectAsState()
    val rows by viewModel.searchRows.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val isQueryEmpty by viewModel.isQueryEmpty.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    return HomeCircuitState(
      // `serverConnected` is folded in here rather than in the UI: it is state, and the screen
      // below should not have to know it arrives from a different flow than the rest.
      ui = state.copy(serverConnected = isConnected),
      isConnected = isConnected,
      isRefreshing = isRefreshing,
      isSearchActive = isSearchActive,
      isQueryEmpty = isQueryEmpty,
      query = query,
      searchRows = rows,
    ) { event ->
      when (event) {
        is HomeEvent.BookOpened -> navigator.goTo(BookDetailsScreenKey(event.book.id))
        is HomeEvent.BookResumed -> viewModel.resume(event.book)
        is HomeEvent.SearchQueryChanged -> viewModel.search(event.query)
        is HomeEvent.SearchActiveChanged -> viewModel.setSearchActive(event.isActive)
        HomeEvent.Refreshed -> viewModel.refreshData()
        HomeEvent.OfflineModeDisabled -> viewModel.disableOfflineMode()
      }
    }
  }
}

/**
 * The home shelves.
 *
 * ### Pull-to-refresh is Compose's own, deliberately
 *
 * Two earlier passes kept `SwipeRefreshLayout` as the `ComposeView`'s host, on the reasoning that
 * swapping a working widget was "an unrelated behaviour change inside a screen migration". That
 * reasoning was about changing it *while the XML host existed*. `PullToRefreshBox` keeps the same
 * contract: a gesture raises [HomeEvent.Refreshed], and `isRefreshing` drives the indicator.
 *
 * One behaviour is genuinely not carried over: `HorizontalChildReadySwipeRefreshLayout`, the
 * subclass that declined a pull once horizontal scroll slop was exceeded, so a sideways swipe on a
 * shelf did not trigger a refresh. `PullToRefreshBox` reads a nested-scroll connection rather than
 * intercepting touches, so a `LazyRow`'s horizontal drag never reaches it and the same protection
 * falls out of the architecture. **Verify this on a device** — a shelf that refreshes when swiped
 * sideways is exactly the regression that subclass was written to prevent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeUi(
  state: HomeCircuitState,
  viewModel: HomeViewModel,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  ToastEffect(viewModel.messageForUser)
  // A resume that could not start — offline with an uncached book. A tap that silently does
  // nothing is the worst outcome: the user cannot tell a broken app from an unavailable book.
  ToastResEffect(viewModel.resumeError)
  ToastResEffect(viewModel.syncError)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = ChronicleColors.Primary,
    topBar = {
      SearchTopBar(
        title = stringResource(R.string.tab_home),
        isActive = state.isSearchActive,
        query = state.query,
        onQueryChange = { state.eventSink(HomeEvent.SearchQueryChanged(it)) },
        onActiveChange = { state.eventSink(HomeEvent.SearchActiveChanged(it)) },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { state.eventSink(HomeEvent.Refreshed) },
        modifier = Modifier.fillMaxSize(),
      ) {
        HomeScreen(
          state = state.ui,
          coverUrl = plexConfig::toServerString,
          onBookClick = { state.eventSink(HomeEvent.BookOpened(it)) },
          onResumeClick = { state.eventSink(HomeEvent.BookResumed(it)) },
          onDisableOfflineMode = { state.eventSink(HomeEvent.OfflineModeDisabled) },
        )
      }

      // Drawn *over* the shelves, which is what the RecyclerView's `elevation="8dp"` did.
      SearchOverlay(
        state = searchOverlayState(state.isSearchActive, state.isQueryEmpty, state.searchRows),
        serverConnected = state.isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = { state.eventSink(HomeEvent.BookOpened(it)) },
      )
    }
  }
}

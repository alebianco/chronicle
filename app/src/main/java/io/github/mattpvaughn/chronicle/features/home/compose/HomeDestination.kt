package io.github.mattpvaughn.chronicle.features.home.compose

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
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.home.HomeViewModel
import io.github.mattpvaughn.chronicle.features.search.compose.SearchOverlay
import io.github.mattpvaughn.chronicle.features.search.compose.SearchTopBar
import io.github.mattpvaughn.chronicle.features.search.searchOverlayState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastResEffect

/**
 * The home shelves, as a navigation destination.
 *
 * ### Pull-to-refresh moved to Compose here, deliberately
 *
 * Two earlier passes kept `SwipeRefreshLayout` as the `ComposeView`'s host, on the reasoning that
 * swapping a working widget was "an unrelated behaviour change inside a screen migration". That
 * reasoning was about changing it *while the XML host existed*. This task removes the host, so the
 * choice is no longer "keep the widget or swap it" but "keep it inside an `AndroidView` island, or
 * use the platform's own". `PullToRefreshBox` is the latter, and it keeps the same contract: a
 * gesture calls `refreshData()`, and `isRefreshing` drives the indicator.
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
fun HomeDestination(
  plexConfig: PlexConfig,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
  val rows by viewModel.searchRows.collectAsStateWithLifecycle()
  val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
  val isQueryEmpty by viewModel.isQueryEmpty.collectAsStateWithLifecycle()
  val query by viewModel.searchQuery.collectAsStateWithLifecycle()
  val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

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
        HomeScreen(
          state = state.copy(serverConnected = isConnected),
          coverUrl = plexConfig::toServerString,
          onBookClick = onBookClick,
          // Continue Listening resumes rather than opening details.
          onResumeClick = { viewModel.resume(it) },
          onDisableOfflineMode = viewModel::disableOfflineMode,
        )
      }

      // Drawn *over* the shelves, which is what the RecyclerView's `elevation="8dp"` did.
      SearchOverlay(
        state = searchOverlayState(isSearchActive, isQueryEmpty, rows),
        serverConnected = isConnected,
        coverUrl = plexConfig::toServerString,
        onBookClick = onBookClick,
      )
    }
  }
}

package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.ChapterRow
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.chapterRows
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState
import io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsViewModel
import io.github.mattpvaughn.chronicle.features.player.compose.CastButton
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/** One full rotation of the sync icon. */
private const val SYNC_SPIN_MILLIS = 1000

/** Everything one book's details render, plus the sink its UI posts back through. */
data class DetailsCircuitState(
  val ui: DetailsUiState,
  val chapterRows: List<ChapterRow>,
  val connection: ConnectionState,
  val watchedIcon: Int,
  val isSyncing: Boolean,
  val eventSink: (DetailsEvent) -> Unit,
) : CircuitUiState

sealed interface DetailsEvent : CircuitUiEvent {
  data class ChapterOpened(val chapter: Chapter) : DetailsEvent

  data object PlayPausePressed : DetailsEvent

  data object DownloadPressed : DetailsEvent

  data object SummaryToggled : DetailsEvent

  /**
   * The series line under the title.
   *
   * Losing this would be a silent feature loss of the kind the player's Compose migration shipped
   * and had to recover — which is the argument for it being an event with a branch in an
   * exhaustive `when` rather than one lambda among six.
   */
  data object SeriesOpened : DetailsEvent

  data object WatchedToggled : DetailsEvent

  data object SyncPressed : DetailsEvent

  data object ServerReconnectPressed : DetailsEvent

  data object NavigateUp : DetailsEvent
}

class DetailsPresenter(
  private val viewModel: @Composable () -> AudiobookDetailsViewModel,
  private val navigator: Navigator,
) : Presenter<DetailsCircuitState> {
  @Composable
  override fun present(): DetailsCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val activeChapter by viewModel.activeChapter.collectAsState()
    val connection by viewModel.serverConnection.collectAsState()
    val watchedIcon by viewModel.isWatchedIcon.collectAsState()
    val isSyncing by viewModel.forceSyncInProgress.collectAsState()

    return DetailsCircuitState(
      ui = state,
      chapterRows = chapterRows(chapters, activeChapter),
      connection = connection,
      watchedIcon = watchedIcon,
      isSyncing = isSyncing == true,
    ) { event ->
      when (event) {
        is DetailsEvent.ChapterOpened ->
          viewModel.jumpToChapter(event.chapter.bookStartTimeOffset, event.chapter.trackId)
        DetailsEvent.PlayPausePressed -> viewModel.pausePlayButtonClicked()
        DetailsEvent.DownloadPressed -> viewModel.onCacheButtonClick()
        DetailsEvent.SummaryToggled -> viewModel.onToggleSummaryView()
        // The book is read here rather than carried on the event: which book this screen is about
        // is the presenter's business, and a tap on the series line knows only that it happened.
        DetailsEvent.SeriesOpened ->
          viewModel.audiobook.value?.let {
            navigator.goTo(FacetBooksScreenKey(FacetKind.Series, it.series))
          }
        DetailsEvent.WatchedToggled -> viewModel.toggleWatched()
        DetailsEvent.SyncPressed -> viewModel.forceSyncBook(hasUserConfirmation = false)
        DetailsEvent.ServerReconnectPressed -> viewModel.connectToServer()
        DetailsEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

/**
 * One book's details.
 *
 * Carries `@UnstableApi` because the Cast SDK's opt-in reaches here through [CastButton]. That used
 * to carry on into `ChronicleNavHost` and then `MainActivity.onCreate`; with the graph gone it
 * stops at the Circuit factory.
 */
@UnstableApi
@Composable
fun DetailsUi(
  state: DetailsCircuitState,
  viewModel: AudiobookDetailsViewModel,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  val chooser by viewModel.bottomChooserState.collectAsState()

  ChronicleScaffold(
    // The XML set `detailsToolbar.title = null`; the book's title is in the header below.
    title = "",
    onNavigateUp = { state.eventSink(DetailsEvent.NavigateUp) },
    modifier = modifier,
    actions = { DetailsToolbarActions(state) },
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      DetailsScreen(
        state = state.ui,
        chapterRows = state.chapterRows,
        onChapterClick = { state.eventSink(DetailsEvent.ChapterOpened(it)) },
        actions =
          DetailsActions(
            onPlayPause = { state.eventSink(DetailsEvent.PlayPausePressed) },
            onDownload = { state.eventSink(DetailsEvent.DownloadPressed) },
            onToggleSummary = { state.eventSink(DetailsEvent.SummaryToggled) },
            onSeriesClick = { state.eventSink(DetailsEvent.SeriesOpened) },
          ),
        coverUrl = plexConfig::toServerString,
      )

      ConnectionBanner(state)

      BottomChooser(chooser)
    }
  }
}

/**
 * The toolbar's three actions: cast, mark-as-watched, and force sync.
 *
 * Its own composable so [DetailsUi] stays readable — this is the densest part of the screen, and
 * the spinning sync icon in particular is eighteen lines of animation for one drawable.
 */
@UnstableApi
@Composable
private fun DetailsToolbarActions(state: DetailsCircuitState) {
  // The one View island in the app: the Cast SDK has no Compose surface, and this renders
  // nothing at all where casting is unavailable (decision-19's degrade-to-absent).
  CastButton()

  IconButton(onClick = { state.eventSink(DetailsEvent.WatchedToggled) }) {
    Icon(
      painter = painterResource(state.watchedIcon),
      contentDescription = stringResource(R.string.toggle_watched),
    )
  }

  IconButton(onClick = { state.eventSink(DetailsEvent.SyncPressed) }) {
    val rotation =
      if (state.isSyncing) {
        val transition = rememberInfiniteTransition(label = "sync")
        transition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(SYNC_SPIN_MILLIS, easing = LinearEasing),
              repeatMode = RepeatMode.Restart,
            ),
          label = "syncRotation",
        ).value
      } else {
        0f
      }
    Icon(
      painter = painterResource(R.drawable.ic_sync),
      contentDescription = stringResource(R.string.force_sync),
      modifier = Modifier.rotate(rotation),
    )
  }
}

/**
 * The connection banner, which was two `isVisible`-driven views over the pinned bar.
 *
 * The `when` names all four states rather than using `else`, so a fifth is a compile error here
 * instead of silently rendering no banner at all.
 */
@Composable
private fun BoxScope.ConnectionBanner(state: DetailsCircuitState) {
  when (state.connection) {
    ConnectionState.CONNECTING ->
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
        color = ChronicleColors.Accent,
      )

    ConnectionState.CONNECTION_FAILED ->
      Text(
        text = stringResource(R.string.failed_to_connect_to_server),
        color = ChronicleColors.TextError,
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .clickable { state.eventSink(DetailsEvent.ServerReconnectPressed) }
            .padding(8.dp),
      )

    ConnectionState.CONNECTED, ConnectionState.NOT_CONNECTED -> Unit
  }
}

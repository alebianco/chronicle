package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.chapterRows
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState
import io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsViewModel
import io.github.mattpvaughn.chronicle.features.player.compose.CastButton
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/** How long one turn of the sync spinner takes. */
private const val SYNC_SPIN_MILLIS = 1000

/**
 * One book, as a navigation destination (cu-206).
 *
 * ### The collapsing toolbar is not carried over
 *
 * `fragment_audiobook_details.xml` had a real `CollapsingToolbarLayout` with a pinned bar. It
 * collapsed *nothing*, though: `titleEnabled="false"` and the toolbar's own title set to `null`, so
 * there was no large title to shrink and the pinned bar stayed the same height throughout. What it
 * actually provided was a toolbar that scrolled away with the content, which
 * `exitUntilCollapsed` gave for free. A plain top bar is the honest equivalent, and the alternative
 * — an `enterAlwaysCollapsed` scroll behaviour — would re-export the experimental
 * `TopAppBarScrollBehavior` opt-in to no visible benefit. **Worth a look on a device** beside the
 * baseline screenshot.
 *
 * ### The sync icon animates in Compose, not as an AnimatedVectorDrawable
 *
 * `ic_sync_rotate` is an `<animated-vector>`, started and stopped by casting the menu item's icon
 * to `AnimatedVectorDrawable`. `painterResource` does not animate one, so the rotation is an
 * `infiniteRepeatable` here — the same appearance, and the `onPrepareMenu` hook that re-applied the
 * icon state "once the menu exists" has nothing left to fix, because state renders whenever it
 * changes.
 */
@Composable
fun DetailsDestination(
  plexConfig: PlexConfig,
  onNavigateUp: () -> Unit,
  onSeriesClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: AudiobookDetailsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val chapters by viewModel.chapters.collectAsStateWithLifecycle()
  val activeChapter by viewModel.activeChapter.collectAsStateWithLifecycle()
  val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()
  val connection by viewModel.serverConnection.collectAsStateWithLifecycle()
  val watchedIcon by viewModel.isWatchedIcon.collectAsStateWithLifecycle()
  val isSyncing by viewModel.forceSyncInProgress.collectAsStateWithLifecycle()

  ChronicleScaffold(
    // The XML set `detailsToolbar.title = null`; the book's title is in the header below.
    title = "",
    onNavigateUp = onNavigateUp,
    modifier = modifier,
    actions = {
      // The one View island in the app: the Cast SDK has no Compose surface, and this renders
      // nothing at all where casting is unavailable (decision-19's degrade-to-absent).
      CastButton()

      IconButton(onClick = viewModel::toggleWatched) {
        Icon(
          painter = painterResource(watchedIcon),
          contentDescription = stringResource(R.string.toggle_watched),
        )
      }

      IconButton(onClick = { viewModel.forceSyncBook(hasUserConfirmation = false) }) {
        val rotation =
          if (isSyncing == true) {
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
    },
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      DetailsScreen(
        state = state,
        chapterRows = chapterRows(chapters, activeChapter),
        onChapterClick = { viewModel.jumpToChapter(it.bookStartTimeOffset, it.trackId) },
        actions =
          DetailsActions(
            onPlayPause = viewModel::pausePlayButtonClicked,
            onDownload = viewModel::onCacheButtonClick,
            onToggleSummary = viewModel::onToggleSummaryView,
            // The series line navigates into the browse facet cu-24 built. Losing it would be a
            // silent feature loss of the kind cu-198 shipped and had to recover.
            onSeriesClick = { viewModel.audiobook.value?.let { onSeriesClick(it.series) } },
          ),
        coverUrl = plexConfig::toServerString,
      )

      // The connection banner, which was two `isVisible`-driven views over the pinned bar.
      when (connection) {
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
                .clickable { viewModel.connectToServer() }
                .padding(8.dp),
          )

        else -> Unit
      }

      BottomChooser(chooser)
    }
  }
}

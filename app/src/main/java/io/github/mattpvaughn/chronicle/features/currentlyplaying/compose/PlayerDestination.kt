package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.chapterRows
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.CollectEffect
import io.github.mattpvaughn.chronicle.util.compose.EventEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.views.compose.BookmarkList
import io.github.mattpvaughn.chronicle.views.compose.BookmarkNoteSheet
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import io.github.mattpvaughn.chronicle.views.compose.SpeedChooserSheet

/**
 * The expanded player.
 *
 * Not a `NavHost` destination: it lives *above* the nav host in `ChronicleApp`, because the player
 * sheet covers whatever screen the user is on and must survive navigating between them. That is the
 * same relationship `currently_playing_container` had to `fragNavHost` in `activity_main.xml`.
 *
 * ### The three sheets become state
 *
 * `CurrentlyPlayingFragment` showed the bookmark list, the note editor and the speed chooser as
 * `BottomSheetDialogFragment`s through `childFragmentManager`, and implemented two `Listener`
 * interfaces to hear back from them. It also had to *push* bookmarks into the list sheet with a
 * `collectWhileStarted` after showing it, because the sheet held no repository of its own. All of
 * that is `if (showX)` here.
 *
 * The sleep-timer collection is STARTED-scoped. Note this is **not** what the `DisposableEffect`
 * it replaced did — that was keyed on `context`, so it was composition-scoped and outlived
 * `onStop`. The Fragment's original `onStart`/`onStop` pair was STARTED; the Compose port had
 * quietly widened it, and this restores it. It no longer guards a bidirectional channel either:
 * `SleepTimerBus` splits commands from reports, so this side only ever receives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDestination(
  plexConfig: PlexConfig,
  onCollapse: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CurrentlyPlayingViewModel,
) {
  val context = LocalContext.current
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
  val chapters by viewModel.chapters.collectAsStateWithLifecycle()
  val activeChapter by viewModel.activeChapter.collectAsStateWithLifecycle()
  val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()
  val sleepTimerChooser by viewModel.sleepTimerChooserState.collectAsStateWithLifecycle()
  val isLoadingTracks by viewModel.isLoadingTracks.collectAsStateWithLifecycle()
  val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
  val speedState by viewModel.speedChooserState.collectAsStateWithLifecycle()
  val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()

  var showBookmarks by remember { mutableStateOf(false) }
  var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
  var showSpeedChooser by remember { mutableStateOf(false) }

  ToastEffect(viewModel.showUserMessage)

  // A new bookmark opens its note sheet straight away, so writing one is part of the same gesture
  // rather than something to go and find afterwards. The note is optional — dismissing
  // leaves a perfectly good positional bookmark.
  EventEffect(viewModel.bookmarkAdded) { bookmark -> editingBookmark = bookmark }
  EventEffect(viewModel.showModalBottomSheetSpeedChooser) { showSpeedChooser = true }

  // STARTED-scoped, which is a deliberate *tightening*: the `DisposableEffect` this replaces was
  // keyed on `context`, so it was composition-scoped and kept the receiver registered across
  // `onStop` for as long as this destination stayed composed. A backgrounded player now stops
  // applying ticks. `SleepTimerBus.updates` replays its latest value, so re-expanding the sheet
  // inherits the current timer state rather than an empty one — which an end-of-chapter timer
  // depends on, since it publishes only when armed and when the chapter ends.
  CollectEffect(viewModel.sleepTimerUpdates, viewModel::onSleepTimerUpdate)

  Box(modifier = modifier.fillMaxSize()) {
    PlayerScreen(
      state = state.copy(artwork = state.artwork.copy(serverConnected = isConnected)),
      actions =
        PlayerActions(
          onPlayPause = viewModel::play,
          onSkipForwards = viewModel::skipForwards,
          onSkipBackwards = viewModel::skipBackwards,
          onSkipToNext = viewModel::skipToNext,
          onSkipToPrevious = viewModel::skipToPrevious,
          onSeek = { fraction -> viewModel.seekTo(fraction.toDouble()) },
          onSlideStart = viewModel::onSlideStart,
          onSlideFinished = viewModel::onSlideFinished,
          onChangeSpeed = viewModel::showPlaybackSpeedChooser,
          onSleepTimer = viewModel::showSleepTimerOptions,
          onAddBookmark = viewModel::addBookmark,
          onShowBookmarks = { showBookmarks = true },
        ),
      coverUrl = plexConfig::toServerString,
      chapterRows = chapterRows(chapters, activeChapter),
      onChapterClick = { viewModel.jumpToChapter(it.bookStartTimeOffset, it.trackId) },
      // Landscape drops the cover, which is the only thing `values-land` ever changed here.
      showArtwork =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT,
    )

    // Collapse, which the pinned toolbar's navigation icon did.
    IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.TopStart)) {
      Icon(
        painter = painterResource(R.drawable.ic_arrow_back_white),
        contentDescription = stringResource(R.string.back),
        tint = ChronicleColors.TextPrimary,
      )
    }

    if (isLoadingTracks) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center),
        color = ChronicleColors.Accent,
      )
    }

    BottomChooser(chooser)
    BottomChooser(sleepTimerChooser)
  }

  if (showBookmarks) {
    // `BookmarkList` is content, not a sheet — `ModalBottomSheetBookmarks` was its host, and this
    // is what replaces that host. The list no longer has to be *pushed* its bookmarks after being
    // shown: it reads the same flow the screen does.
    ModalBottomSheet(
      onDismissRequest = { showBookmarks = false },
      containerColor = ChronicleColors.PrimaryDark,
    ) {
      BookmarkList(
        bookmarks = bookmarks,
        onJump = {
          viewModel.jumpToBookmark(it)
          showBookmarks = false
        },
        onEdit = {
          showBookmarks = false
          editingBookmark = it
        },
      )
    }
  }

  editingBookmark?.let { bookmark ->
    BookmarkNoteSheet(
      positionMillis = bookmark.position.millis,
      existingNote = bookmark.note,
      onSave = {
        viewModel.setBookmarkNote(bookmark.id, it)
        editingBookmark = null
      },
      onDelete = {
        viewModel.deleteBookmark(bookmark.id)
        editingBookmark = null
      },
      onDismiss = { editingBookmark = null },
    )
  }

  if (showSpeedChooser) {
    SpeedChooserSheet(
      state = speedState,
      skipSilence = skipSilence,
      onSpeedChange = viewModel::setPlaybackSpeed,
      onToggleOverride = viewModel::setSpeedOverrideEnabled,
      onToggleSkipSilence = viewModel::setSkipSilence,
      onDismiss = { showSpeedChooser = false },
    )
  }
}

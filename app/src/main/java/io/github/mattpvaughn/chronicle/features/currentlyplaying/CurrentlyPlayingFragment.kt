package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.compose.runtime.getValue
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.chapterRows
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCurrentlyPlayingBinding
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.PlayerActions
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.PlayerScreen
import io.github.mattpvaughn.chronicle.features.player.SleepTimer
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInsetAsPinnedBar
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetBookmarkNote
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetBookmarks
import io.github.mattpvaughn.chronicle.views.ModalBottomSheetSpeedChooser
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

/** Responsible for playback controls and displaying the currently playing media */
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CurrentlyPlayingFragment :
  Fragment(),
  ModalBottomSheetBookmarkNote.Listener,
  ModalBottomSheetBookmarks.Listener {
  private lateinit var currentlyPlayingInterface: MainActivity.CurrentlyPlayingInterface

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var localBroadcastManager: LocalBroadcastManager

  private val viewModel: CurrentlyPlayingViewModel by viewModels()

  /**
   * Opens the bookmark list, keeping it fed while it is shown.
   *
   * The sheet holds no repository: this observes and pushes, so the subscription belongs to the
   * Fragment's lifecycle and dies with it.
   */
  private fun showBookmarkList() {
    val sheet = ModalBottomSheetBookmarks()
    sheet.show(childFragmentManager, ModalBottomSheetBookmarks.TAG)
    viewLifecycleOwner.collectWhileStarted(viewModel.bookmarks) { bookmarks ->
      sheet.setBookmarks(bookmarks)
    }
  }

  override fun onBookmarkJump(bookmark: Bookmark) {
    viewModel.jumpToBookmark(bookmark)
  }

  override fun onBookmarkEdit(bookmark: Bookmark) {
    ModalBottomSheetBookmarkNote.forBookmark(
      bookmarkId = bookmark.id,
      positionMillis = bookmark.position.millis,
      note = bookmark.note,
    ).show(childFragmentManager, ModalBottomSheetBookmarkNote.TAG)
  }

  override fun onNoteSaved(
    bookmarkId: String,
    note: String,
  ) {
    viewModel.setBookmarkNote(bookmarkId, note)
  }

  override fun onBookmarkDeleted(bookmarkId: String) {
    viewModel.deleteBookmark(bookmarkId)
  }

  companion object {
    fun newInstance() = CurrentlyPlayingFragment()
  }

  /**
   * The host's currently-playing interface, which is **not** dependency injection.
   *
   * This survived cu-185's removal of the DI `onAttach` bodies because it is a different thing:
   * the host *is* the interface, so it comes from the attaching context rather than the graph.
   * Deleting it with the injection line left `currentlyPlayingInterface` uninitialised and the
   * player crashed on its first composition.
   */
  override fun onAttach(context: Context) {
    currentlyPlayingInterface = (context as MainActivity).getCurrentlyPlayingInterface()
    super.onAttach(context)
  }

  override fun onStart() {
    super.onStart()
    localBroadcastManager.registerReceiver(
      viewModel.onUpdateSleepTimer,
      IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
    )
  }

  override fun onStop() {
    localBroadcastManager.unregisterReceiver(viewModel.onUpdateSleepTimer)
    super.onStop()
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    // Activity and context are non-null on view creation. This informs lint about that
    val binding = FragmentCurrentlyPlayingBinding.inflate(inflater, container, false)

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.showUserMessage) { message ->
      Toast.makeText(context, message, LENGTH_SHORT).show()
    }

    // A new bookmark opens its note sheet straight away, so writing one is part of the same
    // gesture rather than something to go and find afterwards (cu-22). The note is optional —
    // dismissing the sheet leaves a perfectly good positional bookmark.
    viewLifecycleOwner.collectEventsWhileStarted(viewModel.bookmarkAdded) { bookmark ->
      ModalBottomSheetBookmarkNote.forBookmark(
        bookmarkId = bookmark.id,
        positionMillis = bookmark.position.millis,
        note = bookmark.note,
      ).show(childFragmentManager, ModalBottomSheetBookmarkNote.TAG)
    }

    // The whole player body is `PlayerScreen` now (cu-198). This replaces 29 imperative writes,
    // eight click listeners, three guarded render functions and the `boundTitle`/`boundThumb`
    // change-detection fields — all of which existed to avoid work Compose skips for free.
    //
    // Composition is gated on the sheet being **expanded**, read from the Activity's own state
    // rather than inferred from `!seekbar.isShown || root.height == 0`. That inference is the
    // cause of cu-141 and cu-19: a collapsed sheet is zero height with every child still
    // `VISIBLE`, and the obvious alternative probe was a view `values-land` hides. Collapsed, the
    // body is not composed at all, which is a stronger guarantee than a guard that has to be
    // remembered at each write site (cu-110, cu-117).
    binding.playerCompose.setContent {
      val sheetState by currentlyPlayingInterface.bottomSheetState.collectAsStateWithLifecycle()
      if (sheetState != MainActivityViewModel.BottomSheetState.EXPANDED) return@setContent

      val state by viewModel.uiState.collectAsStateWithLifecycle()
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
      val chapters by viewModel.chapters.collectAsStateWithLifecycle()
      val activeChapter by viewModel.activeChapter.collectAsStateWithLifecycle()

      ChronicleTheme {
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
              onShowBookmarks = ::showBookmarkList,
            ),
          coverUrl = plexConfig::toServerString,
          chapterRows = chapterRows(chapters, activeChapter),
          onChapterClick = { viewModel.jumpToChapter(it.bookStartTimeOffset, it.trackId) },
          // Landscape drops the cover, which is the only thing `values-land` ever changed here.
          showArtwork =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
        )

        // Both choosers are `BottomChooser` now (cu-203). They are siblings of the screen rather
        // than part of it: a modal sheet draws in its own window, and this one carries *two*
        // independent states — the speed chooser and the sleep timer — which the View version
        // needed two overlapping `FrameLayout`s to express.
        val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()
        val sleepTimerChooser by viewModel.sleepTimerChooserState.collectAsStateWithLifecycle()
        BottomChooser(chooser)
        BottomChooser(sleepTimerChooser)
      }
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.isLoadingTracks) {
      binding.loadingTracksSpinner.isVisible = it
    }

    binding.detailsToolbar.setNavigationOnClickListener {
      currentlyPlayingInterface.setBottomSheetState(COLLAPSED)
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.showModalBottomSheetSpeedChooser) {
      ModalBottomSheetSpeedChooser().show(
        childFragmentManager,
        ModalBottomSheetSpeedChooser.TAG,
      )
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    // The *pinned* bar takes the inset, so it paints the status-bar strip itself. Padding the
    // collapsing container instead leaves that strip to the scrolling artwork, which then shows
    // above the toolbar (cu-105).
    binding.detailsToolbar.applyTopSystemBarInsetAsPinnedBar()

    return binding.root
  }
}

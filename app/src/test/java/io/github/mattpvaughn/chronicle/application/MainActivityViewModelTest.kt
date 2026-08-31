package io.github.mattpvaughn.chronicle.application

import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.EXPANDED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.HIDDEN
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The mini-player's expand/collapse state machine, which was at 0% instruction coverage.
 *
 * These are small transitions, but they are the ones a user drives dozens of times a session, and
 * one of them throws: `onCurrentlyPlayingClicked` raises IllegalStateException on HIDDEN. That is
 * the same crash-on-an-unexpected-state shape as cu-92, so the current behaviour is pinned here
 * rather than quietly changed.
 */
class MainActivityViewModelTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  /**
   * `PlaybackStateCompat.Builder.setState` reads `SystemClock.elapsedRealtime`, which the unit-test
   * android.jar leaves unimplemented. Stubbing the clock is cheaper and more honest than pulling in
   * Robolectric for one timestamp.
   */
  @Before
  fun stubSystemClock() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
  }

  @After
  fun unstubSystemClock() {
    unmockkStatic(SystemClock::class)
  }

  private val loginRepo =
    mockk<IPlexLoginRepo>(relaxed = true) {
      every { loginEvent } returns
        MutableLiveData(Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY))
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook(any()) } returns MutableLiveData(emptyList())
    }

  private val collectionsRepository =
    mockk<CollectionsRepository>(relaxed = true) {
      every { hasCollections() } returns MutableLiveData(false)
    }

  private val mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true)

  /** The sheet starts hidden: nothing is playing yet. */
  @Test
  fun `the sheet starts hidden`() {
    assertEquals(HIDDEN, viewModel().currentlyPlayingLayoutState.value)
  }

  @Test
  fun `clicking the collapsed sheet expands it`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.onCurrentlyPlayingClicked()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  @Test
  fun `clicking the expanded sheet collapses it`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(EXPANDED)

    viewModel.onCurrentlyPlayingClicked()

    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  /**
   * Current behaviour, pinned rather than endorsed: clicking a hidden sheet throws. The sheet is
   * not on screen when hidden, so this should be unreachable — but it is an uncaught exception on
   * a main-screen control if it ever is reached.
   */
  @Test(expected = IllegalStateException::class)
  fun `clicking a hidden sheet throws`() {
    viewModel().onCurrentlyPlayingClicked()
  }

  @Test
  fun `minimizing only affects an expanded sheet`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(EXPANDED)

    viewModel.minimizeCurrentlyPlaying()

    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  /** Minimizing a collapsed sheet must not hide it — that would lose the mini-player. */
  @Test
  fun `minimizing a collapsed sheet leaves it collapsed`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.minimizeCurrentlyPlaying()

    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  @Test
  fun `maximizing a collapsed sheet expands it`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.maximizeCurrentlyPlaying()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  /** Already expanded: no redundant transition. */
  @Test
  fun `maximizing an expanded sheet leaves it expanded`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(EXPANDED)

    viewModel.maximizeCurrentlyPlaying()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  @Test
  fun `dragging the handle expands a collapsed sheet`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.onCurrentlyPlayingHandleDragged()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  /** Dragging an already-expanded sheet must not collapse it — that inverts the gesture. */
  @Test
  fun `dragging an expanded sheet leaves it expanded`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(EXPANDED)

    viewModel.onCurrentlyPlayingHandleDragged()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  /** Play/pause must wait for the service rather than dropping the press. */
  @Test
  fun `pressing play while disconnected connects first`() {
    every { mediaServiceConnection.isConnected } returns MutableLiveData(false)

    viewModel().pausePlayButtonClicked()

    verify { mediaServiceConnection.connect(any()) }
  }

  @Test
  fun `pressing play while connected does not reconnect`() {
    // A real PlaybackStateCompat: it is a plain builder with no device dependency, and a relaxed
    // mock returns a bare Object that `pausePlay` cannot cast.
    every { mediaServiceConnection.isConnected } returns MutableLiveData(true)
    every { mediaServiceConnection.playbackState } returns
      MutableLiveData(
        PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f)
          .build(),
      )

    viewModel().pausePlayButtonClicked()

    verify(exactly = 0) { mediaServiceConnection.connect(any()) }
  }

  private fun viewModel() =
    MainActivityViewModel(
      loginRepo = loginRepo,
      trackRepository = trackRepository,
      bookRepository = mockk<IBookRepository>(relaxed = true),
      mediaServiceConnection = mediaServiceConnection,
      collectionsRepository = collectionsRepository,
    )
}

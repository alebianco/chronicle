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
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coEvery
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

  private val book = Audiobook(id = "1001", source = 1L, title = "Dune")

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      coEvery { getAudiobookAsync(any()) } returns book
    }

  private fun track(
    id: String,
    progress: Long = 0L,
    title: String = "Track",
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    title = title,
    duration = 10_000L,
    progress = progress,
    index = 1,
  )

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

  // --- expandCurrentlyPlaying: the idempotent, non-throwing counterpart -------------------
  //
  // Added for the `show_player` debug hook (cu-73), which needs the player on screen without tap
  // coordinates so the "position not synced" badge can be screenshotted. It cannot use
  // `onCurrentlyPlayingClicked`, which toggles and throws on HIDDEN.

  @Test
  fun `expanding a collapsed sheet expands it`() {
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.expandCurrentlyPlaying()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  @Test
  fun `expanding is idempotent`() {
    // Unlike the click handler, calling this twice must not collapse the sheet again — the hook
    // observes playback state and can fire more than once.
    val viewModel = viewModel()
    viewModel.setBottomSheetState(COLLAPSED)

    viewModel.expandCurrentlyPlaying()
    viewModel.expandCurrentlyPlaying()

    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)
  }

  @Test
  fun `expanding a hidden sheet is a no-op rather than a throw`() {
    // The reason this method exists. Nothing is playing, so there is no player to show; the
    // caller is a debug hook firing on a state change and must not crash the app.
    val viewModel = viewModel()

    viewModel.expandCurrentlyPlaying()

    assertEquals(HIDDEN, viewModel.currentlyPlayingLayoutState.value)
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

  /**
   * Nothing playing: the readout says so rather than showing a stale or blank title.
   *
   * Only the idle branch is covered. The populated branches of `chapters` and `currentChapterTitle`
   * hang off `audiobookId`, which is set by the `MediaControllerCompat` metadata observer and then
   * loaded through an async `mapAsync` hop — so exercising them means driving real playback
   * metadata, not stubbing a repository. Worth doing with the media-session work (cu-89) rather
   * than faking the controller here.
   */
  @Test
  fun `the chapter title reads as idle when there are no tracks`() {
    every { trackRepository.getTracksForAudiobook(any()) } returns MutableLiveData(emptyList())

    val viewModel = viewModel()
    viewModel.currentChapterTitle.observeForever { }

    assertEquals("No track playing", viewModel.currentChapterTitle.value)
  }

  /**
   * The sheet state must be readable **immediately** after it is written (cu-73).
   *
   * Every writer used `postValue`, which defers to the next main-loop pass. Three methods here read
   * the state back to decide what to do, and so does the activity's back handler — so a deferred
   * write meant the next reader saw the *previous* state. Back concluded the player was not
   * expanded and fell through to leaving the app.
   *
   * `InstantTaskExecutorRule` runs LiveData work inline, so this test would pass under `postValue`
   * on the *observed* value; what it actually pins is the read-after-write that the production
   * paths depend on.
   */
  @Test
  fun `the sheet state is readable immediately after being set`() {
    val viewModel = viewModel()

    viewModel.setBottomSheetState(EXPANDED)

    assertEquals(
      "a deferred write makes the next reader — including the back handler — see stale state",
      EXPANDED,
      viewModel.currentlyPlayingLayoutState.value,
    )
  }

  /** Two writes in one pass must both take effect; postValue coalesces and keeps only the last. */
  @Test
  fun `consecutive state changes are not coalesced`() {
    val viewModel = viewModel()

    viewModel.setBottomSheetState(EXPANDED)
    assertEquals(EXPANDED, viewModel.currentlyPlayingLayoutState.value)

    viewModel.setBottomSheetState(COLLAPSED)
    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  /** The read-then-write helpers must act on what was just written, not a stale value. */
  @Test
  fun `minimize acts on the state just set`() {
    val viewModel = viewModel()

    viewModel.setBottomSheetState(EXPANDED)
    viewModel.minimizeCurrentlyPlaying()

    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  private fun viewModel() =
    MainActivityViewModel(
      loginRepo = loginRepo,
      trackRepository = trackRepository,
      bookRepository = bookRepository,
      mediaServiceConnection = mediaServiceConnection,
      collectionsRepository = collectionsRepository,
    )

  /**
   * The player sheet must survive a book ending.
   *
   * `STATE_STOPPED` fires when the last track runs out, and hiding on it was a **one-way door**:
   * nothing could bring the sheet back, because the routes off `HIDDEN` need either a later
   * non-stopped state (there is none — playback has ended) or a *different* book id. The collapsed
   * player is the only handle that expands the sheet, so the player became unreachable, and for an
   * already-finished book it was never reachable at all (cu-119).
   */
  @Test
  fun `a book reaching its end does not hide the player`() {
    val playbackState =
      MutableLiveData(
        PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f).build(),
      )
    every { mediaServiceConnection.playbackState } returns playbackState
    val viewModel = viewModel()

    // Playing: the sheet is revealed.
    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)

    // The book runs out.
    playbackState.value =
      PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1.0f).build()

    assertEquals(
      "a finished book must stay reachable; hiding here is a one-way door",
      COLLAPSED,
      viewModel.currentlyPlayingLayoutState.value,
    )
  }

  /** `STATE_NONE` still hides it: that means there is genuinely nothing to play. */
  @Test
  fun `no playback at all hides the player`() {
    val playbackState =
      MutableLiveData(
        PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f).build(),
      )
    every { mediaServiceConnection.playbackState } returns playbackState
    val viewModel = viewModel()
    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)

    playbackState.value =
      PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f).build()

    assertEquals(HIDDEN, viewModel.currentlyPlayingLayoutState.value)
  }

  /** Recovery from the hidden state, which is the half that made it a trap. */
  @Test
  fun `playback resuming after nothing was playing reveals the player again`() {
    val playbackState =
      MutableLiveData(
        PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f).build(),
      )
    every { mediaServiceConnection.playbackState } returns playbackState
    val viewModel = viewModel()
    assertEquals(HIDDEN, viewModel.currentlyPlayingLayoutState.value)

    playbackState.value =
      PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f).build()

    assertEquals(COLLAPSED, viewModel.currentlyPlayingLayoutState.value)
  }

  /**
   * Onboarding must be distinguishable from being in the app.
   *
   * The back handler keys off this. Backing out of a picker used to fall through to "switch to the
   * Home tab", which rendered the previous session's books from Room — so the app looked fully
   * configured while holding no library at all (cu-124).
   */
  @Test
  fun `a partial login state counts as onboarding`() {
    for (state in listOf(
      IPlexLoginRepo.LoginState.LOGGED_IN_NO_USER_CHOSEN,
      IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN,
      IPlexLoginRepo.LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN,
    )) {
      every { loginRepo.loginEvent } returns MutableLiveData(Event(state))
      val vm = viewModel()
      vm.isOnboarding.observeForever {}

      assertEquals("$state must count as onboarding", true, vm.isOnboarding.value)
    }
  }

  @Test
  fun `a complete login is not onboarding`() {
    every { loginRepo.loginEvent } returns
      MutableLiveData(Event(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY))
    val vm = viewModel()
    vm.isOnboarding.observeForever {}

    assertEquals(false, vm.isOnboarding.value)
  }

  @Test
  fun `being logged out is not onboarding`() {
    // Not signed in at all is the login screen's business, not a half-finished setup.
    every { loginRepo.loginEvent } returns
      MutableLiveData(Event(IPlexLoginRepo.LoginState.NOT_LOGGED_IN))
    val vm = viewModel()
    vm.isOnboarding.observeForever {}

    assertEquals(false, vm.isOnboarding.value)
  }
}

package io.github.mattpvaughn.chronicle.features.home

import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Resuming from the Continue Listening shelf (cu-18).
 *
 * The shelf's whole premise is "carry on where you left off", and it used to navigate to the
 * details screen — so carrying on took a second screen and a second tap. These pin that a tap now
 * reaches the player directly, and the two ways it must *not*.
 *
 * Robolectric because `resume` builds a real `Bundle`, which is unimplemented in the unit-test
 * android.jar; the same split as `AudiobookDetailsPlaybackTest` and for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
class HomeResumeTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book = Audiobook(id = "1001", source = 1L, title = "The Hobbit", progress = 74_008L)

  private val transportControls = mockk<MediaControllerCompat.TransportControls>(relaxed = true)

  @Test
  fun `a tap resumes this book by id`() {
    viewModel().resume(book)

    verify { transportControls.playFromMediaId(eq("1001"), any()) }
  }

  /**
   * The saved-position sentinel, not an offset computed here.
   *
   * The service owns resolving where a book resumes from, out of its tracks. Computing it in the
   * ViewModel would duplicate that resolution — the mistake cu-136 was about — and a wrong answer
   * reads as "resume jumped me somewhere else", which is worse than not resuming at all.
   */
  @Test
  fun `resume asks for the saved position rather than computing one`() {
    val extras = slot<Bundle>()

    viewModel().resume(book)

    verify { transportControls.playFromMediaId(eq("1001"), capture(extras)) }
    assertEquals(
      USE_SAVED_TRACK_PROGRESS,
      extras.captured.getLong(KEY_START_TIME_TRACK_OFFSET),
    )
  }

  /**
   * Offline, an uncached book cannot play — say so instead of failing silently.
   *
   * A tap that does nothing is the worst outcome here: the user cannot tell whether the app is
   * broken or the book is simply unavailable.
   */
  @Test
  fun `resume refuses an uncached book with no server, and says why`() {
    val viewModel = viewModel(connected = false)

    viewModel.resume(book.copy(isCached = false))

    verify(exactly = 0) { transportControls.playFromMediaId(any(), any()) }
    assertEquals(
      R.string.cannot_play_media_no_server,
      viewModel.resumeError.value?.peekContent(),
    )
  }

  /** A downloaded book must still resume with no server — that is what downloading is for. */
  @Test
  fun `resume plays a cached book with no server`() {
    val viewModel = viewModel(connected = false)

    viewModel.resume(book.copy(isCached = true))

    verify { transportControls.playFromMediaId(eq("1001"), any()) }
    assertEquals(null, viewModel.resumeError.value)
  }

  /**
   * With the service not yet bound, the play must be deferred to the connection callback rather
   * than dropped — a cold start is the common case for this shelf, since the user has just opened
   * the app.
   */
  @Test
  fun `resume waits for the service when it is not connected`() {
    val onConnected = slot<() -> Unit>()
    val connection =
      mockk<MediaServiceConnection>(relaxed = true) {
        every { isConnected } returns MutableLiveData(false)
        every { transportControls } returns this@HomeResumeTest.transportControls
        every { connect(capture(onConnected)) } returns Unit
      }

    viewModel(connection = connection).resume(book)

    verify(exactly = 0) { transportControls.playFromMediaId(any(), any()) }

    onConnected.captured.invoke()

    verify { transportControls.playFromMediaId(eq("1001"), any()) }
  }

  private fun viewModel(
    connected: Boolean = true,
    connection: MediaServiceConnection =
      mockk(relaxed = true) {
        every { isConnected } returns MutableLiveData(true)
        every { transportControls } returns this@HomeResumeTest.transportControls
      },
  ) = HomeViewModel(
    plexConfig =
      mockk<PlexConfig>(relaxed = true) {
        every { isConnected } returns MutableLiveData(connected)
      },
    bookRepository =
      mockk<IBookRepository>(relaxed = true) {
        every { getRecentlyListened() } returns MutableLiveData(listOf(book))
        every { getRecentlyAdded() } returns MutableLiveData(emptyList())
        every { getCachedAudiobooks() } returns MutableLiveData(emptyList())
      },
    librarySyncRepository = mockk<LibrarySyncRepository>(relaxed = true),
    prefsRepo =
      mockk<PrefsRepo>(relaxed = true) {
        every { offlineMode } returns false
      },
    mediaServiceConnection = connection,
  )
}

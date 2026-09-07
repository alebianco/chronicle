package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.keepCollected
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The playback entry points of `AudiobookDetailsViewModel` — behaviours 1 and 2.
 *
 * Separate from [AudiobookDetailsViewModelTest] because these need a **real `Bundle`**: `pausePlay`
 * builds one for the transport controls, and `Bundle` is unimplemented in the unit-test android.jar.
 * Reimplementing it with mocks would be faking the thing under test, so this class runs on
 * Robolectric while the rest stay on plain JVM mocks.
 *
 * The split is deliberate rather than cosmetic. Robolectric classes are excluded from the PIT run
 * (they report false SURVIVED), so keeping them apart means the other twelve cases still get a
 * mutation score.
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookDetailsPlaybackTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book = Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")

  private val transportControls = mockk<MediaControllerCompat.TransportControls>(relaxed = true)

  private val mediaServiceConnection =
    mockk<MediaServiceConnection>(relaxed = true) {
      every { isConnected } returns MutableStateFlow(true)
      every { nowPlaying } returns MutableStateFlow(mockk<MediaMetadataCompat>(relaxed = true))
      every { this@mockk.transportControls } returns this@AudiobookDetailsPlaybackTest.transportControls
    }

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook("1001") } returns MutableStateFlow(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook("1001") } returns
        MutableStateFlow(emptyList<MediaItemTrack>())
    }

  /** Behaviour 1: play reaches the player with *this* book's id. */
  @Test
  fun `pressing play starts this book by id`() =
    runTest {
      val viewModel = viewModel()
      keepCollected(viewModel.audiobook)

      viewModel.pausePlayButtonClicked()

      verify { transportControls.playFromMediaId(eq("1001"), any()) }
    }

  /**
   * Behaviour 2: a confirmed jump carries the requested offset and track to the player.
   *
   * The offset is what makes a chapter jump land in the right place; dropping it silently starts
   * the book from its saved position instead, which reads as "the jump did nothing".
   */
  @Test
  fun `a confirmed jump plays from the requested position`() =
    runTest {
      val viewModel = viewModel()
      keepCollected(viewModel.audiobook)

      viewModel.jumpToChapter(bookStartTimeOffset = BookOffset(5_000L), trackId = "2001", hasUserConfirmation = true)

      val extras = slot<Bundle>()
      verify { transportControls.playFromMediaId(eq("1001"), capture(extras)) }
      assertEquals(5_000L, extras.captured.getLong(KEY_START_TIME_TRACK_OFFSET))
      assertEquals("2001", extras.captured.getString(KEY_SEEK_TO_TRACK_WITH_ID))
    }

  /** An unconfirmed jump must not reach the player at all. */
  @Test
  fun `an unconfirmed jump does not play`() =
    runTest {
      val viewModel = viewModel()
      keepCollected(viewModel.audiobook)

      viewModel.jumpToChapter(bookStartTimeOffset = BookOffset(5_000L), trackId = "2001")

      verify(exactly = 0) { transportControls.playFromMediaId(any(), any()) }
    }

  private fun viewModel() =
    AudiobookDetailsViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          every { activeBookDownloads } returns MutableStateFlow(emptySet())
        },
      mediaServiceConnection = mediaServiceConnection,
      plexConfig =
        mockk<PlexConfig>(relaxed = true) {
          every { isConnected } returns MutableStateFlow(true)
        },
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
      appContext = mockk<Context>(relaxed = true),
      dispatchers = TestDispatcherProvider(),
      // A real handle, not a mock: it is a plain map, and this is the same path production takes
      // — the Fragment's navigation arguments.
      savedStateHandle =
        SavedStateHandle(
          mapOf(
            AudiobookDetailsViewModel.ARG_AUDIOBOOK_ID to book.id,
            AudiobookDetailsViewModel.ARG_AUDIOBOOK_TITLE to book.title,
          ),
        ),
    )
}

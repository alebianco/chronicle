package io.github.mattpvaughn.chronicle.features.bookdetails

import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater
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
 * The playback entry points of `AudiobookDetailsViewModel` — cu-59 behaviours 1 and 2.
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

  private val book = Audiobook(id = "1001", source = 1L, title = "Dune")

  private val transportControls = mockk<MediaControllerCompat.TransportControls>(relaxed = true)

  private val mediaServiceConnection =
    mockk<MediaServiceConnection>(relaxed = true) {
      every { isConnected } returns MutableLiveData(true)
      every { nowPlaying } returns MutableLiveData(null)
      every { this@mockk.transportControls } returns this@AudiobookDetailsPlaybackTest.transportControls
    }

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook("1001") } returns MutableLiveData(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook("1001") } returns
        MutableLiveData(emptyList<MediaItemTrack>())
    }

  /** cu-59 behaviour 1: play reaches the player with *this* book's id. */
  @Test
  fun `pressing play starts this book by id`() {
    val viewModel = viewModel()
    viewModel.audiobook.observeForever { }

    viewModel.pausePlayButtonClicked()

    verify { transportControls.playFromMediaId(eq("1001"), any()) }
  }

  /**
   * cu-59 behaviour 2: a confirmed jump carries the requested offset and track to the player.
   *
   * The offset is what makes a chapter jump land in the right place; dropping it silently starts
   * the book from its saved position instead, which reads as "the jump did nothing".
   */
  @Test
  fun `a confirmed jump plays from the requested position`() {
    val viewModel = viewModel()
    viewModel.audiobook.observeForever { }

    viewModel.jumpToChapter(offset = 5_000L, trackId = "2001", hasUserConfirmation = true)

    val extras = slot<Bundle>()
    verify { transportControls.playFromMediaId(eq("1001"), capture(extras)) }
    assertEquals(5_000L, extras.captured.getLong(KEY_START_TIME_TRACK_OFFSET))
    assertEquals("2001", extras.captured.getString(KEY_SEEK_TO_TRACK_WITH_ID))
  }

  /** An unconfirmed jump must not reach the player at all. */
  @Test
  fun `an unconfirmed jump does not play`() {
    val viewModel = viewModel()
    viewModel.audiobook.observeForever { }

    viewModel.jumpToChapter(offset = 5_000L, trackId = "2001")

    verify(exactly = 0) { transportControls.playFromMediaId(any(), any()) }
  }

  private fun viewModel() =
    AudiobookDetailsViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          every { activeBookDownloads } returns MutableLiveData(emptySet())
        },
      inputAudiobook = book,
      mediaServiceConnection = mediaServiceConnection,
      progressUpdater = mockk<ProgressUpdater>(relaxed = true),
      plexConfig =
        mockk<PlexConfig>(relaxed = true) {
          every { isConnected } returns MutableLiveData(true)
        },
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
    )
}

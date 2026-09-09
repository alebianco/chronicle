package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import android.support.v4.media.MediaMetadataCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsViewModel
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The details screen's two navigations.
 *
 * The series line is the one worth the most here. It navigates into browse-by-facet, and the
 * `*Destination` reached it through `onSeriesClick = { viewModel.audiobook.value?.let { … } }` —
 * a lambda handed down from the nav graph, wrapped around a read the destination did itself. The
 * player's own Compose migration shipped exactly this kind of silent feature loss and had to
 * recover it, and the comment in the old destination said so.
 *
 * Here it is a `data object` event with a branch in an exhaustive `when`, and this asserts the
 * branch reaches the right screen with the right facet.
 */
class DetailsPresenterTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book =
    Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune", series = "Dune Chronicles")
  private val tracksFlow = MutableStateFlow<List<MediaItemTrack>>(emptyList())

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook("1001") } returns MutableStateFlow(book)
    }

  private val plexConfig =
    mockk<PlexConfig>(relaxed = true) {
      every { isConnected } returns MutableStateFlow(true)
      // `connectionState` needs a real flow: it is one of `uiState`'s four sources, and `combine`
      // emits nothing until every source has produced. A relaxed mock hands back a mocked
      // StateFlow that never emits, so `uiState` silently stays on its seed.
      every { connectionState } returns MutableStateFlow(PlexConfig.ConnectionState.CONNECTED)
    }

  private fun viewModel() =
    AudiobookDetailsViewModel(
      bookRepository = bookRepository,
      trackRepository =
        mockk<ITrackRepository>(relaxed = true) {
          every { getTracksForAudiobook("1001") } returns tracksFlow
        },
      cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          every { activeBookDownloads } returns MutableStateFlow(emptySet())
        },
      mediaServiceConnection =
        mockk<MediaServiceConnection>(relaxed = true) {
          every { nowPlaying } returns MutableStateFlow(mockk<MediaMetadataCompat>(relaxed = true))
          every { isConnected } returns MutableStateFlow(false)
        },
      plexConfig = plexConfig,
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
      appContext = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(),
      bookId = book.id,
    )

  @Test
  fun `the series line opens browse-by-series for this book's series`() =
    runTest {
      val navigator = FakeNavigator(BookDetailsScreenKey("1001"))
      val vm = viewModel()
      val presenter = DetailsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        // The book must have loaded: `SeriesOpened` reads `audiobook.value`, and on the seed it is
        // null and the branch correctly does nothing.
        var state = awaitItem()
        while (vm.audiobook.value == null) {
          state = awaitItem()
        }
        state.eventSink(DetailsEvent.SeriesOpened)

        assertEquals(
          FacetBooksScreenKey(FacetKind.Series, "Dune Chronicles"),
          navigator.awaitNextScreen(),
        )
        cancel()
      }
    }

  @Test
  fun `the back arrow pops the backstack`() =
    runTest {
      val navigator = FakeNavigator(BookDetailsScreenKey("1001"))
      val vm = viewModel()
      val presenter = DetailsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        navigator.assertPopIsEmpty()

        awaitItem().eventSink(DetailsEvent.NavigateUp)

        navigator.awaitPop()
        cancel()
      }
    }

  /**
   * Opening a book navigates nowhere by itself.
   *
   * Worth pinning because `SeriesOpened`'s branch reads a flow: a presenter that navigated on the
   * *state* rather than on the event would send the user to the series list the moment the book
   * loaded, which is a bug no screenshot would show.
   */
  @Test
  fun `opening a book navigates nowhere by itself`() =
    runTest {
      val navigator = FakeNavigator(BookDetailsScreenKey("1001"))
      val vm = viewModel()
      val presenter = DetailsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem()
        awaitItem()

        navigator.assertGoToIsEmpty()
        navigator.assertPopIsEmpty()
        cancel()
      }
    }
}

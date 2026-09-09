package io.github.mattpvaughn.chronicle.features.home.compose

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.home.HomeViewModel
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.HomeScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Home's two book taps, which do **different things** — the distinction this screen exists for.
 *
 * Continue Listening *resumes playback*; every other shelf *opens details*. `HomeDestination`
 * expressed that as `onBookClick` (a lambda from the nav graph) beside `onResumeClick` (a call
 * into the ViewModel), which is two mechanisms for what a reader sees as the same gesture — and
 * nothing stopped a future shelf being wired to the wrong one.
 *
 * As two events with two branches, mixing them up is visible in one `when`; these pin that each
 * still does its own thing.
 */
class HomePresenterTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book = Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")

  private fun presenter(navigator: FakeNavigator): Pair<HomePresenter, HomeViewModel> {
    val vm =
      HomeViewModel(
        plexConfig =
          mockk<PlexConfig>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
          },
        bookRepository =
          mockk<IBookRepository>(relaxed = true) {
            every { getRecentlyListened() } returns MutableStateFlow(listOf(book))
            every { getRecentlyAdded() } returns MutableStateFlow(emptyList())
            every { getCachedAudiobooks() } returns MutableStateFlow(emptyList())
          },
        // `isRefreshing` must be a real flow, not left to the relaxed mock. It passes straight
        // through `HomeViewModel` into the presenter's `collectAsState`, and a relaxed mock hands
        // back a mocked StateFlow whose `value` is a plain `Object` — which fails as a
        // `ClassCastException` inside composition, not at the mock. The details tests hit the same
        // trap with `connectionState`.
        librarySyncRepository =
          mockk<LibrarySyncRepository>(relaxed = true) {
            every { isRefreshing } returns MutableStateFlow(false)
            every { errorMessage } returns MutableStateFlow(null)
          },
        prefsRepo = mockk<PrefsRepo>(relaxed = true) { every { offlineMode } returns false },
        // `isConnected` for the same reason: `resume()` reads `.value` as a Boolean directly, and
        // a relaxed mock's StateFlow yields a plain `Object`.
        mediaServiceConnection =
          mockk<MediaServiceConnection>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
          },
        exceptionHandler = testExceptionHandler(),
      )
    val plexConfig =
      mockk<PlexConfig>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) }
    return HomePresenter({ vm }, plexConfig, navigator) to vm
  }

  @Test
  fun `tapping a book on a shelf opens its details`() =
    runTest {
      val navigator = FakeNavigator(HomeScreenKey)
      val (presenter, _) = presenter(navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem().eventSink(HomeEvent.BookOpened(book))

        assertEquals(BookDetailsScreenKey("1001"), navigator.awaitNextScreen())
        cancel()
      }
    }

  /**
   * Continue Listening resumes instead — so it must navigate **nowhere**.
   *
   * A resume that also opened details would put the player behind a details screen the user did
   * not ask for, which is the exact wiring mistake two lambdas made easy.
   */
  @Test
  fun `resuming a book does not open its details`() =
    runTest {
      val navigator = FakeNavigator(HomeScreenKey)
      val (presenter, _) = presenter(navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem().eventSink(HomeEvent.BookResumed(book))

        navigator.assertGoToIsEmpty()
        cancel()
      }
    }

  /** A bottom-nav root must not navigate on its own when it opens. */
  @Test
  fun `opening home navigates nowhere by itself`() =
    runTest {
      val navigator = FakeNavigator(HomeScreenKey)
      val (presenter, _) = presenter(navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem()

        navigator.assertGoToIsEmpty()
        navigator.assertPopIsEmpty()
        cancel()
      }
    }
}

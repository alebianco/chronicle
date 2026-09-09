package io.github.mattpvaughn.chronicle.features.library.compose

import android.content.Context
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.BrowseScreenKey
import io.github.mattpvaughn.chronicle.navigation.LibraryScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The library's navigation and its two prefs-writing events.
 *
 * Library is the heaviest screen in the app — eleven interactions, where `LibraryDestination` took
 * four lambdas and reached past them into `prefsRepo` and `viewModel` for the other seven. Two of
 * those eleven navigate, and two write straight to preferences; those four are what this covers,
 * because the rest are one-line delegations the exhaustive `when` already protects.
 */
class LibraryPresenterTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val prefs = testSettingsDataStore("library-presenter")
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())
  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { libraryBookViewStyle } returns "COVER_GRID"
      every { bookSortKey } returns "title"
    }
  private val syncRepository = mockk<LibrarySyncRepository>(relaxed = true)

  private fun book(id: String) = Audiobook(id = id, source = TEST_SOURCE, title = "T$id")

  private fun viewModel(): LibraryViewModel {
    every { syncRepository.isRefreshing } returns MutableStateFlow(false)
    every { syncRepository.errorMessage } returns MutableStateFlow(null)
    return LibraryViewModel(
      bookRepository = mockk<IBookRepository>(relaxed = true) { every { getAllBooks() } returns booksFlow },
      trackRepository = mockk<ITrackRepository>(relaxed = true),
      prefsRepo = prefsRepo,
      settings = prefs,
      cachedFileManager = mockk<ICachedFileManager>(relaxed = true),
      librarySyncRepository = syncRepository,
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      appContext = mockk<Context>(relaxed = true),
      dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
    )
  }

  private fun presenter(navigator: FakeNavigator): LibraryPresenter {
    val vm = viewModel()
    return LibraryPresenter(
      { vm },
      prefsRepo,
      mockk<PlexConfig>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) },
      navigator,
    )
  }

  @Test
  fun `tapping a book opens its details`() =
    runTest {
      val navigator = FakeNavigator(LibraryScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(LibraryEvent.BookOpened(book("1001")))

        assertEquals(BookDetailsScreenKey("1001"), navigator.awaitNextScreen())
        cancel()
      }
    }

  /**
   * The browse icon in the toolbar, which `LibraryDestination` wired through an `onBrowseClick`
   * lambda the nav graph supplied — the shape where a forgotten parameter renders a live-looking
   * button that does nothing.
   */
  @Test
  fun `the browse icon opens the browse screen`() =
    runTest {
      val navigator = FakeNavigator(LibraryScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(LibraryEvent.BrowseOpened)

        assertEquals(BrowseScreenKey, navigator.awaitNextScreen())
        cancel()
      }
    }

  /**
   * Sort and view style are **written to preferences**, not held in the presenter.
   *
   * The filter sheet's callbacks did this directly (`onSortKeyChange = { prefsRepo.bookSortKey = it }`)
   * and the state came back round through a prefs-backed flow. Routing them as events must not
   * quietly turn them into presenter state, because then the choice would not survive leaving the
   * screen — so this asserts the write actually happens.
   */
  @Test
  fun `changing the sort key writes it to preferences`() =
    runTest {
      val navigator = FakeNavigator(LibraryScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(LibraryEvent.SortKeyChanged("author"))

        verify { prefsRepo.bookSortKey = "author" }
        cancel()
      }
    }

  @Test
  fun `changing the view style writes it to preferences`() =
    runTest {
      val navigator = FakeNavigator(LibraryScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(LibraryEvent.ViewStyleChanged(PrefsRepo.VIEW_STYLE_TEXT_LIST))

        verify { prefsRepo.libraryBookViewStyle = PrefsRepo.VIEW_STYLE_TEXT_LIST }
        cancel()
      }
    }

  /** A bottom-nav root must not navigate on its own when it opens. */
  @Test
  fun `opening the library navigates nowhere by itself`() =
    runTest {
      val navigator = FakeNavigator(LibraryScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem()

        navigator.assertGoToIsEmpty()
        navigator.assertPopIsEmpty()
        cancel()
      }
    }
}

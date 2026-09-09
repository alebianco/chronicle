package io.github.mattpvaughn.chronicle.features.collections.compose

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.collections.CollectionsViewModel
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.CollectionDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.CollectionsScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The collections list navigates to two *different* screens, and that is the point of the test.
 *
 * The list itself opens a collection; the search overlay drawn over it opens a **book**. Under
 * `CollectionsDestination` these were two lambdas, `onCollectionClick` and `onBookClick`, and
 * swapping them compiled fine — both take one argument and neither returns anything. Here they are
 * separate events carrying different types, so the mix-up is a compile error; this pins that each
 * still reaches the screen it should.
 */
class CollectionsPresenterTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val prefs = testSettingsDataStore("collections-presenter")
  private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())

  private fun presenter(navigator: FakeNavigator): CollectionsPresenter {
    val vm =
      CollectionsViewModel(
        prefsRepo = mockk<PrefsRepo>(relaxed = true),
        librarySyncRepository =
          mockk<LibrarySyncRepository>(relaxed = true) {
            every { isRefreshing } returns MutableStateFlow(false)
            every { errorMessage } returns MutableStateFlow(null)
          },
        collectionsRepository =
          mockk<CollectionsRepository> {
            every { getAllCollections() } returns collectionsFlow
          },
        settings = prefs,
        bookRepository = mockk<BookRepository>(relaxed = true),
        exceptionHandler = CoroutineExceptionHandler { _, _ -> },
        dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
      )
    return CollectionsPresenter(
      { vm },
      mockk<PlexConfig>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) },
      navigator,
    )
  }

  @Test
  fun `tapping a collection opens that collection`() =
    runTest {
      val navigator = FakeNavigator(CollectionsScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(
          CollectionsEvent.CollectionOpened(
            Collection(id = "c1", source = TEST_SOURCE, title = "Favourites"),
          ),
        )

        assertEquals(CollectionDetailsScreenKey("c1"), navigator.awaitNextScreen())
        cancel()
      }
    }

  /** From the search overlay, which lists books rather than collections. */
  @Test
  fun `tapping a search result opens the book, not a collection`() =
    runTest {
      val navigator = FakeNavigator(CollectionsScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem().eventSink(
          CollectionsEvent.BookOpened(Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")),
        )

        assertEquals(BookDetailsScreenKey("1001"), navigator.awaitNextScreen())
        cancel()
      }
    }

  /** A bottom-nav root must not navigate on its own when it opens. */
  @Test
  fun `opening collections navigates nowhere by itself`() =
    runTest {
      val navigator = FakeNavigator(CollectionsScreenKey)

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator).present() }.test {
        awaitItem()

        navigator.assertGoToIsEmpty()
        navigator.assertPopIsEmpty()
        cancel()
      }
    }
}

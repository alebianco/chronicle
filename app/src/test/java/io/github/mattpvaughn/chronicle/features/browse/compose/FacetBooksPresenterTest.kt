package io.github.mattpvaughn.chronicle.features.browse.compose

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.browse.FacetBooksViewModel
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The facet-books screen, and the argument round trip this migration removed.
 *
 * The toolbar title used to be read **back out of the route** — the nav graph pulled the path
 * segment and ran `decodeArg` on it, because the value had been percent-encoded on the way in to
 * survive being a URL path segment at all. A raw `/` or `?` in the value silently failed to match
 * the route pattern and navigated nowhere with no error.
 *
 * The value carrying punctuation is the case that used to break, so it is the one asserted.
 */
class FacetBooksPresenterTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val prefs = testSettingsDataStore("facet-presenter")

  private fun presenter(
    navigator: FakeNavigator,
    value: String,
  ): FacetBooksPresenter {
    val vm =
      FacetBooksViewModel(
        bookRepository =
          mockk<IBookRepository>(relaxed = true) {
            every { getAllBooks() } returns MutableStateFlow(emptyList())
          },
        settings = prefs,
        kind = FacetKind.Narrator,
        value = value,
      )
    return FacetBooksPresenter(
      { vm },
      mockk<PlexConfig>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) },
      navigator,
      title = value,
    )
  }

  /**
   * A value with `/` and `?` in it reaches the toolbar intact.
   *
   * Under route strings this needed `encodeArg` on the way in and `decodeArg` on the way out, and
   * getting either wrong showed the user `The%2FHobbit` or navigated nowhere. The screen key
   * carries the string, so there is nothing to encode.
   */
  @Test
  fun `a facet value carrying punctuation reaches the title unchanged`() =
    runTest {
      val value = "Whitfield, June/Nunn?"
      val navigator = FakeNavigator(FacetBooksScreenKey(FacetKind.Narrator, value))

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator, value).present() }.test {
        assertEquals(value, awaitItem().title)
        cancelAndIgnoreRemainingEvents()
      }
    }

  @Test
  fun `tapping a book opens its details`() =
    runTest {
      val value = "Kramer, Michael"
      val navigator = FakeNavigator(FacetBooksScreenKey(FacetKind.Narrator, value))

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator, value).present() }.test {
        awaitItem().eventSink(
          FacetBooksEvent.BookOpened(Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")),
        )

        assertEquals(BookDetailsScreenKey("1001"), navigator.awaitNextScreen())
        cancelAndIgnoreRemainingEvents()
      }
    }

  @Test
  fun `the back arrow pops the backstack`() =
    runTest {
      val value = "Kramer, Michael"
      val navigator = FakeNavigator(FacetBooksScreenKey(FacetKind.Narrator, value))

      moleculeFlow(RecompositionMode.Immediate) { presenter(navigator, value).present() }.test {
        navigator.assertPopIsEmpty()

        awaitItem().eventSink(FacetBooksEvent.NavigateUp)

        navigator.awaitPop()
        cancelAndIgnoreRemainingEvents()
      }
    }
}

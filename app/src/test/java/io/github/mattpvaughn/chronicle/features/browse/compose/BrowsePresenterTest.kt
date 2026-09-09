package io.github.mattpvaughn.chronicle.features.browse.compose

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.features.browse.BrowseViewModel
import io.github.mattpvaughn.chronicle.navigation.BrowseScreenKey
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The browse screen's navigation, which is the interesting half of what Circuit changed here.
 *
 * `BrowseDestination` took an `onFacetClick: (FacetKind, Facet) -> Unit` and passed the *kind* from
 * its own state and the *facet* from the row — so the nav graph then had to build a route from two
 * values it did not own. This asserts the presenter builds the screen key itself, with both halves
 * correct, which is the thing that used to be spread across two files.
 */
class BrowsePresenterTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun book(
    id: String,
    author: String = "Sanderson, Brandon",
    series: String = "",
  ) = Audiobook(id = id, source = TEST_SOURCE, title = "T$id", author = author, series = series)

  private fun viewModel(books: List<Audiobook>): BrowseViewModel {
    val repo =
      mockk<IBookRepository>(relaxed = true) {
        every { getAllBooks() } returns MutableStateFlow(books)
      }
    return BrowseViewModel(repo)
  }

  @Test
  fun `tapping a facet navigates with the value and the tab currently showing`() =
    runTest {
      val navigator = FakeNavigator(BrowseScreenKey)
      val vm = viewModel(listOf(book("1"), book("2")))
      val presenter = BrowsePresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem().eventSink(BrowseEvent.FacetOpened(Facet("Sanderson, Brandon", 2)))

        // Author is the default tab, and the *kind* comes from the presenter's own state rather
        // than from the event — the row only knows its own value.
        assertEquals(
          FacetBooksScreenKey(FacetKind.Author, "Sanderson, Brandon"),
          navigator.awaitNextScreen(),
        )
        cancel()
      }
    }

  /**
   * The kind must follow the selected tab, not stay pinned to the default.
   *
   * This is the branch a two-lambda `*Destination` made easy to get wrong: `onFacetClick` was
   * invoked as `onFacetClick(state.selected, it)`, and reading the wrong `selected` — a captured
   * one, say — would send the user to the right value under the wrong facet, which looks like an
   * empty screen rather than like a bug.
   */
  @Test
  fun `switching tabs changes which facet kind a tap navigates to`() =
    runTest {
      val navigator = FakeNavigator(BrowseScreenKey)
      val vm = viewModel(listOf(book("1", series = "Mistborn")))
      val presenter = BrowsePresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem().eventSink(BrowseEvent.FacetKindSelected(FacetKind.Series))

        // Recomposes with the new tab, then the tap is dispatched against it.
        var state = awaitItem()
        while (state.ui.selected != FacetKind.Series) {
          state = awaitItem()
        }
        state.eventSink(BrowseEvent.FacetOpened(Facet("Mistborn", 1)))

        assertEquals(
          FacetBooksScreenKey(FacetKind.Series, "Mistborn"),
          navigator.awaitNextScreen(),
        )
        cancel()
      }
    }

  /**
   * **The branch a `*Destination` could silently drop.** Back was an `onNavigateUp` lambda from
   * the nav graph; forget it and the arrow renders and does nothing, which only a person tapping
   * it on a device finds out.
   */
  @Test
  fun `the back arrow pops the backstack`() =
    runTest {
      val navigator = FakeNavigator(BrowseScreenKey)
      val vm = viewModel(emptyList())
      val presenter = BrowsePresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        navigator.assertPopIsEmpty()

        awaitItem().eventSink(BrowseEvent.NavigateUp)

        navigator.awaitPop()
        cancel()
      }
    }
}

package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.features.settings.SeriesIndexTesterViewModel
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The first Circuit presenter in the tree (decision-27), and the test that shows what the migration
 * is *for*.
 *
 * ### Why this is different from a `*Destination` test
 *
 * There was no `*Destination` test, and that is the point rather than an oversight. A
 * `*Destination` is a composable wiring `hiltViewModel()` to method references — reaching it needs
 * a Compose rule, a Hilt graph and an Android runtime, so in practice the wiring went unasserted
 * and only the ViewModel underneath was tested. A missed lambda was invisible.
 *
 * A presenter is a plain object. It runs here in a **plain JVM test** — no Robolectric, no Compose
 * rule — through Molecule's documented `moleculeFlow(Immediate).test { }` recipe, which is what
 * `circuit-test` uses internally.
 *
 * ### What is actually asserted
 *
 * That **every event in the sealed hierarchy does something**, and that navigation is one of them.
 * The `when` in the presenter is exhaustive, so a fourth event cannot be added and left unwired —
 * that is a compile error rather than a test this file would have to remember to update. What a
 * test can still add is proof that each existing branch reaches the right place.
 */
class SeriesIndexTesterPresenterTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun book(
    id: String,
    titleSort: String,
  ) = Audiobook(id = id, source = TEST_SOURCE, title = "T$id", titleSort = titleSort)

  private fun TestScope.viewModel(books: List<Audiobook> = emptyList()): SeriesIndexTesterViewModel {
    val repo =
      mockk<IBookRepository>(relaxed = true) {
        coEvery { getAllBooksAsync() } returns books
      }
    val dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler)
    return SeriesIndexTesterViewModel(repo, dispatchers, testExceptionHandler())
  }

  @Test
  fun `typing a title sort runs the rules and reaches the state`() =
    runTest {
      val presenter = SeriesIndexTesterPresenter(viewModel(), FakeNavigator(SeriesIndexTesterScreenKey))

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        assertEquals("", awaitItem().ui.titleSort)

        awaitItem().eventSink(
          SeriesIndexTesterEvent.TitleSortChanged("Mistborn, Book 2 - The Well of Ascension"),
        )

        val typed = awaitItem()
        assertEquals("Mistborn, Book 2 - The Well of Ascension", typed.ui.titleSort)
        assertTrue(
          "typing a parseable title must produce rule attempts",
          typed.ui.attempts.isNotEmpty(),
        )
        cancel()
      }
    }

  /**
   * `SampleChosen` is a *different event* from `TitleSortChanged` even though the ViewModel handles
   * them the same way today. Keeping them distinct is what lets the two diverge later — loading a
   * sample could reasonably scroll or announce something typing should not — without either becoming
   * a boolean parameter on the other.
   */
  @Test
  fun `choosing a sample loads it into the input`() =
    runTest {
      val presenter = SeriesIndexTesterPresenter(viewModel(), FakeNavigator(SeriesIndexTesterScreenKey))

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem()
        awaitItem().eventSink(SeriesIndexTesterEvent.SampleChosen("Dune, Book 1 - Dune"))

        assertEquals("Dune, Book 1 - Dune", awaitItem().ui.titleSort)
        cancel()
      }
    }

  /**
   * **The branch a `*Destination` could silently drop.** Navigation used to be an `onNavigateUp`
   * lambda passed down from the nav graph; if it were forgotten, the back arrow would render and do
   * nothing, and only a person tapping it on a device would find out — which is how the hidden
   * back arrow survived for months.
   *
   * Here it is an event with a branch in an exhaustive `when`, and this asserts it reaches the
   * navigator.
   */
  @Test
  fun `the back arrow pops the backstack`() =
    runTest {
      val navigator = FakeNavigator(SeriesIndexTesterScreenKey)
      val presenter = SeriesIndexTesterPresenter(viewModel(), navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        navigator.expectNoPopEvents()

        awaitItem().eventSink(SeriesIndexTesterEvent.NavigateUp)

        // Circuit's own FakeNavigator rather than a hand-rolled one: `Navigator` gained members
        // between minors, and a fake written against today's interface is a compile break waiting
        // for the next 0.x bump. `awaitPop` suspends, so this is not the "not yet" trap that
        // `expectNoEvents` is.
        navigator.awaitPop()
        cancel()
      }
    }
}

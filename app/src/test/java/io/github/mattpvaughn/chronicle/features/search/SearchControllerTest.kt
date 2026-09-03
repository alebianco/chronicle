package io.github.mattpvaughn.chronicle.features.search

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.GroupedSearchResults
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.data.model.groupedSearch
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The search controller debounces and never answers a stale query (cu-25).
 *
 * These are the two properties that cannot be seen by inspection: before this, each screen ran a
 * database read per keystroke, and two racing searches could deliver out of order and leave the
 * list showing the answer to a query the user had already moved past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  private val library =
    listOf(
      Audiobook(id = "1", source = 1L, title = "The Hobbit", author = "J R R Tolkien"),
      Audiobook(id = "2", source = 1L, title = "Dune", author = "Frank Herbert"),
      Audiobook(
        id = "3",
        source = 1L,
        title = "Mistborn",
        author = "Brandon Sanderson",
        narrator = "Michael Kramer",
      ),
    )

  /** Counts calls so the debounce can be asserted on, not just the final answer. */
  private class CountingRepo(private val books: List<Audiobook>) {
    var calls = 0
      private set

    fun repo(): IBookRepository =
      mockk<IBookRepository>(relaxed = true).also { repo ->
        coEvery { repo.searchGrouped(any()) } answers
          {
            calls++
            books.groupedSearch(firstArg())
          }
      }
  }

  @Test
  fun `a search publishes grouped rows`() =
    runTest {
      val controller = SearchController(CountingRepo(library).repo(), this)

      controller.search("hobit")
      advanceUntilIdle()

      val rows = controller.rows.value.orEmpty()
      assertEquals(
        listOf(SearchField.Title),
        rows.filterIsInstance<SearchRow.Header>().map { it.field },
      )
      assertEquals(listOf("1"), rows.filterIsInstance<SearchRow.Book>().map { it.book.id })
    }

  /**
   * Typing a word costs one query, not one per letter.
   *
   * This is the whole reason the controller exists: the three screens it replaces each called the
   * repository straight from the `SearchView` listener.
   */
  @Test
  fun `typing quickly runs a single search`() =
    runTest {
      val counting = CountingRepo(library)
      val controller = SearchController(counting.repo(), this)

      "hobbit".forEachIndexed { index, _ ->
        controller.search("hobbit".substring(0, index + 1))
        advanceTimeBy(50)
      }
      advanceUntilIdle()

      assertEquals(1, counting.calls)
    }

  @Test
  fun `pausing between words runs a search per pause`() =
    runTest {
      val counting = CountingRepo(library)
      val controller = SearchController(counting.repo(), this)

      controller.search("dune")
      advanceUntilIdle()
      controller.search("hobbit")
      advanceUntilIdle()

      assertEquals(2, counting.calls)
    }

  /**
   * The last query typed is the one shown.
   *
   * Asserted on the *result*, not just the call count: a cancelled search that still published
   * would overwrite the newer answer with an older one.
   */
  @Test
  fun `a superseded query never overwrites a newer result`() =
    runTest {
      val controller = SearchController(CountingRepo(library).repo(), this)

      controller.search("dune")
      advanceTimeBy(100)
      controller.search("hobbit")
      advanceUntilIdle()

      assertEquals(
        listOf("1"),
        controller.rows.value.orEmpty().filterIsInstance<SearchRow.Book>().map { it.book.id },
      )
    }

  @Test
  fun `an empty query clears the results without querying`() =
    runTest {
      val counting = CountingRepo(library)
      val controller = SearchController(counting.repo(), this)

      controller.search("dune")
      advanceUntilIdle()
      controller.search("")
      advanceUntilIdle()

      assertTrue(controller.rows.value.orEmpty().isEmpty())
      assertEquals(true, controller.isQueryEmpty.value)
      assertEquals(1, counting.calls)
    }

  @Test
  fun `a whitespace-only query counts as empty`() =
    runTest {
      val counting = CountingRepo(library)
      val controller = SearchController(counting.repo(), this)

      controller.search("   ")
      advanceUntilIdle()

      assertEquals(true, controller.isQueryEmpty.value)
      assertEquals(0, counting.calls)
    }

  @Test
  fun `closing search clears the results`() =
    runTest {
      val controller = SearchController(CountingRepo(library).repo(), this)
      controller.search("dune")
      advanceUntilIdle()

      controller.setSearchActive(false)
      advanceUntilIdle()

      assertTrue(controller.rows.value.orEmpty().isEmpty())
      assertEquals(false, controller.isSearchActive.value)
    }

  /**
   * A failing search shows nothing, not the previous query's answer.
   *
   * Leaving stale rows on screen would read as "these are the matches for what you just typed",
   * which is a wrong claim rather than a missing one.
   */
  @Test
  fun `a repository failure clears the results rather than leaving stale ones`() =
    runTest {
      val repo = mockk<IBookRepository>(relaxed = true)
      var shouldFail = false
      coEvery { repo.searchGrouped(any()) } answers
        {
          if (shouldFail) throw IllegalStateException("db gone") else library.groupedSearch(firstArg())
        }
      val controller = SearchController(repo, this)

      controller.search("dune")
      advanceUntilIdle()
      assertTrue(controller.rows.value.orEmpty().isNotEmpty())

      shouldFail = true
      controller.search("hobbit")
      advanceUntilIdle()

      assertTrue(controller.rows.value.orEmpty().isEmpty())
    }

  @Test
  fun `results and rows agree`() =
    runTest {
      val controller = SearchController(CountingRepo(library).repo(), this)

      controller.search("Kramer")
      advanceUntilIdle()

      val grouped: GroupedSearchResults = controller.results.value!!
      assertEquals(grouped.toRows(), controller.rows.value)
      assertEquals(SearchField.Narrator, grouped.groups.single().field)
    }
}

package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.groupedSearch
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.testing.TEST_SERVER_ID
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The projection search must return exactly what the whole-table search returned.
 *
 * The optimisation is only safe if it is invisible. So rather than asserting a handful of expected
 * results, each case runs the **old** path — `getAllBooksAsync(...).groupedSearch(query)`, which is
 * what `searchGrouped` did before — over the same real database and asserts the two agree on
 * groups, order, ids, matched values and counts.
 *
 * the rules are what would break silently here: the fuzzy tier, the 4-character floor and the
 * character-count prefilter all run over strings that now arrive from a different query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchProjectionEquivalenceTest {
  private lateinit var db: BookDatabase

  private val prefsRepo = mockk<PrefsRepo>(relaxed = true) { every { offlineMode } returns false }

  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { server } returns ServerModel(name = "T", connections = emptyList(), serverId = TEST_SERVER_ID)
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java).allowMainThreadQueries().build()
    db.bookDao.insertAll(library)
  }

  @After
  fun tearDown() = db.close()

  private val library =
    listOf(
      book("1", "Dune", "Frank Herbert", "Scott Brick", "Dune"),
      book("2", "Dune Messiah", "Frank Herbert", "Scott Brick", "Dune"),
      book("3", "The Well of Ascension", "Brandon Sanderson", "Michael Kramer, Kate Reading", "Mistborn"),
      book("4", "Mistborn", "Brandon Sanderson", "Michael Kramer", "Mistborn"),
      book("5", "Ender's Game", "Orson Scott Card", "Stefan Rudnicki", ""),
      book("6", "Xenos", "Dan Abnett", "Toby Longworth", "Eisenhorn"),
    )

  private fun book(
    id: String,
    title: String,
    author: String,
    narrator: String,
    series: String,
  ) = Audiobook(
    id = id,
    source = TEST_SOURCE,
    title = title,
    titleSort = title,
    author = author,
    narrator = narrator,
    series = series,
    // Columns the projection deliberately omits. If the swap-back ever failed, these would come
    // out empty and the equivalence assertion below would catch it.
    summary = "Summary for $title",
    thumb = "/library/metadata/$id/thumb/1",
    duration = 3_600_000L,
  )

  private fun repository() =
    BookRepository(
      bookDao = db.bookDao,
      chapterDao = mockk(relaxed = true),
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      dispatchers = TestDispatcherProvider(),
    )

  /** The path `searchGrouped` used before the projection-search rewrite, run over the same database. */
  private fun wholeTableSearch(query: String) = db.bookDao.getAllBooksAsync(TEST_SOURCE, false).groupedSearch(query)

  private fun assertSameResults(query: String) =
    runTest {
      val expected = wholeTableSearch(query)
      val actual = repository().searchGrouped(query)

      assertEquals(
        "group fields must match for '$query'",
        expected.groups.map { it.field },
        actual.groups.map { it.field },
      )
      expected.groups.zip(actual.groups).forEach { (e, a) ->
        assertEquals("ids and order must match in ${e.field} for '$query'", e.results.map { it.book.id }, a.results.map { it.book.id })
        assertEquals("matched values must match in ${e.field} for '$query'", e.values, a.values)
        assertEquals("counts must match in ${e.field} for '$query'", e.count, a.count)
        assertEquals("scores must match in ${e.field} for '$query'", e.results.map { it.score }, a.results.map { it.score })
      }
    }

  @Test fun `an exact title matches identically`() = assertSameResults("Dune")

  @Test fun `a partial title matches identically`() = assertSameResults("Mist")

  @Test fun `an author matches identically`() = assertSameResults("Sanderson")

  @Test fun `a narrator matches identically`() = assertSameResults("Kramer")

  @Test fun `a series matches identically`() = assertSameResults("Eisenhorn")

  /** the Damerau tier: a transposition is the commonest typo and must still be tolerated. */
  @Test fun `a transposition still matches identically`() = assertSameResults("Dnue")

  @Test fun `a one-character typo still matches identically`() = assertSameResults("Mistbron")

  /** Below the 4-character floor, only prefix and substring match. */
  @Test fun `a short query matches identically`() = assertSameResults("Du")

  @Test fun `a query matching nothing matches identically`() = assertSameResults("zzzzzz")

  @Test fun `a query matching most of the library matches identically`() = assertSameResults("e")

  /**
   * The whole point of the swap-back: a result must carry the **real** book, not the projection
   * stub. The stub has no summary, thumb or duration, so asserting one of those is present is how
   * a failed swap would be caught.
   */
  @Test
  fun `results carry the full book, not the projection stub`() =
    runTest {
      val results = repository().searchGrouped("Dune")
      val first = results.groups.first().results.first().book

      assertEquals("Summary for Dune", first.summary)
      assertEquals("/library/metadata/1/thumb/1", first.thumb)
      assertEquals(3_600_000L, first.duration)
      assertTrue("the real row carries its source", first.source == TEST_SOURCE)
    }

  /** Another source's books must stay invisible, as they were before. */
  @Test
  fun `the projection is source-scoped`() =
    runTest {
      db.bookDao.insertAll(
        listOf(
          book(
            "99",
            "Dune",
            "Frank Herbert",
            "Scott Brick",
            "Dune",
          ).copy(source = io.github.mattpvaughn.chronicle.testing.OTHER_TEST_SOURCE),
        ),
      )

      val ids = repository().searchGrouped("Dune").groups.flatMap { g -> g.results.map { it.book.id } }

      assertTrue("another server's book must not appear; got $ids", !ids.contains("99"))
    }
}

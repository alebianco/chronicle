package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.groupedSearch
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureNanoTime

/**
 * Measures what `searchGrouped`'s whole-table read actually costs.
 *
 * A **measurement harness**, not a gate: it prints and asserts nothing tight, because a wall-clock
 * number under Robolectric on a laptop is not a device number and must not fail a build on a busy
 * machine. It exists so the "is this worth fixing" question is answered with numbers rather than
 * by reading the query — two separate past attempts at reading it each recorded confident wrong
 * answers here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchReadCostTest {
  private lateinit var db: BookDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private companion object {
    /** A real query shape: an author surname, which matches a meaningful slice but not most. */
    const val QUERY = "Sanderson"

    val TITLES = listOf("Dune", "Mistborn", "Xenos", "Hyperion", "Neuromancer", "Foundation", "Ubik", "Solaris")
    val WORDS = listOf("Empire", "Shadow", "Crimson", "Silent", "Northern", "Broken", "Gilded", "Hollow")
    val FIRST = listOf("Frank", "Brandon", "Dan", "Orson", "Iain", "Ursula", "Ann", "Becky")
    val LAST = listOf("Herbert", "Sanderson", "Abnett", "Card", "Banks", "LeGuin", "Leckie", "Chambers")
  }

  private fun library(size: Int): List<Audiobook> =
    (1..size).map { i ->
      Audiobook(
        id = i.toString(),
        source = TEST_SOURCE,
        // Names drawn from a varied pool rather than a numeric suffix. A library of
        // "Series 1..200" makes any query fuzzy-match hundreds of neighbours, which is a fixture
        // artefact, not a real search — and it dominates the measurement.
        title = "${TITLES[i % TITLES.size]} ${WORDS[i % WORDS.size]} ${i / 97}",
        titleSort = "${TITLES[i % TITLES.size]} $i",
        author = "${FIRST[i % FIRST.size]} ${LAST[(i / 7) % LAST.size]}",
        narrator = "${FIRST[(i / 3) % FIRST.size]} ${LAST[i % LAST.size]}",
        series = "${WORDS[(i / 11) % WORDS.size]} ${TITLES[(i / 13) % TITLES.size]}",
        summary = "A summary long enough to be worth materialising, repeated. ".repeat(4),
        thumb = "/library/metadata/$i/thumb/12345",
      )
    }

  @Test
  fun `report the whole-table read cost at ten thousand books`() =
    runTest {
      db.bookDao.insertAll(library(10_000))

      // Warm: the first read pays for statement preparation and page cache.
      db.bookDao.getAllBooksAsync(TEST_SOURCE, false)

      val runs = 5
      val timings =
        (1..runs).map {
          measureNanoTime { db.bookDao.getAllBooksAsync(TEST_SOURCE, false) } / 1_000_000.0
        }
      val median = timings.sorted()[runs / 2]

      println("search-read-cost: whole-table read, 10k books: median ${"%.1f".format(median)} ms, all=$timings")
    }

  /** The 196-book library the household actually has, for scale. */
  @Test
  fun `report the whole-table read cost at the real library size`() =
    runTest {
      db.bookDao.insertAll(library(196))
      db.bookDao.getAllBooksAsync(TEST_SOURCE, false)

      val timings =
        (1..5).map { measureNanoTime { db.bookDao.getAllBooksAsync(TEST_SOURCE, false) } / 1_000_000.0 }
      println("search-read-cost: whole-table read, 196 books: median ${"%.2f".format(timings.sorted()[2])} ms, all=$timings")
    }

  /**
   * The hypothesis this proposes: match on a projection, then fetch only the hits in full.
   *
   * Measured rather than assumed. If the projection read plus a second keyed read is not
   * materially cheaper than one whole-table read, the change is not worth its complexity — and a
   * search that matches *most* of the library pays for both reads, which is the case that could
   * make it worse rather than better.
   */
  @Test
  fun `report the projection read cost at ten thousand books`() =
    runTest {
      db.bookDao.insertAll(library(10_000))
      db.bookDao.searchProjection(TEST_SOURCE, false)

      val timings =
        (1..5).map { measureNanoTime { db.bookDao.searchProjection(TEST_SOURCE, false) } / 1_000_000.0 }
      println("search-read-cost: projection read, 10k books: median ${"%.1f".format(timings.sorted()[2])} ms, all=$timings")
    }

  /** The second half of the hypothesis: fetching a realistic number of hits by id. */
  @Test
  fun `report the cost of fetching the matching books by id`() =
    runTest {
      db.bookDao.insertAll(library(10_000))
      val ids = (1..50).map { it.toString() }
      db.bookDao.getAudiobooksByIds(TEST_SOURCE, ids)

      val timings =
        (1..5).map { measureNanoTime { db.bookDao.getAudiobooksByIds(TEST_SOURCE, ids) } / 1_000_000.0 }
      println("search-read-cost: fetch 50 by id, 10k books: median ${"%.2f".format(timings.sorted()[2])} ms, all=$timings")
    }

  /**
   * The number the acceptance criterion asks for: a whole `searchGrouped` at 10,000 books,
   * old path against new, on the same data in the same run.
   */
  @Test
  fun `report searchGrouped before and after at ten thousand books`() =
    runTest {
      db.bookDao.insertAll(library(10_000))
      val prefs = io.mockk.mockk<PrefsRepo>(relaxed = true) { io.mockk.every { offlineMode } returns false }
      val plexPrefs =
        io.mockk.mockk<io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo>(relaxed = true) {
          io.mockk.every { server } returns
            io.github.mattpvaughn.chronicle.data.model.ServerModel(
              name = "T",
              connections = emptyList(),
              serverId = io.github.mattpvaughn.chronicle.testing.TEST_SERVER_ID,
            )
        }
      val repo =
        BookRepository(
          bookDao = db.bookDao,
          chapterDao = io.mockk.mockk(relaxed = true),
          prefsRepo = prefs,
          plexPrefsRepo = plexPrefs,
          plexMediaService = io.mockk.mockk(relaxed = true),
          dispatchers = io.github.mattpvaughn.chronicle.util.TestDispatcherProvider(),
        )

      // Warm both paths.
      db.bookDao.getAllBooksAsync(TEST_SOURCE, false).groupedSearch(QUERY)
      repo.searchGrouped(QUERY)

      val before =
        (1..5).map {
          measureNanoTime {
            db.bookDao.getAllBooksAsync(TEST_SOURCE, false).groupedSearch(QUERY)
          } / 1_000_000.0
        }.sorted()[2]
      val after = (1..5).map { measureNanoTime { repo.searchGrouped(QUERY) } / 1_000_000.0 }.sorted()[2]

      val hits = repo.searchGrouped(QUERY).groups.sumOf { it.count }
      println(
        "search-read-cost: searchGrouped 10k books: before ${"%.1f".format(before)} ms, " +
          "after ${"%.1f".format(after)} ms (${"%.0f".format((1 - after / before) * 100)}% less), " +
          "hits=$hits",
      )
    }
}

package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grouped search reads through the repository correctly (cu-25).
 *
 * Against a **real database**, because the claim being tested is about the offline-mode contract:
 * every other read here filters on `isCached >= :offlineModeActive`, and a search that forgot to
 * would show the user books they cannot play on a train. A mocked DAO would answer whatever it was
 * told and prove nothing about that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchGroupedTest {
  private lateinit var bookDb: BookDatabase

  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)
  private val chapterDao = mockk<ChapterDao>(relaxed = true)
  private val plexPrefsRepo = mockk<PlexPrefsRepo>(relaxed = true)

  private var offline = false

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } answers { offline }
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    bookDb =
      Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    bookDb.close()
  }

  private fun TestScope.bookRepository() =
    BookRepository(
      bookDao = bookDb.bookDao,
      chapterDao = chapterDao,
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = plexMediaService,
      dispatchers = TestDispatcherProvider(testScheduler),
    )

  private suspend fun libraryOf(vararg books: Audiobook) {
    bookDb.bookDao.insertAll(books.toList())
  }

  private fun book(
    id: String,
    title: String,
    author: String = "",
    narrator: String = "",
    series: String = "",
    isCached: Boolean = false,
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    author = author,
    narrator = narrator,
    series = series,
    isCached = isCached,
  )

  @Test
  fun `a typo in a title still finds the book through the repository`() =
    runTest {
      libraryOf(book("1001", title = "The Hobbit", author = "J R R Tolkien"))

      val grouped = bookRepository().searchGrouped("hobit")

      assertEquals(listOf("1001"), grouped.groups.flatMap { g -> g.results.map { it.book.id } })
    }

  @Test
  fun `a narrator is searchable, which the plain LIKE search could not do`() =
    runTest {
      libraryOf(book("1003", title = "Mistborn", narrator = "Michael Kramer"))

      val grouped = bookRepository().searchGrouped("Kramer")

      assertEquals(SearchField.Narrator, grouped.groups.single().field)
    }

  /**
   * Offline mode must hide uncached books, exactly as every other read does.
   *
   * This is the whole reason the search reads through `getAllBooksAsync` rather than its own
   * query — that method already applies the `isCached >= :offlineModeActive` filter.
   */
  @Test
  fun `offline mode hides books that are not downloaded`() =
    runTest {
      libraryOf(
        book("1001", title = "The Hobbit", isCached = true),
        book("1002", title = "The Hobbit Companion", isCached = false),
      )
      offline = true

      val grouped = bookRepository().searchGrouped("hobbit")

      assertEquals(
        listOf("1001"),
        grouped.groups.flatMap { g -> g.results.map { it.book.id } },
      )
    }

  @Test
  fun `online mode shows books that are not downloaded`() =
    runTest {
      libraryOf(
        book("1001", title = "The Hobbit", isCached = true),
        book("1002", title = "The Hobbit Companion", isCached = false),
      )
      offline = false

      val grouped = bookRepository().searchGrouped("hobbit")

      assertEquals(
        setOf("1001", "1002"),
        grouped.groups.flatMap { g -> g.results.map { it.book.id } }.toSet(),
      )
    }

  @Test
  fun `an empty library answers nothing rather than failing`() =
    runTest {
      val grouped = bookRepository().searchGrouped("anything")

      assertTrue(grouped.isEmpty)
    }
}

package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bookmarks survive a library re-sync (cu-22, criterion 2).
 *
 * Against **real databases**, both of them, and driving the real `refreshData` — because the claim
 * is about what the sync path does to another table, and a mocked DAO cannot answer that. The
 * strongest case is the one below where the book itself is deleted: a refresh removes books the
 * server no longer lists, and the bookmark must still be there afterwards.
 *
 * The property holds *structurally* — bookmarks are a separate database keyed by `bookId`, so no
 * query in the sync path can reach them — and this test exists to keep it that way. Moving
 * bookmarks into `BookDatabase` would break it, which is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookmarkSurvivesSyncTest {
  private lateinit var bookDb: BookDatabase
  private lateinit var bookmarkDb: BookmarkDatabase
  private lateinit var bookmarks: BookmarkRepository

  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)
  private val chapterDao = mockk<ChapterDao>(relaxed = true)

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
      every { lastRefreshTimeStamp = any() } returns Unit
    }

  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { library } returns PlexLibrary(name = "Books", type = MediaType.ARTIST, id = "1")
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    bookDb =
      Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    bookmarkDb =
      Room.inMemoryDatabaseBuilder(context, BookmarkDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    bookmarks = BookmarkRepository(bookmarkDb.bookmarkDao, TestDispatcherProvider())
  }

  @After
  fun tearDown() {
    bookDb.close()
    bookmarkDb.close()
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

  private fun serverHas(vararg books: PlexDirectory) {
    coEvery { plexMediaService.retrieveAllAlbums(any()) } returns
      PlexMediaContainerWrapper(PlexMediaContainer(metadata = books.toList()))
  }

  @Test
  fun `a bookmark survives an ordinary refresh`() =
    runTest {
      bookDb.bookDao.insertAll(listOf(Audiobook(id = "1001", source = 1L, title = "The Hobbit")))
      bookmarks.add(bookId = "1001", position = BookOffset(90_000L), note = "the riddle game")
      serverHas(PlexDirectory(ratingKey = "1001", title = "The Hobbit"))

      bookRepository().refreshData()

      val stored = bookmarks.getBookmarksForBookAsync("1001")
      assertEquals(1, stored.size)
      assertEquals("the riddle game", stored.single().note)
      assertEquals(BookOffset(90_000L), stored.single().position)
    }

  /**
   * The hard case. A refresh **deletes** books the server no longer lists, and that deletion is
   * exactly what a bookmark must not be caught by — a book that disappears for an evening because
   * a Plex library was rescanned would otherwise take the user's notes with it, permanently, since
   * no server holds a copy.
   */
  @Test
  fun `a bookmark survives its book being deleted by a refresh`() =
    runTest {
      bookDb.bookDao.insertAll(listOf(Audiobook(id = "1001", source = 1L, title = "The Hobbit")))
      bookmarks.add(bookId = "1001", position = BookOffset(90_000L), note = "the riddle game")
      // The server no longer lists it.
      serverHas(PlexDirectory(ratingKey = "1002", title = "Dune"))

      bookRepository().refreshData()

      assertNull(
        "the fixture must actually delete the book, or this test proves nothing",
        bookDb.bookDao.getAudiobookAsync("1001"),
      )
      assertEquals(
        "the note must outlive the catalogue row",
        "the riddle game",
        bookmarks.getBookmarksForBookAsync("1001").single().note,
      )
    }

  /**
   * And it comes back usable when the book returns, because the link is the `bookId` string rather
   * than a row reference.
   */
  @Test
  fun `a bookmark reattaches when its book comes back`() =
    runTest {
      bookmarks.add(bookId = "1001", position = BookOffset(90_000L), note = "the riddle game")
      serverHas(PlexDirectory(ratingKey = "1001", title = "The Hobbit"))

      bookRepository().refreshData()

      assertTrue(
        "the book must be back for this to mean anything",
        bookDb.bookDao.getAudiobookAsync("1001") != null,
      )
      assertEquals(1, bookmarks.getBookmarksForBookAsync("1001").size)
    }

  /**
   * A library *switch* is a different act from a re-sync — it clears the catalogue deliberately.
   * Bookmarks are left alone there too: the user may switch back, and a note is not something to
   * discard on their behalf. Recorded as a test so the behaviour is a decision rather than an
   * accident.
   */
  @Test
  fun `clearing the catalogue does not clear bookmarks`() =
    runTest {
      bookDb.bookDao.insertAll(listOf(Audiobook(id = "1001", source = 1L, title = "The Hobbit")))
      bookmarks.add(bookId = "1001", position = BookOffset(90_000L), note = "the riddle game")

      bookRepository().clear()

      assertEquals(0, bookDb.bookDao.getBookCount())
      assertEquals(1, bookmarkDb.bookmarkDao.count())
    }
}

package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `BookRepository.backfillChapterTable` — the I/O around [ChapterBackfill] (cu-158).
 *
 * The decisions are unit-tested in [ChapterBackfillTest]; what is pinned here is the plumbing:
 * which books get queried, that rows are actually inserted, and — the one that matters most — that
 * a single failing book does not abort the pass for the books after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBackfillRepositoryTest {
  private val bookDao = mockk<BookDao>(relaxed = true)
  private val chapterDao = mockk<ChapterDao>(relaxed = true)

  /**
   * Lets the cheap pre-gate through: more books carry a chapters column than have rows, which is
   * the "needs backfilling" state. The gate itself is covered by its own tests below and against a
   * real database in [ChapterBackfillSqlTest].
   */
  private fun gateOpen(
    withRows: Int = 0,
    withChapters: Int = 99,
  ) {
    coEvery { chapterDao.countBooksWithChapters() } returns withRows
    coEvery { bookDao.countBooksWithChapters() } returns withChapters
  }

  private fun chapter(index: Long) =
    Chapter(
      id = "ch-$index",
      title = "Chapter $index",
      index = index,
      bookStartTimeOffset = BookOffset(index * 1000),
      bookEndTimeOffset = BookOffset(index * 1000 + 1000),
      trackId = "track-1",
      // The pre-cu-49 serialized shape: no book id, so it decodes to the sentinel.
      bookId = NO_AUDIOBOOK_FOUND_ID,
    )

  private fun book(
    id: String,
    chapters: List<Chapter>,
  ) = EMPTY_AUDIOBOOK.copy(id = id, title = "Book $id", chapters = chapters)

  @Test
  fun `a book with chapters and no rows gets them written`() =
    runTest {
      gateOpen()
      every { bookDao.getAudiobooks(any()) } returns listOf(book("b1", listOf(chapter(1), chapter(2))))
      coEvery { chapterDao.getChaptersForBook("b1") } returns emptyList()
      val inserted = slot<List<Chapter>>()
      every { chapterDao.insertAll(capture(inserted)) } returns Unit

      val written = repository().backfillChapterTable()

      assertEquals(1, written)
      assertEquals(listOf("Chapter 1", "Chapter 2"), inserted.captured.map { it.title })
      assertTrue("book id must be stamped", inserted.captured.all { it.bookId == "b1" })
    }

  @Test
  fun `a book that already has rows is not rewritten`() =
    runTest {
      gateOpen()
      every { bookDao.getAudiobooks(any()) } returns listOf(book("b1", listOf(chapter(1))))
      coEvery { chapterDao.getChaptersForBook("b1") } returns listOf(chapter(1))

      val written = repository().backfillChapterTable()

      assertEquals(0, written)
    }

  @Test
  fun `a book with no chapter data is never queried`() =
    runTest {
      gateOpen()
      every { bookDao.getAudiobooks(any()) } returns listOf(book("b1", emptyList()))

      val written = repository().backfillChapterTable()

      assertEquals(0, written)
      // The cheap pre-filter: no query at all for a book that cannot need one.
      io.mockk.coVerify(exactly = 0) { chapterDao.getChaptersForBook(any()) }
    }

  /**
   * The important one. A book whose chapters will not read must not take the rest of the library
   * with it — it is left in exactly the state it is in today (a column, no rows), which the
   * `asChapterList()` fallback already handles.
   */
  @Test
  fun `one failing book does not stop the others`() =
    runTest {
      gateOpen()
      every { bookDao.getAudiobooks(any()) } returns
        listOf(
          book("bad", listOf(chapter(1))),
          book("good", listOf(chapter(1), chapter(2))),
        )
      coEvery { chapterDao.getChaptersForBook("bad") } throws IOException("db is unhappy")
      coEvery { chapterDao.getChaptersForBook("good") } returns emptyList()
      val inserted = slot<List<Chapter>>()
      every { chapterDao.insertAll(capture(inserted)) } returns Unit

      val written = repository().backfillChapterTable()

      assertEquals("the good book must still be written", 1, written)
      assertTrue(inserted.captured.all { it.bookId == "good" })
    }

  @Test
  fun `running twice writes nothing the second time`() =
    runTest {
      gateOpen()
      val subject = book("b1", listOf(chapter(1)))
      every { bookDao.getAudiobooks(any()) } returns listOf(subject)
      // First pass sees nothing, second sees what the first wrote.
      coEvery { chapterDao.getChaptersForBook("b1") } returnsMany
        listOf(emptyList(), listOf(chapter(1).copy(bookId = "b1")))

      val repo = repository()
      assertEquals(1, repo.backfillChapterTable())
      assertEquals(0, repo.backfillChapterTable())
    }

  /**
   * The gate that keeps this off the launch path once it has run. Reading the book table is a
   * `SELECT *` over every serialized chapters column, so discovering "nothing to do" must not cost
   * that (cu-110, cu-134).
   */
  @Test
  fun `nothing is read from the book table once every book has rows`() =
    runTest {
      gateOpen(withRows = 12, withChapters = 12)

      val written = repository().backfillChapterTable()

      assertEquals(0, written)
      io.mockk.verify(exactly = 0) { bookDao.getAudiobooks(any()) }
    }

  @Test
  fun `the book table is read while any book still lacks rows`() =
    runTest {
      gateOpen(withRows = 11, withChapters = 12)
      every { bookDao.getAudiobooks(any()) } returns listOf(book("b1", listOf(chapter(1))))
      coEvery { chapterDao.getChaptersForBook("b1") } returns emptyList()

      repository().backfillChapterTable()

      io.mockk.verify { bookDao.getAudiobooks(any()) }
    }

  private fun TestScope.repository(): BookRepository =
    BookRepository(
      bookDao = bookDao,
      chapterDao = chapterDao,
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexPrefsRepo = mockk<PlexPrefsRepo>(relaxed = true),
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      dispatchers = TestDispatcherProvider(testScheduler),
    )
}

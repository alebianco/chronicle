package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two counting queries the cu-158 backfill gates on, against **real SQLite**.
 *
 * Mocks cannot catch a wrong query, and both of these are easy to get wrong in ways that fail
 * silently rather than loudly:
 *
 * - `BookDao.countBooksWithChapters` has to treat an **empty string** as "no chapters", because
 *   `ChapterListConverter.toString` writes `joinToString(...)` over an empty list — which is `''`,
 *   not NULL. A query testing only `IS NOT NULL` counts every book in the library and the gate
 *   then never opens.
 * - `ChapterDao.countBooksWithChapters` counts **DISTINCT bookId**, not rows. Counting rows makes
 *   the number wildly larger than the book count, so the gate closes immediately and the backfill
 *   never runs at all.
 *
 * Both mistakes leave a green suite and a feature that does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterBackfillSqlTest {
  private lateinit var bookDb: BookDatabase
  private lateinit var chapterDb: ChapterDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    bookDb =
      Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    chapterDb =
      Room.inMemoryDatabaseBuilder(context, ChapterDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    bookDb.close()
    chapterDb.close()
  }

  private fun chapter(
    bookId: String,
    index: Long,
  ) = Chapter(
    id = "ch-$bookId-$index",
    title = "Chapter $index",
    index = index,
    bookStartTimeOffset = BookOffset(index * 1000),
    bookEndTimeOffset = BookOffset(index * 1000 + 1000),
    trackId = "track-$bookId",
    bookId = bookId,
  )

  @Test
  fun `a book with an empty chapter list is not counted`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          EMPTY_AUDIOBOOK.copy(id = "with", title = "Has chapters", chapters = listOf(chapter("with", 1))),
          EMPTY_AUDIOBOOK.copy(id = "without", title = "No chapters", chapters = emptyList()),
        ),
      )

      assertEquals(1, bookDb.bookDao.countBooksWithChapters())
    }

  @Test
  fun `the chapter count is per book, not per row`() =
    runTest {
      chapterDb.chapterDao.insertAll(
        listOf(
          chapter("b1", 1),
          chapter("b1", 2),
          chapter("b1", 3),
          chapter("b2", 1),
        ),
      )

      assertEquals("two books carry rows, not four", 2, chapterDb.chapterDao.countBooksWithChapters())
    }

  @Test
  fun `both counts are zero on an empty library`() =
    runTest {
      assertEquals(0, bookDb.bookDao.countBooksWithChapters())
      assertEquals(0, chapterDb.chapterDao.countBooksWithChapters())
    }

  /**
   * End to end through the real converter: a chapter decoded from the pre-cu-49 serialized shape
   * carries `bookId = NO_AUDIOBOOK_FOUND_ID`, and the backfill has to stamp the owning book's id
   * or every book's chapters collide on the composite primary key. Written through a real database
   * so the collision would actually happen if the stamp were dropped.
   */
  @Test
  fun `stamped rows from two books coexist in the table`() =
    runTest {
      val a = EMPTY_AUDIOBOOK.copy(id = "book-a", chapters = listOf(chapter(NO_AUDIOBOOK_FOUND_ID, 1)))
      val b = EMPTY_AUDIOBOOK.copy(id = "book-b", chapters = listOf(chapter(NO_AUDIOBOOK_FOUND_ID, 1)))

      chapterDb.chapterDao.insertAll(ChapterBackfill.rowsFor(a, existingRowCount = 0))
      chapterDb.chapterDao.insertAll(ChapterBackfill.rowsFor(b, existingRowCount = 0))

      assertEquals(1, chapterDb.chapterDao.getChaptersForBook("book-a").size)
      assertEquals(1, chapterDb.chapterDao.getChaptersForBook("book-b").size)
      assertEquals(2, chapterDb.chapterDao.countBooksWithChapters())
    }
}

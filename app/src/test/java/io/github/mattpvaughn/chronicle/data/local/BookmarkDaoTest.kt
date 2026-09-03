package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
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
 * The bookmark store against real SQLite (cu-22).
 *
 * Robolectric rather than a fake, because the behaviours that matter here are SQL's: the
 * REPLACE-on-id that makes a restore idempotent, the ordering the list depends on, and the
 * `String`-bound id that would silently match nothing if it were numeric (cu-71).
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkDaoTest {
  private lateinit var db: BookmarkDatabase
  private lateinit var repo: BookmarkRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db =
      Room.inMemoryDatabaseBuilder(context, BookmarkDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    repo = BookmarkRepository(db.bookmarkDao, TestDispatcherProvider())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `a bookmark can be added and read back`() =
    runTest {
      val added = repo.add(bookId = "1001", position = BookOffset(42_000L), note = "here")

      val stored = repo.getBookmarksForBookAsync("1001")

      assertEquals(listOf(added), stored)
      assertEquals(BookOffset(42_000L), stored.single().position)
    }

  /** The offset is stored as a plain INTEGER, so the value class must survive the trip. */
  @Test
  fun `the position keeps its frame across a write and read`() =
    runTest {
      repo.add(bookId = "1001", position = BookOffset(90_000L))

      assertEquals(BookOffset(90_000L), repo.getBookmarksForBookAsync("1001").single().position)
    }

  @Test
  fun `a note can be edited without changing identity`() =
    runTest {
      val added = repo.add(bookId = "1001", position = BookOffset(1_000L), note = "first")

      repo.updateNote(added.id, "second")

      val stored = repo.getBookmarksForBookAsync("1001").single()
      assertEquals("editing a note must not create a second bookmark", added.id, stored.id)
      assertEquals("second", stored.note)
      assertEquals("and must not move it", added.position, stored.position)
    }

  @Test
  fun `a bookmark can be deleted`() =
    runTest {
      val added = repo.add(bookId = "1001", position = BookOffset(1_000L))

      repo.delete(added.id)

      assertTrue(repo.getBookmarksForBookAsync("1001").isEmpty())
    }

  @Test
  fun `bookmarks are listed by position, then by creation time`() =
    runTest {
      repo.add(bookId = "1001", position = BookOffset(3_000L), note = "third", createdAt = 1L)
      repo.add(bookId = "1001", position = BookOffset(1_000L), note = "first", createdAt = 2L)
      // Same position as "first": the tie-break is what keeps the order stable between reads.
      repo.add(bookId = "1001", position = BookOffset(1_000L), note = "second", createdAt = 3L)

      val notes = repo.getBookmarksForBookAsync("1001").map { it.note }

      assertEquals(listOf("first", "second", "third"), notes)
    }

  @Test
  fun `bookmarks of other books are not returned`() =
    runTest {
      repo.add(bookId = "1001", position = BookOffset(1_000L), note = "hobbit")
      repo.add(bookId = "1002", position = BookOffset(1_000L), note = "dune")

      assertEquals(listOf("hobbit"), repo.getBookmarksForBookAsync("1001").map { it.note })
    }

  /**
   * REPLACE on the id is what makes a backup restore idempotent. Verified against real SQLite
   * because it is the conflict strategy, not application code, that provides it.
   */
  @Test
  fun `restoring the same rows twice replaces rather than duplicates`() =
    runTest {
      val rows =
        listOf(
          Bookmark(id = "bm-1", bookId = "1001", position = BookOffset(1_000L), note = "a"),
          Bookmark(id = "bm-2", bookId = "1001", position = BookOffset(2_000L), note = "b"),
        )

      repo.restore(rows)
      repo.restore(rows)

      assertEquals(2, db.bookmarkDao.count())
    }

  @Test
  fun `restoring a changed note overwrites the stored one`() =
    runTest {
      repo.restore(
        listOf(Bookmark(id = "bm-1", bookId = "1001", position = BookOffset(1_000L), note = "old")),
      )

      repo.restore(
        listOf(Bookmark(id = "bm-1", bookId = "1001", position = BookOffset(1_000L), note = "new")),
      )

      assertEquals("new", repo.getBookmarksForBookAsync("1001").single().note)
    }

  /**
   * A bookmark for a book that is not in the library is kept, not dropped. The library may be
   * re-synced later, and discarding a note the user wrote is the worst outcome available.
   */
  @Test
  fun `a bookmark for an unknown book is still stored`() =
    runTest {
      repo.restore(
        listOf(Bookmark(id = "bm-1", bookId = "not-in-library", position = BookOffset(1L))),
      )

      assertEquals(1, db.bookmarkDao.count())
    }

  @Test
  fun `restoring nothing writes nothing`() =
    runTest {
      assertEquals(0, repo.restore(emptyList()))
      assertEquals(0, db.bookmarkDao.count())
    }

  /**
   * The id column is TEXT, and SQLite compares across storage classes — so a numerically-bound
   * lookup matches no row silently, which is how two DAO methods came to be dead code in cu-71.
   * A numeric-looking id must work.
   */
  @Test
  fun `a numeric-looking id is found`() =
    runTest {
      repo.restore(listOf(Bookmark(id = "12345", bookId = "1001", position = BookOffset(1L))))

      assertEquals("12345", db.bookmarkDao.getBookmarkAsync("12345")?.id)
      assertNull(db.bookmarkDao.getBookmarkAsync("54321"))
    }

  @Test
  fun `every bookmark is exported in a stable order`() =
    runTest {
      repo.add(bookId = "1002", position = BookOffset(1_000L), createdAt = 1L)
      repo.add(bookId = "1001", position = BookOffset(2_000L), createdAt = 2L)
      repo.add(bookId = "1001", position = BookOffset(1_000L), createdAt = 3L)

      val order = repo.getAllAsync().map { it.bookId to it.position.millis }

      assertEquals(
        listOf("1001" to 1_000L, "1001" to 2_000L, "1002" to 1_000L),
        order,
      )
    }
}

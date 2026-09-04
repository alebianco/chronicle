package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.resolveChapters
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The upgrade regression cu-82 exists to avoid, over **real** databases.
 *
 * A library synced before cu-49 has chapters in `Audiobook.chapters` and **no rows** in
 * `ChapterDatabase`. A read switched straight to the DAO shows such a book no chapters at all —
 * and because the cu-158 backfill is *launched, not awaited*, that state is reachable on a real
 * launch, not just in theory.
 *
 * So this drives the actual sequence: read before the backfill, run it, read after.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterUpgradeReadTest {
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

  private fun chapter(index: Long) =
    Chapter(
      id = "ch-$index",
      title = "Chapter $index",
      index = index,
      bookStartTimeOffset = BookOffset(index * 1000),
      bookEndTimeOffset = BookOffset(index * 1000 + 1000),
      trackId = "track-1",
      bookId = "b1",
    )

  @Test
  fun `a book synced before cu-49 still shows its chapters, and the backfill then moves it`() =
    runTest {
      val legacyChapters = listOf(chapter(1), chapter(2), chapter(3))
      val book = EMPTY_AUDIOBOOK.copy(id = "b1", title = "Legacy book", chapters = legacyChapters)
      bookDb.bookDao.insertAll(listOf(book))

      val repo =
        BookRepository(
          bookDao = bookDb.bookDao,
          chapterDao = chapterDb.chapterDao,
          prefsRepo = mockk(relaxed = true),
          plexPrefsRepo = mockk<PlexPrefsRepo>(relaxed = true),
          plexMediaService = mockk<PlexMediaService>(relaxed = true),
          dispatchers = TestDispatcherProvider(testScheduler),
        )

      // Before the backfill: the table is empty, so the column has to carry the read.
      val rowsBefore = repo.getChaptersForBook("b1")
      assertTrue("the table must genuinely be empty for this to test anything", rowsBefore.isEmpty())
      assertEquals(
        "a pre-cu-49 book must still show its chapters",
        legacyChapters.map { it.title },
        resolveChapters(rowsBefore, book.chapters, emptyList()).map { it.title },
      )

      // The backfill runs, as it does on launch.
      repo.backfillChapterTable()

      // After: the table answers, and it agrees with what the column said.
      val rowsAfter = repo.getChaptersForBook("b1")
      assertEquals("the backfill must have written the rows", 3, rowsAfter.size)
      assertEquals(
        "the table must agree with the column it replaced",
        legacyChapters.map { it.title },
        resolveChapters(rowsAfter, book.chapters, emptyList()).map { it.title },
      )
    }
}

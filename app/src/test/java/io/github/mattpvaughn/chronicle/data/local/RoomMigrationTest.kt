package io.github.mattpvaughn.chronicle.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Exercises the historical migration chains against real SQLite.
 *
 * These databases hold the household's listening progress and none of them
 * declare `fallbackToDestructiveMigration`, so a broken migration is a crash on
 * upgrade, not a silent reset. That is the right failure mode — these tests
 * exist so we find out here rather than on someone's phone.
 *
 * Room's own `MigrationTestHelper` is instrumented-only and instrumented tests
 * are quarantined (task cu-54), so the chains are driven directly through
 * SQLite via Robolectric instead. That keeps migration coverage inside the
 * `verify.sh` unit gate.
 */
@RunWith(RobolectricTestRunner::class)
class RoomMigrationTest {
  private fun openHelperFor(
    name: String,
    createSql: String,
  ): SupportSQLiteDatabase {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.cacheDir, "$name-${System.nanoTime()}.db")
    file.delete()
    val db =
      SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL(createSql)
      }
    db.close()

    // Reopen through the same Support wrapper Room uses, so the migrations run
    // against the same API surface they will see in production.
    val factory = FrameworkSQLiteOpenHelperFactory()
    val config =
      androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
        .builder(context)
        .name(file.absolutePath)
        .callback(
          object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
              db: SupportSQLiteDatabase,
              oldVersion: Int,
              newVersion: Int,
            ) = Unit
          },
        ).build()
    return factory.create(config).writableDatabase
  }

  private fun columnsOf(
    db: SupportSQLiteDatabase,
    table: String,
  ): Set<String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
      val names = mutableSetOf<String>()
      val nameIndex = cursor.getColumnIndex("name")
      while (cursor.moveToNext()) {
        names.add(cursor.getString(nameIndex))
      }
      names
    }

  // ---------------------------------------------------------------------
  // TrackDatabase: v1 -> v4. Migrations add size, viewCount, discNumber.
  // ---------------------------------------------------------------------

  private val trackV1Create =
    """
    CREATE TABLE IF NOT EXISTS `MediaItemTrack` (
      `id` INTEGER NOT NULL, `parentKey` INTEGER NOT NULL, `title` TEXT NOT NULL,
      `playQueueItemID` INTEGER NOT NULL, `thumb` TEXT, `index` INTEGER NOT NULL,
      `duration` INTEGER NOT NULL, `media` TEXT NOT NULL, `album` TEXT NOT NULL,
      `artist` TEXT NOT NULL, `genre` TEXT NOT NULL, `cached` INTEGER NOT NULL,
      `artwork` TEXT, `progress` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL,
      `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
    )
    """.trimIndent()

  @Test
  fun `track database migrates 1 to 4 and preserves rows`() {
    val db = openHelperFor("track", trackV1Create)

    db.execSQL(
      """
      INSERT INTO MediaItemTrack
        (id, parentKey, title, playQueueItemID, thumb, `index`, duration, media,
         album, artist, genre, cached, artwork, progress, lastViewedAt, updatedAt)
      VALUES (7, 42, 'Chapter One', 1, NULL, 0, 1000, 'a.mp3', 'Book', 'Author',
              'Fantasy', 0, NULL, 500, 123, 456)
      """.trimIndent(),
    )

    MIGRATION_1_2.migrate(db)
    MIGRATION_2_3.migrate(db)
    MIGRATION_3_4.migrate(db)

    val columns = columnsOf(db, "MediaItemTrack")
    assertTrue("size added by 1->2", columns.contains("size"))
    assertTrue("viewCount added by 2->3", columns.contains("viewCount"))
    assertTrue("discNumber added by 3->4", columns.contains("discNumber"))

    db.query("SELECT title, progress, size, viewCount, discNumber FROM MediaItemTrack WHERE id = 7")
      .use { cursor ->
        assertTrue("pre-existing row survived the migration chain", cursor.moveToFirst())
        assertEquals("Chapter One", cursor.getString(0))
        assertEquals("listening progress preserved", 500L, cursor.getLong(1))
        assertEquals("size defaults to 0", 0L, cursor.getLong(2))
        assertEquals("viewCount defaults to 0", 0L, cursor.getLong(3))
        assertEquals("discNumber defaults to 1", 1L, cursor.getLong(4))
      }
    db.close()
  }

  // ---------------------------------------------------------------------
  // BookDatabase: v1 -> v8. Migrations add chapters, source, progress,
  // titleSort, viewCount, year (1->2 is intentionally a no-op).
  // ---------------------------------------------------------------------

  private val bookV1Create =
    """
    CREATE TABLE IF NOT EXISTS `Audiobook` (
      `id` INTEGER NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL,
      `thumb` TEXT NOT NULL, `parentId` INTEGER NOT NULL, `genre` TEXT NOT NULL,
      `summary` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
      `lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL,
      `isCached` INTEGER NOT NULL, `favorited` INTEGER NOT NULL,
      `viewedLeafCount` INTEGER NOT NULL, `leafCount` INTEGER NOT NULL,
      PRIMARY KEY(`id`)
    )
    """.trimIndent()

  @Test
  fun `book database migrates 1 to 8 and preserves rows`() {
    val db = openHelperFor("book", bookV1Create)

    db.execSQL(
      """
      INSERT INTO Audiobook
        (id, title, author, thumb, parentId, genre, summary, addedAt, updatedAt,
         lastViewedAt, duration, isCached, favorited, viewedLeafCount, leafCount)
      VALUES (3, 'Dune', 'Frank Herbert', 't.jpg', 1, 'SciFi', 'Spice.', 10, 20,
              30, 9999, 0, 0, 2, 5)
      """.trimIndent(),
    )

    BOOK_MIGRATION_1_2.migrate(db)
    BOOK_MIGRATION_2_3.migrate(db)
    BOOK_MIGRATION_3_4.migrate(db)
    BOOK_MIGRATION_4_5.migrate(db)
    BOOK_MIGRATION_5_6.migrate(db)
    BOOK_MIGRATION_6_7.migrate(db)
    BOOK_MIGRATION_7_8.migrate(db)

    val columns = columnsOf(db, "Audiobook")
    listOf("chapters", "source", "progress", "titleSort", "viewCount", "year").forEach {
      assertTrue("$it present after full migration chain", columns.contains(it))
    }

    db.query(
      "SELECT title, author, viewedLeafCount, progress, viewCount, year FROM Audiobook WHERE id = 3",
    ).use { cursor ->
      assertTrue("pre-existing row survived the migration chain", cursor.moveToFirst())
      assertEquals("Dune", cursor.getString(0))
      assertEquals("Frank Herbert", cursor.getString(1))
      assertEquals("progress-bearing column preserved", 2L, cursor.getLong(2))
      assertEquals("progress defaults to 0", 0L, cursor.getLong(3))
      assertEquals("viewCount defaults to 0", 0L, cursor.getLong(4))
      assertEquals("year defaults to 0", 0L, cursor.getLong(5))
    }
    db.close()
  }

  @Test
  fun `book database migration 7 to 8 adds year to a v7 database`() {
    // Guards the most recent migration in isolation: a user who last ran the
    // app at v7 takes only this step, not the full chain.
    val v7Create =
      bookV1Create
        .replace("`leafCount` INTEGER NOT NULL,", "`leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL,")
        .replace(
          "`favorited` INTEGER NOT NULL,",
          "`favorited` INTEGER NOT NULL, `chapters` TEXT NOT NULL, `source` INTEGER NOT NULL, " +
            "`progress` INTEGER NOT NULL, `titleSort` TEXT NOT NULL,",
        )
    val db = openHelperFor("book-v7", v7Create)

    BOOK_MIGRATION_7_8.migrate(db)

    assertTrue("year added by 7->8", columnsOf(db, "Audiobook").contains("year"))
    db.close()
  }

  /**
   * The chapter retype from v1 to v2 must not lose a row or a foreign id.
   *
   * Driven through raw SQLite rather than Room, because Room always opens a database at its
   * entity's current version — there is no way to ask it to stop at v2. That matters here: v2→v3
   * deliberately drops the table (see `CHAPTER_MIGRATION_2_3`), so running the whole chain through
   * Room would leave no rows and assert nothing about the retype. This exercises
   * `CHAPTER_MIGRATION_1_2` in isolation, where losing a row *is* a bug.
   */
  @Test
  fun `chapter database migration 1 to 2 retypes ids and preserves rows`() {
    val chapterV1Create =
      "CREATE TABLE IF NOT EXISTS `Chapter` (`title` TEXT NOT NULL, `id` INTEGER NOT NULL, " +
        "`index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, " +
        "`startTimeOffset` INTEGER NOT NULL, `endTimeOffset` INTEGER NOT NULL, " +
        "`downloaded` INTEGER NOT NULL, `trackId` INTEGER NOT NULL, " +
        "`bookId` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    val db = openHelperFor("chapter", chapterV1Create)
    db.execSQL(
      "INSERT INTO Chapter (title, id, `index`, discNumber, startTimeOffset, endTimeOffset, " +
        "downloaded, trackId, bookId) " +
        "VALUES ('Chapter One', 11, 1, 1, 0, 60000, 0, 2001, 1001)",
    )

    CHAPTER_MIGRATION_1_2.migrate(db)

    db.query("SELECT id, trackId, bookId, title, endTimeOffset FROM Chapter").use { cursor ->
      assertTrue("the chapter must survive the retype", cursor.moveToFirst())
      assertEquals("11", cursor.getString(0))
      assertEquals("a chapter that loses its trackId cannot be located in the book", "2001", cursor.getString(1))
      assertEquals("1001", cursor.getString(2))
      assertEquals("Chapter One", cursor.getString(3))
      assertEquals(60_000L, cursor.getLong(4))
      assertEquals("exactly one row expected", 1, cursor.count)
    }
    db.close()
  }
}

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
  // ---------------------------------------------------------------------
  // BookDatabase: v8 -> v9. Retypes id and parentId from INTEGER to TEXT.
  // ---------------------------------------------------------------------

  private val bookV8Create =
    """
    CREATE TABLE IF NOT EXISTS `Audiobook` (
      `id` INTEGER NOT NULL, `source` INTEGER NOT NULL, `title` TEXT NOT NULL,
      `titleSort` TEXT NOT NULL, `author` TEXT NOT NULL, `thumb` TEXT NOT NULL,
      `parentId` INTEGER NOT NULL, `genre` TEXT NOT NULL, `summary` TEXT NOT NULL,
      `year` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
      `lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL,
      `isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `favorited` INTEGER NOT NULL,
      `viewedLeafCount` INTEGER NOT NULL, `leafCount` INTEGER NOT NULL,
      `viewCount` INTEGER NOT NULL, `chapters` TEXT NOT NULL, PRIMARY KEY(`id`)
    )
    """.trimIndent()

  @Test
  fun `book database migrates 8 to 9 and preserves listening progress`() {
    val db = openHelperFor("book", bookV8Create)

    db.execSQL(
      """
      INSERT INTO Audiobook
        (id, source, title, titleSort, author, thumb, parentId, genre, summary, year,
         addedAt, updatedAt, lastViewedAt, duration, isCached, progress, favorited,
         viewedLeafCount, leafCount, viewCount, chapters)
      VALUES (1001, 1, 'Dune', 'Dune', 'Frank Herbert', '', 0, '', '', 1965,
              0, 0, 1700000000000, 3600000, 1, 1234567, 0, 0, 3, 0, '')
      """.trimIndent(),
    )

    BOOK_MIGRATION_8_9.migrate(db)

    db.query(
      "SELECT id, parentId, title, progress, lastViewedAt, isCached FROM Audiobook",
    ).use { cursor ->
      assertTrue("the row must survive the table rebuild", cursor.moveToFirst())
      assertEquals(1, cursor.count)
      assertEquals("1001", cursor.getString(0))
      assertEquals("parentId is retyped alongside id", "0", cursor.getString(1))
      assertEquals("Dune", cursor.getString(2))
      assertEquals(
        "losing progress here loses the user's place in the book",
        1_234_567L,
        cursor.getLong(3),
      )
      assertEquals(1_700_000_000_000L, cursor.getLong(4))
      assertEquals("a downloaded book must not become undownloaded", 1, cursor.getInt(5))
    }
  }

  @Test
  fun `book migration 8 to 9 leaves the id column with TEXT affinity`() {
    val db = openHelperFor("book", bookV8Create)

    BOOK_MIGRATION_8_9.migrate(db)

    db.query("PRAGMA table_info(`Audiobook`)").use { cursor ->
      val nameIndex = cursor.getColumnIndex("name")
      val typeIndex = cursor.getColumnIndex("type")
      val types = mutableMapOf<String, String>()
      while (cursor.moveToNext()) {
        types[cursor.getString(nameIndex)] = cursor.getString(typeIndex)
      }
      assertEquals("Room validates this on open; a mismatch crashes there", "TEXT", types["id"])
      assertEquals("TEXT", types["parentId"])
      assertEquals("progress must stay numeric", "INTEGER", types["progress"])
    }
  }

  @Test
  fun `book migration 8 to 9 keeps every column`() {
    val db = openHelperFor("book", bookV8Create)

    BOOK_MIGRATION_8_9.migrate(db)

    val columns = columnsOf(db, "Audiobook")
    assertEquals("a column dropped by the rebuild is lost silently", 21, columns.size)
    listOf("chapters", "titleSort", "viewCount", "favorited").forEach {
      assertTrue("$it must survive", columns.contains(it))
    }
  }
}

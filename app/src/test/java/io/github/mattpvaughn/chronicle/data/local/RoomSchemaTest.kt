package io.github.mattpvaughn.chronicle.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opens every database through Room itself.
 *
 * This exists because of a mistake it would have caught. A commit shipped `BookDatabase` at version
 * 9 with a migration writing TEXT ids while the entity still declared `id: Int`; Room validates the
 * entity against the exported schema when the database is opened, so the app would have crashed on
 * first launch. `verify.sh` passed anyway, because `RoomMigrationTest` drives raw SQLite and nothing
 * opened the databases through Room.
 *
 * Two different checks are needed, and the first one alone is not enough:
 *
 * - **In-memory open** proves every entity is internally consistent and Room can generate its
 *   implementation. It does *not* catch a version/migration mismatch, because an in-memory database
 *   is created fresh at the current version and never migrated — verified by deliberately bumping a
 *   version with no migration, which this alone did not notice.
 * - **File-backed open after a migration** is what actually reproduces the crash: create the file at
 *   the old schema, then let Room open it at the new version, run the registered migrations and
 *   validate the result against the entity. A retyped column that the entity still declares as Int
 *   fails here.
 *
 *  is fine here and only here: the point is to force Room to validate, not
 * to model production threading.
 */
@RunWith(RobolectricTestRunner::class)
class RoomSchemaTest {
  @Test
  fun `the book database opens and validates`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        BookDatabase::class.java,
      ).allowMainThreadQueries().build()

    // Room defers validation until the database is actually used.
    assertNotNull(db.bookDao.getAudiobooks())
    db.close()
  }

  @Test
  fun `the track database opens and validates`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TrackDatabase::class.java,
      ).allowMainThreadQueries().build()

    db.query("SELECT COUNT(*) FROM MediaItemTrack", emptyArray()).close()
    assertNotNull(db.trackDao)
    db.close()
  }

  @Test
  fun `the chapter database opens and validates`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        ChapterDatabase::class.java,
      ).allowMainThreadQueries().build()

    assertNotNull(db.chapterDao)
    db.query("SELECT COUNT(*) FROM Chapter", emptyArray()).close()
    db.close()
  }

  @Test
  fun `the collections database opens and validates`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        CollectionsDatabase::class.java,
      ).allowMainThreadQueries().build()

    assertNotNull(db.collectionsDao)
    db.query("SELECT COUNT(*) FROM Collection", emptyArray()).close()
    db.close()
  }

  /**
   * The check that actually reproduces the crash an earlier commit would have shipped.
   *
   * Creates a real database file at the *previous* schema, then lets Room open it at the current
   * version: Room runs the registered migrations and then validates the resulting tables against
   * the entities. A migration that retypes a column the entity still declares differently fails
   * right here, which is what the in-memory tests above cannot see.
   */
  @Test
  fun `the book database survives being opened after migrating from v8`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = java.io.File(context.cacheDir, "schema-check-${System.nanoTime()}.db")
    file.delete()

    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).apply {
      execSQL(
        "CREATE TABLE IF NOT EXISTS `Audiobook` (" +
          "`id` INTEGER NOT NULL, `source` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
          "`titleSort` TEXT NOT NULL, `author` TEXT NOT NULL, `thumb` TEXT NOT NULL, " +
          "`parentId` INTEGER NOT NULL, `genre` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
          "`year` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
          "`lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
          "`isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, " +
          "`favorited` INTEGER NOT NULL, `viewedLeafCount` INTEGER NOT NULL, " +
          "`leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, " +
          "`chapters` TEXT NOT NULL, PRIMARY KEY(`id`))",
      )
      execSQL(
        "INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, genre, " +
          "summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, progress, " +
          "favorited, viewedLeafCount, leafCount, viewCount, chapters) " +
          "VALUES (1001, 1, 'Dune', 'Dune', 'Frank Herbert', '', 0, '', '', 1965, 0, 0, " +
          "1700000000000, 3600000, 1, 1234567, 0, 0, 3, 0, '')",
      )
      version = 8
      close()
    }

    val db =
      Room.databaseBuilder(context, BookDatabase::class.java, file.absolutePath)
        .addMigrations(*BOOK_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    // Forces the open, the migrations and Room's schema validation.
    db.query("SELECT progress FROM Audiobook", emptyArray()).use { cursor ->
      assertTrue("the pre-existing row must survive the upgrade", cursor.moveToFirst())
      assertEquals(
        "an upgrade that loses progress loses the user's place",
        1_234_567L,
        cursor.getLong(0),
      )
    }
    db.close()
  }
}

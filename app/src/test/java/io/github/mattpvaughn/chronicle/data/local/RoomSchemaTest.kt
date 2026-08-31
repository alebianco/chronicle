package io.github.mattpvaughn.chronicle.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
 * `allowMainThreadQueries()` is fine here and only here: the point is to force Room to validate,
 * not to model production threading.
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

  /**
   * Creates a database file at an old schema, opens it through Room at the current version, and
   * hands back the opened database so a test can assert the rows survived.
   *
   * Room only runs migrations and validates entities when it opens a *file* whose recorded version
   * is behind — an in-memory database is created fresh at the current version, so it can never
   * exercise a migration. Everything below therefore goes through here.
   */
  private fun <T : RoomDatabase> migrated(
    klass: Class<T>,
    oldVersion: Int,
    createSql: String,
    seedSql: String,
    migrations: Array<Migration>,
  ): T {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = java.io.File(context.cacheDir, "migrate-check-${System.nanoTime()}.db")
    file.delete()

    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).apply {
      execSQL(createSql)
      execSQL(seedSql)
      version = oldVersion
      close()
    }

    return Room.databaseBuilder(context, klass, file.absolutePath)
      .addMigrations(*migrations)
      .allowMainThreadQueries()
      .build()
  }

  /**
   * The track id must survive as TEXT *and* keep pointing at its book.
   *
   * A migration that rebuilt the table but forgot to copy `parentKey` was verified to pass the
   * entire rest of the suite: every track silently lost its book, which presents as a library
   * full of books with no tracks. Only opening a migrated file catches it.
   */
  @Test
  fun `the track database survives being opened after migrating from v4`() {
    val db =
      migrated(
        klass = TrackDatabase::class.java,
        oldVersion = 4,
        createSql =
          "CREATE TABLE IF NOT EXISTS `MediaItemTrack` (`id` INTEGER NOT NULL, " +
            "`parentKey` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
            "`playQueueItemID` INTEGER NOT NULL, `thumb` TEXT, `index` INTEGER NOT NULL, " +
            "`discNumber` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `media` TEXT NOT NULL, " +
            "`album` TEXT NOT NULL, `artist` TEXT NOT NULL, `genre` TEXT NOT NULL, " +
            "`cached` INTEGER NOT NULL, `artwork` TEXT, `viewCount` INTEGER NOT NULL, " +
            "`progress` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `size` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO MediaItemTrack (id, parentKey, title, playQueueItemID, thumb, `index`, " +
            "discNumber, duration, media, album, artist, genre, cached, artwork, viewCount, " +
            "progress, lastViewedAt, updatedAt, size) VALUES (2001, 1001, 'Track One', 0, '', " +
            "1, 1, 5000, '', '', '', '', 0, '', 0, 4242, 0, 0, 0)",
        migrations = TRACK_MIGRATIONS,
      )

    db.query("SELECT id, parentKey, progress FROM MediaItemTrack", emptyArray()).use { cursor ->
      assertTrue("the pre-existing track must survive the upgrade", cursor.moveToFirst())
      assertEquals("2001", cursor.getString(0))
      assertEquals("a track that loses its parentKey is orphaned from its book", "1001", cursor.getString(1))
      assertEquals("an upgrade that loses progress loses the user's place", 4_242L, cursor.getLong(2))
    }
    db.close()
  }

  /** Chapters carry two foreign ids, and both are retyped. */
  @Test
  fun `the chapter database survives being opened after migrating from v1`() {
    val db =
      migrated(
        klass = ChapterDatabase::class.java,
        oldVersion = 1,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Chapter` (`title` TEXT NOT NULL, `id` INTEGER NOT NULL, " +
            "`index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, " +
            "`startTimeOffset` INTEGER NOT NULL, `endTimeOffset` INTEGER NOT NULL, " +
            "`downloaded` INTEGER NOT NULL, `trackId` INTEGER NOT NULL, " +
            "`bookId` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Chapter (title, id, `index`, discNumber, startTimeOffset, endTimeOffset, " +
            "downloaded, trackId, bookId) " +
            "VALUES ('Chapter One', 11, 1, 1, 0, 60000, 0, 2001, 1001)",
        migrations = CHAPTER_MIGRATIONS,
      )

    db.query("SELECT id, trackId, bookId, title FROM Chapter", emptyArray()).use { cursor ->
      assertTrue("the pre-existing chapter must survive the upgrade", cursor.moveToFirst())
      assertEquals("11", cursor.getString(0))
      assertEquals("a chapter that loses its trackId cannot be located in the book", "2001", cursor.getString(1))
      assertEquals("1001", cursor.getString(2))
      assertEquals("Chapter One", cursor.getString(3))
    }
    db.close()
  }

  /**
   * `childIds` is deliberately untouched by the migration: its converter already stored a JSON
   * array of strings, so only the Kotlin type changed. This asserts the stored form still reads
   * back, which is what would break if the migration had "helpfully" rewritten the column.
   */
  @Test
  fun `the collections database survives being opened after migrating from v1`() {
    val db =
      migrated(
        klass = CollectionsDatabase::class.java,
        oldVersion = 1,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Collection` (`id` INTEGER NOT NULL, " +
            "`source` INTEGER NOT NULL, `title` TEXT NOT NULL, `childCount` INTEGER NOT NULL, " +
            "`sortType` TEXT NOT NULL, `isCached` INTEGER NOT NULL, `thumb` TEXT NOT NULL, " +
            "`childIds` TEXT NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Collection (id, source, title, childCount, sortType, isCached, thumb, " +
            "childIds) VALUES (5001, 1, 'Favourites', 2, '', 0, '', '[\"1001\",\"1002\"]')",
        migrations = COLLECTIONS_MIGRATIONS,
      )

    db.query("SELECT id, title, childIds FROM Collection", emptyArray()).use { cursor ->
      assertTrue("the pre-existing collection must survive the upgrade", cursor.moveToFirst())
      assertEquals("5001", cursor.getString(0))
      assertEquals("Favourites", cursor.getString(1))
      assertEquals(
        "childIds already held strings; the migration must leave the column alone",
        "[\"1001\",\"1002\"]",
        cursor.getString(2),
      )
    }
    db.close()
  }
}

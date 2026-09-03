package io.github.mattpvaughn.chronicle.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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

  /** The fifth database (cu-22). Same shape as the four above; there is no reason to omit it. */
  @Test
  fun `the bookmark database opens and validates`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        BookmarkDatabase::class.java,
      ).allowMainThreadQueries().build()

    assertNotNull(db.bookmarkDao)
    db.query("SELECT COUNT(*) FROM Bookmark", emptyArray()).close()
    db.close()
  }

  /**
   * The `BookOffset` type converter must round-trip through a real column.
   *
   * `OffsetConverters` stores a plain INTEGER, so this cannot fail as arithmetic — but a
   * `@TypeConverters` annotation missing from the database class makes Room refuse to build, and
   * this is the check that would say so rather than a crash on first use.
   */
  @Test
  fun `the bookmark database stores a book offset`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        BookmarkDatabase::class.java,
      ).allowMainThreadQueries().build()

    // execSQL, not query: `query` prepares a statement and returns a cursor without stepping it,
    // so an INSERT written that way never runs — and the read below then finds no row, which looks
    // like a converter fault rather than a test bug.
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO Bookmark (id, bookId, position, note, createdAt) VALUES ('a', '1', 90000, '', 0)",
    )
    db.query("SELECT position FROM Bookmark WHERE id = 'a'", emptyArray()).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(90_000L, cursor.getLong(0))
    }
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
   * The v9 -> v10 migration adds the per-book speed override (cu-20).
   *
   * Two things must hold: the pre-existing row survives, and its new column reads
   * [Audiobook.NO_SPEED_OVERRIDE] — an upgrade that defaulted it to a *speed* would silently make
   * every book in the library override the global preference at that value.
   */
  @Test
  fun `the book database survives being opened after migrating from v9`() {
    val db =
      migrated(
        klass = BookDatabase::class.java,
        oldVersion = 9,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Audiobook` (`id` TEXT NOT NULL, " +
            "`source` INTEGER NOT NULL, `title` TEXT NOT NULL, `titleSort` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `thumb` TEXT NOT NULL, `parentId` TEXT NOT NULL, " +
            "`genre` TEXT NOT NULL, `summary` TEXT NOT NULL, `year` INTEGER NOT NULL, " +
            "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
            "`isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, " +
            "`favorited` INTEGER NOT NULL, `viewedLeafCount` INTEGER NOT NULL, " +
            "`leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, " +
            "`chapters` TEXT NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, " +
            "genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, " +
            "progress, favorited, viewedLeafCount, leafCount, viewCount, chapters) " +
            "VALUES ('1001', 1, 'Dune', 'Dune', 'Frank Herbert', '', '0', '', '', 1965, 0, 0, " +
            "1700000000000, 3600000, 1, 1234567, 0, 0, 3, 0, '')",
        migrations = BOOK_MIGRATIONS,
      )

    db.query("SELECT progress, playbackSpeed FROM Audiobook", emptyArray()).use { cursor ->
      assertTrue("the pre-existing row must survive the upgrade", cursor.moveToFirst())
      assertEquals(
        "an upgrade that loses progress loses the user's place",
        1_234_567L,
        cursor.getLong(0),
      )
      assertEquals(
        "an upgraded book must follow the global speed, not override it",
        Audiobook.NO_SPEED_OVERRIDE,
        cursor.getFloat(1),
        0f,
      )
    }
    db.close()
  }

  /**
   * The v10 -> v11 migration adds narrator and series (cu-24).
   *
   * Existing rows must read **empty**, meaning "not known yet" — an upgrade that invented a value
   * would put a phantom narrator in the facet list for every book in the library.
   */
  @Test
  fun `the book database survives being opened after migrating from v10`() {
    val db =
      migrated(
        klass = BookDatabase::class.java,
        oldVersion = 10,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Audiobook` (`id` TEXT NOT NULL, " +
            "`source` INTEGER NOT NULL, `title` TEXT NOT NULL, `titleSort` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `thumb` TEXT NOT NULL, `parentId` TEXT NOT NULL, " +
            "`genre` TEXT NOT NULL, `summary` TEXT NOT NULL, `year` INTEGER NOT NULL, " +
            "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
            "`isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, " +
            "`favorited` INTEGER NOT NULL, `viewedLeafCount` INTEGER NOT NULL, " +
            "`leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, " +
            "`chapters` TEXT NOT NULL, `playbackSpeed` REAL NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, " +
            "genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, " +
            "progress, favorited, viewedLeafCount, leafCount, viewCount, chapters, " +
            "playbackSpeed) " +
            "VALUES ('1001', 1, 'Dune', 'Dune', 'Frank Herbert', '', '0', '', '', 1965, 0, 0, " +
            "1700000000000, 3600000, 1, 1234567, 0, 0, 3, 0, '', 1.5)",
        migrations = BOOK_MIGRATIONS,
      )

    db.query(
      "SELECT progress, playbackSpeed, narrator, series, seriesIndex FROM Audiobook",
      emptyArray(),
    ).use { cursor ->
      assertTrue("the pre-existing row must survive the upgrade", cursor.moveToFirst())
      assertEquals(
        "an upgrade that loses progress loses the user's place",
        1_234_567L,
        cursor.getLong(0),
      )
      assertEquals("a per-book speed must survive too", 1.5f, cursor.getFloat(1), 0f)
      assertEquals("an upgraded book has no narrator yet, not a phantom one", "", cursor.getString(2))
      assertEquals("", cursor.getString(3))
      assertEquals(0, cursor.getInt(4))
    }
    db.close()
  }

  /**
   * The v11 -> v12 migration rescales `seriesIndex` to hundredths (cu-146).
   *
   * No column changes — the exported v11 and v12 schemas have identical columns and even the same
   * `identityHash`, since Room hashes the schema rather than the version. So this migration is
   * *only* a data rewrite, and nothing but this test can catch it being wrong: a v11 row holding
   * `2` for book two would read as 0.02 afterwards and sort before every correctly-parsed book.
   *
   * The unknown sentinel must be left alone, because zero means "no position" in both units and
   * multiplying it would be a silent no-op that later reads as a real position if the unit changed
   * again.
   */
  @Test
  fun `the book database rescales a series index when migrating from v11`() {
    val db =
      migrated(
        klass = BookDatabase::class.java,
        oldVersion = 11,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Audiobook` (`id` TEXT NOT NULL, " +
            "`source` INTEGER NOT NULL, `title` TEXT NOT NULL, `titleSort` TEXT NOT NULL, " +
            "`author` TEXT NOT NULL, `thumb` TEXT NOT NULL, `parentId` TEXT NOT NULL, " +
            "`genre` TEXT NOT NULL, `summary` TEXT NOT NULL, `year` INTEGER NOT NULL, " +
            "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL, " +
            "`isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, " +
            "`favorited` INTEGER NOT NULL, `viewedLeafCount` INTEGER NOT NULL, " +
            "`leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, " +
            "`chapters` TEXT NOT NULL, `playbackSpeed` REAL NOT NULL, " +
            "`narrator` TEXT NOT NULL, `series` TEXT NOT NULL, " +
            "`seriesIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Audiobook (id, source, title, titleSort, author, thumb, parentId, " +
            "genre, summary, year, addedAt, updatedAt, lastViewedAt, duration, isCached, " +
            "progress, favorited, viewedLeafCount, leafCount, viewCount, chapters, " +
            "playbackSpeed, narrator, series, seriesIndex) " +
            "VALUES ('1001', 1, 'Well of Ascension', 'Mistborn, Book 2', 'Sanderson', '', '0', " +
            "'', '', 2007, 0, 0, 1700000000000, 3600000, 1, 1234567, 0, 0, 3, 0, '', 0, " +
            "'Michael Kramer', 'Mistborn', 2), " +
            "('1002', 1, 'Standalone', 'Standalone', 'Someone', '', '0', " +
            "'', '', 2001, 0, 0, 0, 3600000, 0, 0, 0, 0, 1, 0, '', 0, '', '', 0)",
        migrations = BOOK_MIGRATIONS,
      )

    db.query("SELECT id, seriesIndex, narrator, progress FROM Audiobook ORDER BY id", emptyArray())
      .use { cursor ->
        assertTrue("the pre-existing row must survive the upgrade", cursor.moveToFirst())
        assertEquals("1001", cursor.getString(0))
        assertEquals(
          "book two must land on 200, not stay at 2 — a stale value sorts before book one",
          2 * Audiobook.SERIES_INDEX_SCALE,
          cursor.getInt(1),
        )
        assertEquals("the rescale must not disturb other columns", "Michael Kramer", cursor.getString(2))
        assertEquals(1_234_567L, cursor.getLong(3))

        assertTrue(cursor.moveToNext())
        assertEquals("1002", cursor.getString(0))
        assertEquals(
          "an unknown position must stay unknown, not become a real one",
          Audiobook.NO_SERIES_INDEX,
          cursor.getInt(1),
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

  /** A chapter with every key column set, so tests only name what they are varying. */
  private fun chapter(
    id: String,
    bookId: String,
    trackId: String,
    index: Long = 1L,
    discNumber: Int = 1,
    title: String = "Chapter $index",
    bookEndTimeOffset: Long = 60_000L,
  ) = Chapter(
    title = title,
    id = id,
    index = index,
    discNumber = discNumber,
    bookStartTimeOffset = BookOffset(0L),
    bookEndTimeOffset = BookOffset(bookEndTimeOffset),
    trackId = trackId,
    bookId = bookId,
  )

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

    // The cu-110 index must exist on an *upgraded* database, not just a freshly created one.
    // Room validates that the schema matches the entity on open, so a missing index would throw
    // above — but only if the entity declares it, and a silently-dropped `@Index` would then make
    // both sides agree on the wrong thing. Asserting against `sqlite_master` is independent of
    // that agreement, and the query plan below is what actually pays for it.
    db.query(
      "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'MediaItemTrack'",
      emptyArray(),
    ).use { cursor ->
      val indexNames = mutableListOf<String>()
      while (cursor.moveToNext()) indexNames.add(cursor.getString(0))
      assertTrue(
        "the parentKey index must survive a migration, not only a fresh create; found $indexNames",
        indexNames.contains("index_MediaItemTrack_parentKey_discNumber_index"),
      )
    }

    // The point of the index: the per-book track query must not scan the table or sort. This is
    // the assertion that would fail if someone removed the index or reordered its columns —
    // SQLite reports "SCAN" and "USE TEMP B-TREE FOR ORDER BY" when it cannot use one.
    db.query(
      "EXPLAIN QUERY PLAN SELECT * FROM MediaItemTrack WHERE parentKey = '1001' " +
        "AND cached >= 0 ORDER BY `discNumber` ASC, `index` ASC",
      emptyArray(),
    ).use { cursor ->
      val plan = buildString { while (cursor.moveToNext()) append(cursor.getString(3)).append(' ') }
      assertTrue("the per-book query must use the index, not scan: $plan", plan.contains("USING INDEX"))
      assertTrue("the index must satisfy the ORDER BY too: $plan", !plan.contains("TEMP B-TREE"))
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

  /**
   * The collision the old single-column key allowed.
   *
   * `Chapter.id` came from two namespaces — `PlexChapter.id`, and the *track* id on the per-track
   * fallback — and Plex hands out chapter and track ratingKeys from one server-wide sequence, so
   * two books' chapters could share an id. With `id` as the primary key and `insertAll` using
   * `OnConflictStrategy.REPLACE`, inserting the second silently evicted the first: one book lost a
   * chapter with no error anywhere. The composite key `(bookId, trackId, discNumber, index)` is
   * what makes both rows survive.
   */
  @Test
  fun `two books whose chapters share a server id both survive insertAll`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        ChapterDatabase::class.java,
      ).allowMainThreadQueries().build()

    val shared = "4001"
    val fromBookA = chapter(id = shared, bookId = "1001", trackId = "2001")
    val fromBookB = chapter(id = shared, bookId = "1002", trackId = "3001", bookEndTimeOffset = 90_000L)

    db.chapterDao.insertAll(listOf(fromBookA, fromBookB))

    assertEquals(
      "a shared server id must not make one book evict the other's chapter",
      2,
      db.chapterDao.getChapters().size,
    )
    db.close()
  }

  /**
   * Same rule, one book: two chapters of the same book must not collide either. This is the case
   * the *fallback* path produces, where every chapter's `id` is its track id.
   */
  @Test
  fun `chapters of one book on different tracks both survive insertAll`() {
    val db =
      Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        ChapterDatabase::class.java,
      ).allowMainThreadQueries().build()

    val one = chapter(id = "2001", bookId = "1001", trackId = "2001", title = "Track 1")
    val two = chapter(id = "2002", bookId = "1001", trackId = "2002", index = 2L, title = "Track 2")

    db.chapterDao.insertAll(listOf(one, two))

    assertEquals(2, db.chapterDao.getChapters().size)
    db.close()
  }

  /**
   * Migrating a v2 chapter database must produce a table the entity validates against.
   *
   * The v2→v3 migration drops the table rather than copying it, which is safe *only* here: nothing
   * ever wrote to it (no Dagger module provided `ChapterDatabase` before cu-49), and pre-cu-49 rows
   * all carry `bookId = NO_AUDIOBOOK_FOUND_ID`, so copying them would collide on the new key.
   * What this test proves is that Room opens the result and accepts the schema — the failure mode
   * that shipped a crashing migration once already.
   */
  @Test
  fun `the chapter database survives being opened after migrating from v2`() {
    val db =
      migrated(
        klass = ChapterDatabase::class.java,
        oldVersion = 2,
        createSql =
          "CREATE TABLE IF NOT EXISTS `Chapter` (`title` TEXT NOT NULL, `id` TEXT NOT NULL, " +
            "`index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, " +
            "`startTimeOffset` INTEGER NOT NULL, `endTimeOffset` INTEGER NOT NULL, " +
            "`downloaded` INTEGER NOT NULL, `trackId` TEXT NOT NULL, " +
            "`bookId` TEXT NOT NULL, PRIMARY KEY(`id`))",
        seedSql =
          "INSERT INTO Chapter (title, id, `index`, discNumber, startTimeOffset, endTimeOffset, " +
            "downloaded, trackId, bookId) " +
            "VALUES ('Stale Chapter', '11', 1, 1, 0, 60000, 0, '2001', '-22321')",
        migrations = CHAPTER_MIGRATIONS,
      )

    // The table must exist, validate, and accept a row under the new composite key.
    db.chapterDao.insertAll(listOf(chapter(id = "4001", bookId = "1001", trackId = "2001", title = "Fresh")))
    assertEquals(listOf("Fresh"), db.chapterDao.getChapters().map { it.title })
    db.close()
  }

  /**
   * A refetch must *replace* a book's chapters, not merge into them.
   *
   * A chapter list can shrink — a re-tagged file, or a book that drops from server chapters to the
   * per-track fallback. `insertAll` with `OnConflictStrategy.REPLACE` only overwrites rows whose
   * key matches, so without an explicit delete the stale extras survive and the book shows
   * chapters that no longer exist. This is what `removeAllForBook` is for.
   */
  @Test
  fun `refetching fewer chapters for a book leaves no stale rows`() =
    kotlinx.coroutines.test.runTest {
      val db =
        Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          ChapterDatabase::class.java,
        ).allowMainThreadQueries().build()

      db.chapterDao.insertAll(
        listOf(
          chapter(id = "41", bookId = "1001", trackId = "2001", index = 1L),
          chapter(id = "42", bookId = "1001", trackId = "2001", index = 2L),
          chapter(id = "43", bookId = "1001", trackId = "2001", index = 3L),
        ),
      )
      // Another book's chapters must be untouched by the scoped delete.
      db.chapterDao.insertAll(listOf(chapter(id = "51", bookId = "1002", trackId = "3001")))

      db.chapterDao.removeAllForBook("1001")
      db.chapterDao.insertAll(listOf(chapter(id = "41", bookId = "1001", trackId = "2001", index = 1L)))

      assertEquals(
        "the shrunk book must keep only its remaining chapter",
        1,
        db.chapterDao.getChaptersForBook("1001").size,
      )
      assertEquals(
        "another book's chapters must survive a scoped delete",
        1,
        db.chapterDao.getChaptersForBook("1002").size,
      )
      db.close()
    }

  /** The per-book read must not leak other books' chapters. */
  @Test
  fun `getChaptersForBook returns only that book's chapters, in order`() =
    kotlinx.coroutines.test.runTest {
      val db =
        Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          ChapterDatabase::class.java,
        ).allowMainThreadQueries().build()

      db.chapterDao.insertAll(
        listOf(
          chapter(id = "42", bookId = "1001", trackId = "2002", index = 2L, title = "Second"),
          chapter(id = "41", bookId = "1001", trackId = "2001", index = 1L, title = "First"),
          chapter(id = "51", bookId = "1002", trackId = "3001", index = 1L, title = "Other book"),
        ),
      )

      assertEquals(
        listOf("First", "Second"),
        db.chapterDao.getChaptersForBook("1001").map { it.title },
      )
      db.close()
    }

  /**
   * An exported schema's file must declare the version its **name** says (cu-24).
   *
   * Room rewrites `<version>.json` from the current entities, and if the version bump and the
   * entity change land in the same build it overwrites the **old** file — leaving `10.json`
   * containing v11's shape. That happened while writing this task. Those files are the authority a
   * migration's column list is written from (see `BOOK_MIGRATION_8_9`), so a corrupted one
   * silently misinforms the next migration written against it.
   *
   * The check is the filename against the `"version"` inside, which is what actually disagrees —
   * comparing column counts between neighbours does not catch it, because an overwritten file is
   * an exact copy of the newer one and compares equal.
   */
  @Test
  fun `every exported schema declares the version its filename claims`() {
    val roots = File("schemas").listFiles().orEmpty().filter { it.isDirectory }
    assertTrue("no schema directories found under ${File("schemas").absolutePath}", roots.isNotEmpty())

    var checked = 0
    roots.forEach { dir ->
      dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { file ->
        val fromName = file.name.removeSuffix(".json").toInt()
        val declared =
          Regex(""""version"\s*:\s*(\d+)""")
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertEquals(
          "${dir.name}/${file.name} declares version $declared — an older exported schema has " +
            "been overwritten with a newer entity's shape",
          fromName,
          declared,
        )
        checked++
      }
    }
    assertTrue("no schema files were checked", checked > 0)
  }
}

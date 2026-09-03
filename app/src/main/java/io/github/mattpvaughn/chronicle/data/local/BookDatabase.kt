package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookTrackData

private const val BOOK_DATABASE_NAME = "book_db"

private lateinit var INSTANCE: BookDatabase

fun getBookDatabase(context: Context): BookDatabase {
  synchronized(BookDatabase::class.java) {
    if (!::INSTANCE.isInitialized) {
      INSTANCE =
        Room.databaseBuilder(
          context.applicationContext,
          BookDatabase::class.java,
          BOOK_DATABASE_NAME,
        ).addMigrations(*BOOK_MIGRATIONS).build()
    }
  }
  return INSTANCE
}

val BOOK_MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      // Do nothing lol
    }
  }

val BOOK_MIGRATION_2_3 =
  object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN chapters TEXT NOT NULL DEFAULT ''")
    }
  }

val BOOK_MIGRATION_3_4 =
  object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN source BIGINT NOT NULL DEFAULT -1")
    }
  }

val BOOK_MIGRATION_4_5 =
  object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN progress BIGINT NOT NULL DEFAULT 0")
    }
  }

val BOOK_MIGRATION_5_6 =
  object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN titleSort TEXT NOT NULL DEFAULT ''")
    }
  }

val BOOK_MIGRATION_6_7 =
  object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "ALTER TABLE Audiobook ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0",
      )
    }
  }

val BOOK_MIGRATION_7_8 =
  object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
    }
  }

/**
 * Retypes `id` and `parentId` to TEXT so a non-numeric backend can be represented
 * (cu-71, decision-11).
 *
 * A table rebuild because SQLite cannot alter a column type or a primary key. The column list comes
 * from the exported v8 schema, which is the authority — a column omitted here is dropped with
 * no error at all.
 */
val BOOK_MIGRATION_8_9 =
  object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.rebuildTable(
        table = "Audiobook",
        createNewTableSql =
          "CREATE TABLE IF NOT EXISTS `Audiobook_new` (`id` TEXT NOT NULL, `source` INTEGER NOT NULL, `title` TEXT NOT NULL, `titleSort` TEXT NOT NULL, `author` TEXT NOT NULL, `thumb` TEXT NOT NULL, `parentId` TEXT NOT NULL, `genre` TEXT NOT NULL, `summary` TEXT NOT NULL, `year` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `isCached` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `favorited` INTEGER NOT NULL, `viewedLeafCount` INTEGER NOT NULL, `leafCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, `chapters` TEXT NOT NULL, PRIMARY KEY(`id`))",
        columns =
          listOf(
            "id", "source", "title", "titleSort", "author", "thumb", "parentId", "genre",
            "summary", "year", "addedAt", "updatedAt", "lastViewedAt", "duration", "isCached",
            "progress", "favorited", "viewedLeafCount", "leafCount", "viewCount", "chapters",
          ),
        textColumns = setOf("id", "parentId"),
      )
    }
  }

/**
 * Adds the per-book playback-speed override (cu-20).
 *
 * `DEFAULT 0` is [Audiobook.NO_SPEED_OVERRIDE] — an existing row keeps following the global
 * preference, which is the behaviour before this column existed.
 */
val BOOK_MIGRATION_9_10 =
  object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 0")
    }
  }

/**
 * Adds narrator and series, from the Audnexus `Style`/`Mood` tags (cu-24).
 *
 * Empty and 0 defaults mean **not known yet**, which is the truthful state for every existing row:
 * these come from the per-book detail response, so they populate as books are synced rather than
 * on a library refresh.
 */
val BOOK_MIGRATION_10_11 =
  object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN narrator TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN series TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE Audiobook ADD COLUMN seriesIndex INTEGER NOT NULL DEFAULT 0")
    }
  }

/**
 * Rescales `seriesIndex` from whole books to hundredths (cu-146).
 *
 * The column stays `INTEGER` — this is a *unit* change, not a type change — so no column is added
 * or altered and the exported schema is identical apart from its version. The migration exists
 * anyway because rows written by v11 hold `2` for book two, which reads as 0.02 in the new unit:
 * a stale row would sort before every correctly-parsed book instead of in its place.
 *
 * `Audiobook.from` recomputes the value from `titleSort` on every fetch, so a library refresh would
 * heal these on its own — but not until one happens, and the wrong order is visible immediately.
 *
 * Rows at [Audiobook.NO_SERIES_INDEX] are left alone: zero means "unknown" in both units.
 */
val BOOK_MIGRATION_11_12 =
  object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "UPDATE Audiobook SET seriesIndex = seriesIndex * ${Audiobook.SERIES_INDEX_SCALE} " +
          "WHERE seriesIndex != ${Audiobook.NO_SERIES_INDEX}",
      )
    }
  }

/**
 * Every migration, in order, as one list.
 *
 * Named rather than inlined into the builder so a test can open a real database file at an older
 * schema and run the same migrations production runs — which is the only way to catch a migration
 * that disagrees with its entity, since Room validates on open (see `RoomSchemaTest`).
 */
val BOOK_MIGRATIONS =
  arrayOf(
    BOOK_MIGRATION_1_2,
    BOOK_MIGRATION_2_3,
    BOOK_MIGRATION_3_4,
    BOOK_MIGRATION_4_5,
    BOOK_MIGRATION_5_6,
    BOOK_MIGRATION_6_7,
    BOOK_MIGRATION_7_8,
    BOOK_MIGRATION_8_9,
    BOOK_MIGRATION_9_10,
    BOOK_MIGRATION_10_11,
    BOOK_MIGRATION_11_12,
  )

@Database(entities = [Audiobook::class], version = 12, exportSchema = true)
abstract class BookDatabase : RoomDatabase() {
  abstract val bookDao: BookDao
}

/**
 * Note: for the weird isCached <= :isOfflineModeActive queries, this ensures that cached items
 * are returned even when offline mode is inactive. A simple equality check would return only
 * cached items during offline mode, but only uncached items when offline mode is inactive. This
 * is an easy way to implement it at the DB level, avoiding messing code in the repository
 */
@Dao
interface BookDao {
  @Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY titleSort")
  fun getAllRows(offlineModeActive: Boolean): LiveData<List<Audiobook>>

  @Query("SELECT * FROM Audiobook")
  fun getAudiobooks(): List<Audiobook>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAll(rows: List<Audiobook>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun update(audiobook: Audiobook)

  @Query("UPDATE Audiobook SET isCached = :cached WHERE id = :bookId")
  fun updateCachedStatus(
    bookId: String,
    cached: Boolean,
  )

  @Query("SELECT * FROM Audiobook WHERE id = :id AND isCached >= :isOfflineModeActive LIMIT 1")
  fun getAudiobook(
    id: String,
    isOfflineModeActive: Boolean,
  ): LiveData<Audiobook?>

  @Query(
    "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY addedAt DESC LIMIT :bookCount",
  )
  fun getRecentlyAdded(
    bookCount: Int,
    offlineModeActive: Boolean,
  ): LiveData<List<Audiobook>>

  @Query(
    "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY addedAt DESC LIMIT :bookCount",
  )
  suspend fun getRecentlyAddedAsync(
    bookCount: Int,
    offlineModeActive: Boolean,
  ): List<Audiobook>

  @Query("SELECT * FROM Audiobook ORDER BY updatedAt DESC LIMIT 25")
  fun getOnDeck(): LiveData<List<Audiobook>>

  @Query(
    """
        SELECT * FROM Audiobook 
        WHERE isCached >= :offlineModeActive AND lastViewedAt != 0 AND progress > 10000 AND progress < duration - 120000 
        ORDER BY lastViewedAt DESC 
        LIMIT :bookCount
        """,
  )
  fun getRecentlyListened(
    bookCount: Int,
    offlineModeActive: Boolean,
  ): LiveData<List<Audiobook>>

  @Query(
    """
        SELECT * FROM Audiobook 
        WHERE isCached >= :offlineModeActive AND lastViewedAt != 0 AND progress > 10000 AND progress < duration - 120000 
        ORDER BY lastViewedAt DESC
        LIMIT :bookCount
        """,
  )
  suspend fun getRecentlyListenedAsync(
    bookCount: Int,
    offlineModeActive: Boolean,
  ): List<Audiobook>

  @Query(
    "UPDATE Audiobook SET lastViewedAt = :currentTime, progress = :progress WHERE lastViewedAt < :currentTime AND id = :bookId",
  )
  fun updateProgress(
    bookId: String,
    currentTime: Long,
    progress: Long,
  )

  @Query(
    "UPDATE Audiobook SET duration = :duration, leafCount = :trackCount, progress = :progress WHERE id = :bookId",
  )
  suspend fun updateTrackData(
    bookId: String,
    progress: Long,
    duration: Long,
    trackCount: Int,
  )

  /**
   * The same update for many books in one transaction.
   *
   * A full sync re-derives this for every book. Done one call at a time each write is its own
   * implicit transaction — 2000 fsyncs for a 2000-book library — so the loop is the cost, not the
   * statement. [androidx.room.Transaction] makes the batch one commit.
   */
  @Transaction
  suspend fun updateTrackDataForAll(updates: List<BookTrackData>) {
    updates.forEach {
      updateTrackData(it.bookId, it.bookProgress, it.bookDuration, it.trackCount)
    }
  }

  @Query(
    "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive AND (title LIKE :query OR author LIKE :query)",
  )
  fun search(
    query: String,
    offlineModeActive: Boolean,
  ): LiveData<List<Audiobook>>

  @Query(
    "SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive AND (title LIKE :query OR author LIKE :query)",
  )
  fun searchAsync(
    query: String,
    offlineModeActive: Boolean,
  ): List<Audiobook>

  @Query("DELETE FROM Audiobook")
  suspend fun clear()

  @Query("SELECT * FROM Audiobook ORDER BY lastViewedAt DESC LIMIT 1")
  suspend fun getMostRecent(): Audiobook?

  @Query("SELECT * FROM Audiobook WHERE id = :bookId LIMIT 1")
  suspend fun getAudiobookAsync(bookId: String): Audiobook?

  @Query("SELECT * FROM Audiobook WHERE isCached >= :isCached")
  fun getCachedAudiobooks(isCached: Boolean = true): LiveData<List<Audiobook>>

  @Query("SELECT * FROM Audiobook WHERE isCached >= :isCached")
  fun getCachedAudiobooksAsync(isCached: Boolean = true): List<Audiobook>

  @Query("UPDATE Audiobook SET isCached = :isCached")
  suspend fun uncacheAll(isCached: Boolean = false)

  @Query("SELECT * FROM Audiobook WHERE isCached >= :offlineModeActive ORDER BY titleSort ASC")
  fun getAllBooksAsync(offlineModeActive: Boolean): List<Audiobook>

  @Query("SELECT COUNT(*) FROM Audiobook")
  suspend fun getBookCount(): Int

  /**
   * Sets this book's speed override, or clears it with [Audiobook.NO_SPEED_OVERRIDE] (cu-20).
   *
   * `bookId` is `String` deliberately: SQLite compares across storage classes, so a numeric bind
   * against a TEXT id column matches no row, silently and with no error (cu-71).
   */
  @Query("UPDATE Audiobook SET playbackSpeed = :speed WHERE id = :bookId")
  suspend fun updatePlaybackSpeed(
    bookId: String,
    speed: Float,
  )

  @Query("DELETE FROM Audiobook WHERE id IN (:booksToRemove)")
  fun removeAll(booksToRemove: List<String>): Int

  @Query("SELECT * FROM Audiobook ORDER BY RANDOM() LIMIT 1")
  suspend fun getRandomBookAsync(): Audiobook?

  @Query("UPDATE Audiobook SET progress = 0 WHERE id = :bookId")
  suspend fun resetBookProgress(bookId: String)

  @Query("UPDATE Audiobook SET viewCount = viewCount + 1 WHERE id = :bookId")
  suspend fun setWatched(bookId: String)

  @Query("UPDATE Audiobook SET viewCount = 0 WHERE id = :bookId")
  suspend fun setUnwatched(bookId: String)
}

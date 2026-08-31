package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mattpvaughn.chronicle.data.model.Chapter

private const val CHAPTER_DATABASE_NAME = "chapter_db"

private lateinit var INSTANCE: ChapterDatabase

fun getChapterDatabase(context: Context): ChapterDatabase {
  synchronized(ChapterDatabase::class.java) {
    if (!::INSTANCE.isInitialized) {
      INSTANCE =
        Room.databaseBuilder(
          context.applicationContext,
          ChapterDatabase::class.java,
          CHAPTER_DATABASE_NAME,
        ).addMigrations(*CHAPTER_MIGRATIONS).build()
    }
  }
  return INSTANCE
}

@Database(entities = [Chapter::class], version = 3, exportSchema = true)
abstract class ChapterDatabase : RoomDatabase() {
  abstract val chapterDao: ChapterDao
}

/**
 * Note: for the weird isCached <= :isOfflineModeActive queries, this ensures that cached items
 * are returned even when offline mode is inactive. A simple equality check would return only
 * cached items during offline mode, but only uncached items when offline mode is inactive. This
 * is an easy way to implement it at the DB level, avoiding messing code in the repository
 */
@Dao
interface ChapterDao {
  @Query("SELECT * FROM Chapter ORDER BY discNumber, `index`")
  fun getAllRows(): LiveData<List<Chapter>>

  @Query("SELECT * FROM Chapter")
  fun getChapters(): List<Chapter>

  /**
   * The chapters of one book, in playback order.
   *
   * The read path the UI actually needs: `getChapters()` returns the whole library's chapters,
   * which is never what a screen wants. Ordered by `discNumber, index` to match
   * `Chapter.compareTo`, so callers do not have to re-sort.
   */
  @Query("SELECT * FROM Chapter WHERE bookId = :bookId ORDER BY discNumber, `index`")
  suspend fun getChaptersForBook(bookId: String): List<Chapter>

  @Query("SELECT * FROM Chapter WHERE bookId = :bookId ORDER BY discNumber, `index`")
  fun getChaptersForBookLive(bookId: String): LiveData<List<Chapter>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAll(rows: List<Chapter>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun update(chapter: Chapter)

  // These bind against `id`, which is TEXT since cu-71. A numeric parameter would compare
  // across storage classes in SQLite and match no row — no error, just silently nothing.
  @Query("UPDATE Chapter SET downloaded = :cached WHERE id = :chapterId")
  fun updateCachedStatus(
    chapterId: String,
    cached: Boolean,
  )

  @Query("DELETE FROM Chapter WHERE id IN (:chaptersToRemove)")
  fun removeAll(chaptersToRemove: List<String>): Int

  /**
   * Drops every chapter of one book, so a refetch replaces rather than merges.
   *
   * Needed because a book's chapter list can *shrink* — a re-tagged file, or a switch from server
   * chapters to the per-track fallback. `insertAll` with `REPLACE` only overwrites rows whose key
   * matches, so without this the stale extras would survive and the book would show chapters that
   * no longer exist.
   */
  @Query("DELETE FROM Chapter WHERE bookId = :bookId")
  suspend fun removeAllForBook(bookId: String): Int
}

/**
 * Retypes `id`, `trackId` and `bookId` to TEXT. These were Long while books and tracks were Int, so this also removes that inconsistency (cu-71).
 *
 * A table rebuild because SQLite cannot alter a column type or a primary key. The column list comes
 * from the exported v1 schema, which is the authority — a column omitted here is dropped with
 * no error at all.
 */
val CHAPTER_MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.rebuildTable(
        table = "Chapter",
        createNewTableSql =
          "CREATE TABLE IF NOT EXISTS `Chapter_new` (`title` TEXT NOT NULL, `id` TEXT NOT NULL, `index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, `startTimeOffset` INTEGER NOT NULL, `endTimeOffset` INTEGER NOT NULL, `downloaded` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `bookId` TEXT NOT NULL, PRIMARY KEY(`id`))",
        columns =
          listOf(
            "title", "id", "index", "discNumber", "startTimeOffset", "endTimeOffset",
            "downloaded", "trackId", "bookId",
          ),
        textColumns = setOf("bookId", "id", "trackId"),
      )
    }
  }

/**
 * Moves the primary key from `id` alone to `(bookId, trackId, discNumber, index)` (cu-49).
 *
 * `id` is not unique across books — see [io.github.mattpvaughn.chronicle.data.model.Chapter].
 * SQLite cannot alter a primary key, so this is another table rebuild; no column changes type.
 *
 * **Existing rows are discarded rather than copied**, which is safe here and only here: no code
 * ever wrote to this table (nothing provided `ChapterDatabase` in Dagger until cu-49), so it is
 * empty on every real device. Copying would also be *wrong* — pre-cu-49 rows carry
 * `bookId = NO_AUDIOBOOK_FOUND_ID` because neither chapter path set it, so every row would
 * collide on the new key and the insert would fail or silently keep one. Chapters are derived
 * data, refetched from the server per book, so there is nothing here to lose. This is the one
 * table where that argument holds; never reason this way about books or tracks, which hold
 * listening progress.
 */
val CHAPTER_MIGRATION_2_3 =
  object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("DROP TABLE IF EXISTS `Chapter`")
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS `Chapter` (`title` TEXT NOT NULL, `id` TEXT NOT NULL, " +
          "`index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, " +
          "`startTimeOffset` INTEGER NOT NULL, `endTimeOffset` INTEGER NOT NULL, " +
          "`downloaded` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
          "PRIMARY KEY(`bookId`, `trackId`, `discNumber`, `index`))",
      )
    }
  }

/** Every migration, in order. Named so `RoomSchemaTest` runs exactly what production runs. */
val CHAPTER_MIGRATIONS = arrayOf(CHAPTER_MIGRATION_1_2, CHAPTER_MIGRATION_2_3)

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

@Database(entities = [Chapter::class], version = 2, exportSchema = true)
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
      db.rebuildTableWithTextIds(
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

/** Every migration, in order. Named so `RoomSchemaTest` runs exactly what production runs. */
val CHAPTER_MIGRATIONS = arrayOf(CHAPTER_MIGRATION_1_2)

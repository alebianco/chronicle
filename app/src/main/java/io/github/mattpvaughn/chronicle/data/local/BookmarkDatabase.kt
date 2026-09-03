package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.OffsetConverters

private const val BOOKMARK_DATABASE_NAME = "bookmark_db"

private lateinit var INSTANCE: BookmarkDatabase

fun getBookmarkDatabase(context: Context): BookmarkDatabase {
  synchronized(BookmarkDatabase::class.java) {
    if (!::INSTANCE.isInitialized) {
      INSTANCE =
        Room.databaseBuilder(
          context.applicationContext,
          BookmarkDatabase::class.java,
          BOOKMARK_DATABASE_NAME,
        ).addMigrations(*BOOKMARK_MIGRATIONS).build()
    }
  }
  return INSTANCE
}

/**
 * The bookmark store (cu-22).
 *
 * A fifth database rather than a table in `BookDatabase`, so a library re-sync cannot reach it.
 * See [Bookmark] for why that matters. No `fallbackToDestructiveMigration`, like the other four:
 * a bad migration must crash rather than silently discard the user's notes.
 */
@Database(entities = [Bookmark::class], version = 1, exportSchema = true)
@TypeConverters(OffsetConverters::class)
abstract class BookmarkDatabase : RoomDatabase() {
  abstract val bookmarkDao: BookmarkDao
}

/**
 * Every migration, in order. Empty at v1, but named and wired now so the *shape* is in place —
 * `RoomSchemaTest` runs exactly what production runs, and a first migration added later has
 * somewhere to go that is already under test.
 */
val BOOKMARK_MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

@Dao
interface BookmarkDao {
  /**
   * One book's bookmarks, earliest position first.
   *
   * `createdAt` breaks ties so the order is stable when two bookmarks mark the same moment —
   * without it SQLite may return them in either order and the list would reshuffle between reads.
   */
  @Query("SELECT * FROM Bookmark WHERE bookId = :bookId ORDER BY position ASC, createdAt ASC")
  fun getBookmarksForBook(bookId: String): LiveData<List<Bookmark>>

  @Query("SELECT * FROM Bookmark WHERE bookId = :bookId ORDER BY position ASC, createdAt ASC")
  suspend fun getBookmarksForBookAsync(bookId: String): List<Bookmark>

  /** Every bookmark, for the backup export. Ordered so an exported file is diff-stable. */
  @Query("SELECT * FROM Bookmark ORDER BY bookId ASC, position ASC, createdAt ASC")
  suspend fun getAllAsync(): List<Bookmark>

  @Query("SELECT * FROM Bookmark WHERE id = :id LIMIT 1")
  suspend fun getBookmarkAsync(id: String): Bookmark?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(bookmark: Bookmark)

  /**
   * Inserts many, replacing on id collision.
   *
   * REPLACE is what makes a backup import idempotent: restoring the same file twice overwrites the
   * same rows rather than duplicating them, because the id travels in the file.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(bookmarks: List<Bookmark>)

  /**
   * Updates just the note.
   *
   * `id` binds as `String` deliberately — the column is TEXT, and SQLite compares across storage
   * classes, so a numeric bind would match no row silently and with no error (cu-71).
   */
  @Query("UPDATE Bookmark SET note = :note WHERE id = :id")
  suspend fun updateNote(
    id: String,
    note: String,
  )

  @Query("DELETE FROM Bookmark WHERE id = :id")
  suspend fun delete(id: String)

  @Query("DELETE FROM Bookmark WHERE bookId = :bookId")
  suspend fun deleteAllForBook(bookId: String)

  @Query("SELECT COUNT(*) FROM Bookmark")
  suspend fun count(): Int
}

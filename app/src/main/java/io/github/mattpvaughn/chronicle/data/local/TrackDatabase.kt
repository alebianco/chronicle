package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

private const val TRACK_DATABASE_NAME = "track_db"

private lateinit var INSTANCE: TrackDatabase

fun getTrackDatabase(context: Context): TrackDatabase {
  synchronized(TrackDatabase::class.java) {
    if (!::INSTANCE.isInitialized) {
      INSTANCE =
        Room.databaseBuilder(
          context.applicationContext,
          TrackDatabase::class.java,
          TRACK_DATABASE_NAME,
        ).addMigrations(*TRACK_MIGRATIONS).build()
    }
  }
  return INSTANCE
}

/**
 * Retypes `id` and `parentKey` to TEXT. `parentKey` is a book id, so it converts in step with Audiobook (cu-71).
 *
 * A table rebuild because SQLite cannot alter a column type or a primary key. The column list comes
 * from the exported v4 schema, which is the authority — a column omitted here is dropped with
 * no error at all.
 */
val MIGRATION_4_5 =
  object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.rebuildTable(
        table = "MediaItemTrack",
        createNewTableSql =
          "CREATE TABLE IF NOT EXISTS `MediaItemTrack_new` (`id` TEXT NOT NULL, `parentKey` TEXT NOT NULL, `title` TEXT NOT NULL, `playQueueItemID` INTEGER NOT NULL, `thumb` TEXT, `index` INTEGER NOT NULL, `discNumber` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `media` TEXT NOT NULL, `album` TEXT NOT NULL, `artist` TEXT NOT NULL, `genre` TEXT NOT NULL, `cached` INTEGER NOT NULL, `artwork` TEXT, `viewCount` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `size` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        columns =
          listOf(
            "id", "parentKey", "title", "playQueueItemID", "thumb", "index", "discNumber",
            "duration", "media", "album", "artist", "genre", "cached", "artwork",
            "viewCount", "progress", "lastViewedAt", "updatedAt", "size",
          ),
        textColumns = setOf("id", "parentKey"),
      )
    }
  }

@Database(entities = [MediaItemTrack::class], version = 6, exportSchema = true)
abstract class TrackDatabase : RoomDatabase() {
  abstract val trackDao: TrackDao
}

val MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "ALTER TABLE MediaItemTrack ADD COLUMN size INTEGER NOT NULL DEFAULT 0",
      )
    }
  }

val MIGRATION_2_3 =
  object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "ALTER TABLE MediaItemTrack ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0",
      )
    }
  }

val MIGRATION_3_4 =
  object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "ALTER TABLE MediaItemTrack ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 1",
      )
    }
  }

@Dao
interface TrackDao {
  @Query("SELECT * FROM MediaItemTrack")
  fun getAllTracks(): LiveData<List<MediaItemTrack>>

  // Ordered, because callers derive book position from the result and `getTrackStartTime` sums
  // the tracks *before* the active one. It sorts defensively now (cu-115), but an unordered
  // whole-library read is a trap for anything else that groups or slices this list, and the
  // `parentKey, discNumber, index` index makes the ordering free.
  @Query("SELECT * FROM MediaItemTrack ORDER BY `parentKey`, `discNumber` ASC, `index` ASC")
  suspend fun getAllTracksAsync(): List<MediaItemTrack>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAll(rows: List<MediaItemTrack>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun update(track: MediaItemTrack)

  @Query("SELECT * FROM MediaItemTrack WHERE id = :id LIMIT 1")
  suspend fun getTrackAsync(id: String): MediaItemTrack?

  @Query(
    "SELECT * FROM MediaItemTrack WHERE parentKey = :bookId AND cached >= :isOfflineMode ORDER BY `discNumber` ASC, `index` ASC",
  )
  fun getTracksForAudiobook(
    bookId: String,
    isOfflineMode: Boolean,
  ): LiveData<List<MediaItemTrack>>

  @Query(
    "SELECT * FROM MediaItemTrack WHERE parentKey = :id AND cached >= :offlineModeActive ORDER BY `discNumber` ASC, `index` ASC",
  )
  suspend fun getTracksForAudiobookAsync(
    id: String,
    offlineModeActive: Boolean,
  ): List<MediaItemTrack>

  @Query("SELECT COUNT(*) FROM MediaItemTrack WHERE parentKey = :bookId")
  suspend fun getTrackCountForAudiobookAsync(bookId: String): Int

  @Query(
    "UPDATE MediaItemTrack SET progress = :trackProgress, lastViewedAt = :lastViewedAt WHERE id = :trackId",
  )
  fun updateProgress(
    trackProgress: Long,
    trackId: String,
    lastViewedAt: Long,
  )

  @Query("DELETE FROM MediaItemTrack")
  fun clear()

  @Query("UPDATE MediaItemTrack SET cached = :isCached WHERE id = :trackId")
  fun updateCachedStatus(
    trackId: String,
    isCached: Boolean,
  ): Int

  @Query("SELECT * FROM MediaItemTrack WHERE cached = :isCached")
  fun getCachedTracksAsync(isCached: Boolean = true): List<MediaItemTrack>

  @Query("SELECT COUNT(*) FROM MediaItemTrack WHERE cached = :isCached AND parentKey = :bookId")
  suspend fun getCachedTrackCountForBookAsync(
    bookId: String,
    isCached: Boolean = true,
  ): Int

  @Query("UPDATE MediaItemTrack SET cached = :isCached")
  suspend fun uncacheAll(isCached: Boolean = false)

  @Query("SELECT * FROM MediaItemTrack WHERE title LIKE :title")
  suspend fun findTrackByTitle(title: String): MediaItemTrack?
}

/**
 * Adds the `parentKey, discNumber, index` index (cu-110).
 *
 * Pure addition — no data moves, so a `CREATE INDEX` is enough and there is no rebuild to get
 * wrong. The name must match what Room generates for the entity's `@Index`
 * (`index_<table>_<cols joined by _>`), or Room's on-open validation rejects the schema.
 */
val MIGRATION_5_6 =
  object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_MediaItemTrack_parentKey_discNumber_index` " +
          "ON `MediaItemTrack` (`parentKey`, `discNumber`, `index`)",
      )
    }
  }

/** Every migration, in order. Named so `RoomSchemaTest` runs exactly what production runs. */
val TRACK_MIGRATIONS =
  arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

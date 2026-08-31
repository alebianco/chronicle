package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mattpvaughn.chronicle.data.model.Collection

private const val COLLECTIONS_DATABASE_NAME = "collections_db"

private lateinit var INSTANCE: CollectionsDatabase

fun getCollectionsDatabase(context: Context): CollectionsDatabase {
  synchronized(CollectionsDatabase::class.java) {
    if (!::INSTANCE.isInitialized) {
      INSTANCE =
        Room.databaseBuilder(
          context.applicationContext,
          CollectionsDatabase::class.java,
          COLLECTIONS_DATABASE_NAME,
        ).addMigrations(*COLLECTIONS_MIGRATIONS).build()
    }
  }
  return INSTANCE
}

@Database(entities = [Collection::class], version = 2, exportSchema = true)
abstract class CollectionsDatabase : RoomDatabase() {
  abstract val collectionsDao: CollectionsDao
}

@Dao
interface CollectionsDao {
  @Query("SELECT * FROM Collection ORDER BY title")
  fun getAllRows(): LiveData<List<Collection>>

  @Query("SELECT * FROM Collection WHERE id = :id LIMIT 1")
  fun getCollection(id: String): LiveData<Collection?>

  @Query("SELECT * FROM Collection WHERE :collectionId = id")
  suspend fun getCollectionAsync(collectionId: String): Collection

  @Query("SELECT * FROM Collection")
  fun getCollections(): List<Collection>

  @Query("SELECT Count(id) FROM Collection")
  fun countCollections(): LiveData<Long>

  @Query("DELETE FROM Collection")
  suspend fun clear()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAll(rows: List<Collection>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun update(collection: Collection)

  // Binds against `id`, TEXT since cu-71: a numeric parameter matches no row, silently.
  @Query("DELETE FROM Collection WHERE id IN (:collectionsToRemove)")
  fun removeAll(collectionsToRemove: List<String>): Int
}

/**
 * Retypes `id` to TEXT (cu-71). `childIds` needs no change: its converter already serialized a
 * JSON array of strings, and only the Kotlin type moved from List<Long> to List<String>.
 *
 * A table rebuild because SQLite cannot alter a column type or a primary key. The column list comes
 * from the exported v1 schema, which is the authority — a column omitted here is dropped with
 * no error at all.
 */
val COLLECTIONS_MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.rebuildTableWithTextIds(
        table = "Collection",
        createNewTableSql =
          "CREATE TABLE IF NOT EXISTS `Collection_new` (`id` TEXT NOT NULL, `source` INTEGER NOT NULL, `title` TEXT NOT NULL, `childCount` INTEGER NOT NULL, `sortType` TEXT NOT NULL, `isCached` INTEGER NOT NULL, `thumb` TEXT NOT NULL, `childIds` TEXT NOT NULL, PRIMARY KEY(`id`))",
        columns =
          listOf(
            "id",
            "source",
            "title",
            "childCount",
            "sortType",
            "isCached",
            "thumb",
            "childIds",
          ),
        textColumns = setOf("id"),
      )
    }
  }

/** Every migration, in order. Named so `RoomSchemaTest` runs exactly what production runs. */
val COLLECTIONS_MIGRATIONS = arrayOf(COLLECTIONS_MIGRATION_1_2)

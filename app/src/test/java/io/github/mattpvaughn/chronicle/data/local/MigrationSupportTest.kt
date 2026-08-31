package io.github.mattpvaughn.chronicle.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The table-rebuild helper the cu-71 id migrations are built on.
 *
 * Tested on its own because four migrations over databases holding listening progress depend on
 * it, and its worst failure mode is silent: a column left out of `columns` is dropped with no
 * error at all.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationSupportTest {
  @Test
  fun `a rebuild retypes the id and keeps every row`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
    db.execSQL("INSERT INTO Thing (id, name) VALUES (1001, 'Dune')")
    db.execSQL("INSERT INTO Thing (id, name) VALUES (1002, 'Mistborn')")

    db.rebuildThing()

    db.query("SELECT id, name FROM Thing ORDER BY name").use { cursor ->
      assertEquals(2, cursor.count)
      cursor.moveToFirst()
      assertEquals("1001", cursor.getString(0))
      assertEquals("Dune", cursor.getString(1))
    }
  }

  @Test
  fun `the rebuilt column reports TEXT affinity`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")

    db.rebuildThing()

    db.query("PRAGMA table_info(`Thing`)").use { cursor ->
      val nameIndex = cursor.getColumnIndex("name")
      val typeIndex = cursor.getColumnIndex("type")
      var idType: String? = null
      while (cursor.moveToNext()) {
        if (cursor.getString(nameIndex) == "id") idType = cursor.getString(typeIndex)
      }
      assertEquals("Room validates this on open; INTEGER here is a crash later", "TEXT", idType)
    }
  }

  /**
   * Values must end up with TEXT *storage*, not merely a TEXT-declared column.
   *
   * Note this passes with or without the helper's `CAST`: SQLite applies TEXT affinity on insert,
   * so a copied INTEGER already lands as text. Recorded because an earlier version of this test
   * claimed to prove the CAST was load-bearing, and it does not — the cast is documentation.
   */
  @Test
  fun `a numeric value ends up with text storage`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
    db.execSQL("INSERT INTO Thing (id, name) VALUES (1001, 'Dune')")

    db.rebuildThing()

    db.query("SELECT typeof(id) FROM Thing").use { cursor ->
      cursor.moveToFirst()
      assertEquals("text", cursor.getString(0))
    }
  }

  @Test
  fun `a non-id column keeps its value`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
    db.execSQL("INSERT INTO Thing (id, name) VALUES (1001, 'Dune')")

    db.rebuildThing()

    db.query("SELECT name FROM Thing").use { cursor ->
      cursor.moveToFirst()
      assertEquals("omitting a column from `columns` drops it silently", "Dune", cursor.getString(0))
    }
  }

  @Test
  fun `no leftover temporary table survives`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")

    db.rebuildThing()

    db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='Thing_new'").use { cursor ->
      assertEquals("the temporary table must be renamed, not left behind", 0, cursor.count)
    }
  }

  @Test
  fun `an empty table rebuilds without error`() {
    val db = openDatabase("CREATE TABLE Thing (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")

    db.rebuildThing()

    db.query("SELECT COUNT(*) FROM Thing").use { cursor ->
      cursor.moveToFirst()
      assertEquals(0, cursor.getInt(0))
    }
  }

  private fun SupportSQLiteDatabase.rebuildThing() =
    rebuildTable(
      table = "Thing",
      createNewTableSql =
        "CREATE TABLE Thing_new (id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))",
      columns = listOf("id", "name"),
      textColumns = setOf("id"),
    )

  /** Mirrors `RoomMigrationTest.openHelperFor`: create at the old schema, reopen through Room's wrapper. */
  private fun openDatabase(createSql: String): SupportSQLiteDatabase {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.cacheDir, "migration-support-${System.nanoTime()}.db")
    file.delete()
    SQLiteDatabase.openOrCreateDatabase(file, null).apply {
      execSQL(createSql)
      close()
    }
    val config =
      SupportSQLiteOpenHelper.Configuration
        .builder(context)
        .name(file.absolutePath)
        .callback(
          object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(
              db: SupportSQLiteDatabase,
              oldVersion: Int,
              newVersion: Int,
            ) = Unit
          },
        ).build()
    return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
  }
}

package io.github.mattpvaughn.chronicle.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Rebuilds [table] so the columns named in [textColumns] hold TEXT instead of INTEGER.
 *
 * SQLite cannot change a column's type or a table's primary key, so retyping an id means
 * create-copy-drop-rename. Every migration this codebase had before cu-71 was a simple
 * `ADD COLUMN`, so there was no precedent to copy — hence one tested helper rather than the same
 * five statements written out four times, where a mistyped column list drops a column's data with
 * no error at all.
 *
 * `CAST(x AS TEXT)` is belt-and-braces rather than load-bearing: SQLite applies TEXT affinity on
 * insert, so a copied INTEGER already lands as text (verified — `typeof()` reports `text` with or
 * without it). The cast is kept because it makes the intent explicit at the point of copy, and
 * because affinity rules are subtle enough that a future column type change should not depend on
 * remembering them. It is not what makes the migration correct.
 *
 * @param columns every column in the table. Order must match [createNewTableSql]'s declaration
 *   order, and omitting a column silently discards its data — so callers build this list from the
 *   exported schema in `app/schemas/`, which is the authority.
 */
fun SupportSQLiteDatabase.rebuildTableWithTextIds(
  table: String,
  createNewTableSql: String,
  columns: List<String>,
  textColumns: Set<String>,
) {
  execSQL(createNewTableSql)
  val select =
    columns.joinToString(", ") { column ->
      if (column in textColumns) "CAST(`$column` AS TEXT)" else "`$column`"
    }
  val insert = columns.joinToString(", ") { "`$it`" }
  execSQL("INSERT INTO `${table}_new` ($insert) SELECT $select FROM `$table`")
  execSQL("DROP TABLE `$table`")
  execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
}

package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bookmarks in the backup file (cu-22, criterion 3).
 *
 * The file is the durable artifact — a user may keep it for years and open it in an editor — so
 * what matters is that a round trip is lossless, that a restore is idempotent, and that a
 * hand-edited file cannot produce nonsense rows.
 */
class BookmarkBackupTest {
  private fun bookmark(
    id: String = "bm-1",
    bookId: String = "1001",
    position: Long = 90_000L,
    note: String = "the riddle game",
    createdAt: Long = 1_700_000_000_000L,
  ) = Bookmark(
    id = id,
    bookId = bookId,
    position = BookOffset(position),
    note = note,
    createdAt = createdAt,
  )

  @Test
  fun `a bookmark survives a round trip through the file format`() {
    val original = bookmark()

    val restored = original.toBackup().toBookmarkOrNull()

    assertEquals(original, restored)
  }

  @Test
  fun `a bookmark with no note round-trips`() {
    val original = bookmark(note = "")

    assertEquals(original, original.toBackup().toBookmarkOrNull())
  }

  /**
   * The id is what makes a restore idempotent, so a row without one would insert a fresh
   * duplicate on every import.
   */
  @Test
  fun `a row with no id is refused`() {
    assertNull(bookmark(id = "").toBackup().toBookmarkOrNull())
    assertNull(bookmark(id = "   ").toBackup().toBookmarkOrNull())
  }

  /** A bookmark with no book cannot be listed or jumped to. */
  @Test
  fun `a row with no bookId is refused`() {
    assertNull(bookmark(bookId = "").toBackup().toBookmarkOrNull())
  }

  /**
   * Clamped rather than refused: the *note* is the part worth keeping, and the start of the book is
   * a harmless place to point. Refusing would throw away something the user wrote.
   */
  @Test
  fun `a negative position is clamped, not refused`() {
    val restored = bookmark(position = -5_000L).toBackup().toBookmarkOrNull()

    assertEquals(BookOffset.ZERO, restored?.position)
    assertEquals("the note must survive a bad position", "the riddle game", restored?.note)
  }

  @Test
  fun `importing drops untrustworthy rows and keeps the rest`() {
    val backup =
      SettingsBackup(
        bookmarks =
          listOf(
            bookmark(id = "bm-1").toBackup(),
            bookmark(id = "").toBackup(),
            bookmark(id = "bm-2", bookId = "").toBackup(),
            bookmark(id = "bm-3").toBackup(),
          ),
      )

    val imported = importBookmarks(backup)

    assertEquals(listOf("bm-1", "bm-3"), imported.map { it.id })
  }

  /**
   * A hand-edited file can repeat an id. Letting both through would make the winning row depend on
   * insertion order, which is not something a file format should leave undefined.
   */
  @Test
  fun `a repeated id keeps only the first`() {
    val backup =
      SettingsBackup(
        bookmarks =
          listOf(
            bookmark(id = "bm-1", note = "first").toBackup(),
            bookmark(id = "bm-1", note = "second").toBackup(),
          ),
      )

    val imported = importBookmarks(backup)

    assertEquals(1, imported.size)
    assertEquals("first", imported.single().note)
  }

  @Test
  fun `a file with no bookmarks imports as none`() {
    assertTrue(importBookmarks(SettingsBackup()).isEmpty())
  }

  /**
   * The schema version had to move when the format grew a field. `importSettingsOrNull` refuses a
   * *newer* file, and that refusal only ever means anything if the number changes — otherwise this
   * build could not tell a v1 file that never had bookmarks from a v2 file whose bookmarks were
   * dropped.
   */
  @Test
  fun `the schema version moved when bookmarks were added`() {
    assertTrue(
      "adding a top-level array to the file format must bump the version",
      BACKUP_SCHEMA_VERSION >= 2,
    )
  }

  /** A v1 file predates the field entirely and must still restore its settings. */
  @Test
  fun `an older file without the bookmarks field is still accepted`() {
    val v1 = SettingsBackup(version = 1, settings = mapOf(PrefsRepo.KEY_SKIP_SILENCE to "true"))

    val allowed = importSettingsOrNull(v1)

    assertEquals(mapOf(PrefsRepo.KEY_SKIP_SILENCE to "true"), allowed)
    assertTrue(importBookmarks(v1).isEmpty())
  }

  /**
   * Bookmarks must **not** be reachable through the settings allowlist: it gates preference keys,
   * and a bookmark is not one. This pins the separation so a future change cannot smuggle records
   * through the map and bypass the validation above.
   */
  @Test
  fun `bookmarks do not travel as settings`() {
    val exported = exportSettings(mapOf("bookmarks" to "[]"))

    assertTrue(exported.settings.isEmpty())
  }
}

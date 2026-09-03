package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The SAF half of the settings backup (cu-77).
 *
 * `BackupSchemaTest` covers the allowlist and the parsing in isolation; what needs an Android
 * runtime is the part that actually moves bytes — a `ContentResolver` writing to a `Uri`, and the
 * typed writes landing back in `SharedPreferences`. Robolectric gives both without a device.
 *
 * The headline case is [a wipe and restore round trip restores every setting], which is cu-77's
 * acceptance criterion stated as a test: settings are changed away from their defaults, exported,
 * wiped, and restored, then compared **key by key** rather than eyeballed.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsBackupRepoTest {
  private lateinit var context: Context
  private lateinit var prefs: SharedPreferences
  private lateinit var repo: SettingsBackupRepo
  private lateinit var backupFile: File
  private lateinit var bookmarks: FakeBookmarkRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    prefs = context.getSharedPreferences("SettingsBackupRepoTest", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    backupFile = File.createTempFile("chronicle-backup", ".json")
    bookmarks = FakeBookmarkRepository()
    repo =
      SettingsBackupRepo(
        sharedPreferences = prefs,
        contentResolver = context.contentResolver,
        moshi = Moshi.Builder().build(),
        dispatchers = TestDispatcherProvider(),
        bookmarkRepository = bookmarks,
      )
  }

  /**
   * An in-memory [IBookmarkRepository] with the DAO's REPLACE-on-id semantics.
   *
   * Written out rather than mocked because the *semantics* are the thing under test: a restore is
   * idempotent only because the id is the key, and a mock returning canned values would assert
   * nothing about that.
   */
  private class FakeBookmarkRepository : IBookmarkRepository {
    val stored = linkedMapOf<String, Bookmark>()

    override fun getBookmarksForBook(bookId: String) = throw UnsupportedOperationException("not needed by these tests")

    override suspend fun getBookmarksForBookAsync(bookId: String) = stored.values.filter { it.bookId == bookId }

    override suspend fun getAllAsync() = stored.values.toList()

    override suspend fun add(
      bookId: String,
      position: BookOffset,
      note: String,
      createdAt: Long,
    ): Bookmark {
      val bookmark =
        Bookmark(bookId = bookId, position = position, note = note, createdAt = createdAt)
      stored[bookmark.id] = bookmark
      return bookmark
    }

    override suspend fun updateNote(
      id: String,
      note: String,
    ) {
      stored[id]?.let { stored[id] = it.copy(note = note) }
    }

    override suspend fun delete(id: String) {
      stored.remove(id)
    }

    override suspend fun restore(bookmarks: List<Bookmark>): Int {
      bookmarks.forEach { stored[it.id] = it }
      return bookmarks.size
    }
  }

  /** The non-default values used across the round-trip tests, one per stored type. */
  private fun writeNonDefaultSettings() {
    prefs.edit()
      .putString(PrefsRepo.KEY_BOOK_COVER_STYLE, PrefsRepo.BOOK_COVER_STYLE_RECT)
      .putBoolean(PrefsRepo.KEY_SKIP_SILENCE, true)
      .putBoolean(PrefsRepo.KEY_HIDE_PLAYED_AUDIOBOOKS, true)
      .putLong(PrefsRepo.KEY_JUMP_FORWARD_SECONDS, 45L)
      .putLong(PrefsRepo.KEY_REFRESH_RATE, 120L)
      .putFloat(PrefsRepo.KEY_PLAYBACK_SPEED, 1.75f)
      .commit()
  }

  @Test
  fun `a wipe and restore round trip restores every setting`() =
    runTest {
      writeNonDefaultSettings()
      val before = prefs.all.filterKeys { it in BACKUP_SETTING_KEYS }
      assertTrue("the fixture must actually set something", before.isNotEmpty())

      val export = repo.exportTo(backupFile.toUri())
      assertEquals(
        SettingsBackupRepo.ExportResult.Written(before.size),
        export,
      )

      // The wipe: exactly what a reinstall looks like from the prefs' point of view.
      prefs.edit().clear().commit()
      assertTrue(prefs.all.filterKeys { it in BACKUP_SETTING_KEYS }.isEmpty())

      val import = repo.importFrom(backupFile.toUri())
      assertEquals(
        SettingsBackupRepo.ImportResult.Applied(applied = before.size, skipped = 0),
        import,
      )

      // Compared key by key, and with types: a Long that came back as a String would
      // satisfy a `toString()` comparison but crash the first `getLong` on it.
      assertEquals(before, prefs.all.filterKeys { it in BACKUP_SETTING_KEYS })
    }

  @Test
  fun `restored values are readable at their declared types`() =
    runTest {
      writeNonDefaultSettings()
      repo.exportTo(backupFile.toUri())
      prefs.edit().clear().commit()
      repo.importFrom(backupFile.toUri())

      // The real reason the round trip stores types rather than strings: these are the calls
      // PrefsRepo makes, and each throws a ClassCastException on a wrongly-typed entry.
      assertEquals(
        PrefsRepo.BOOK_COVER_STYLE_RECT,
        prefs.getString(PrefsRepo.KEY_BOOK_COVER_STYLE, ""),
      )
      assertEquals(true, prefs.getBoolean(PrefsRepo.KEY_SKIP_SILENCE, false))
      assertEquals(45L, prefs.getLong(PrefsRepo.KEY_JUMP_FORWARD_SECONDS, 0L))
      assertEquals(120L, prefs.getLong(PrefsRepo.KEY_REFRESH_RATE, 0L))
      assertEquals(1.75f, prefs.getFloat(PrefsRepo.KEY_PLAYBACK_SPEED, 0f), 0.0001f)
    }

  @Test
  fun `an exported file carries no auth token`() =
    runTest {
      // The security property, asserted against the bytes on disk rather than against the
      // exporter's return value — this is the artifact the user syncs to a cloud folder.
      prefs.edit()
        .putString("auth_token", "plex-account-token")
        .putString("server_token", "plex-server-token")
        .putString("user", """{"authToken":"nested-token"}""")
        .putBoolean(PrefsRepo.KEY_SKIP_SILENCE, true)
        .commit()

      repo.exportTo(backupFile.toUri())

      val written = backupFile.readText()
      assertTrue("the export must contain the setting", written.contains("key_skip_silence"))
      listOf("plex-account-token", "plex-server-token", "nested-token", "auth_token")
        .forEach { assertTrue("'$it' leaked into the export", !written.contains(it)) }
    }

  @Test
  fun `the exported file is readable json ending in a newline`() =
    runTest {
      writeNonDefaultSettings()
      repo.exportTo(backupFile.toUri())

      val written = backupFile.readText()
      // A file-over-app artifact: indented so it can be read and hand-edited, and
      // newline-terminated because POSIX tools expect it.
      assertTrue("should be indented", written.contains("\n  "))
      assertTrue("should end with a newline", written.endsWith("\n"))
      assertTrue("should declare its schema", written.contains("\"version\""))
    }

  @Test
  fun `a file from a newer schema is refused and changes nothing`() =
    runTest {
      prefs.edit().putBoolean(PrefsRepo.KEY_SKIP_SILENCE, false).commit()
      backupFile.writeText(
        """{"version":${BACKUP_SCHEMA_VERSION + 1},"settings":{"key_skip_silence":"true"}}""",
      )

      val result = repo.importFrom(backupFile.toUri())

      assertEquals(
        SettingsBackupRepo.ImportResult.WrongVersion(BACKUP_SCHEMA_VERSION + 1),
        result,
      )
      assertEquals(
        "a refused file must not half-apply",
        false,
        prefs.getBoolean(PrefsRepo.KEY_SKIP_SILENCE, false),
      )
    }

  @Test
  fun `an unparseable file is reported rather than crashing`() =
    runTest {
      backupFile.writeText("this is not json at all {{{")

      val result = repo.importFrom(backupFile.toUri())

      assertTrue(
        "expected Unreadable, got $result",
        result is SettingsBackupRepo.ImportResult.Unreadable,
      )
    }

  @Test
  fun `a json file that is not a backup is reported rather than applied`() =
    runTest {
      // Valid JSON, wrong document — the user picked the wrong file in the picker.
      backupFile.writeText("""{"unrelated":"document","numbers":[1,2,3]}""")

      val result = repo.importFrom(backupFile.toUri())

      // Moshi fills absent fields with the data class defaults, so this parses to
      // version 0 with no settings. Applying nothing is the honest outcome; what must not
      // happen is a crash or a silent success claim about settings that were never there.
      assertEquals(
        SettingsBackupRepo.ImportResult.Applied(applied = 0, skipped = 0),
        result,
      )
    }

  @Test
  fun `a malformed value is skipped and reported while the rest applies`() =
    runTest {
      backupFile.writeText(
        """
        {"version":$BACKUP_SCHEMA_VERSION,"settings":{
          "key_jump_forward_seconds":"not-a-number",
          "key_skip_silence":"true"
        }}
        """.trimIndent(),
      )

      val result = repo.importFrom(backupFile.toUri())

      assertEquals(
        SettingsBackupRepo.ImportResult.Applied(applied = 1, skipped = 1),
        result,
      )
      assertEquals(true, prefs.getBoolean(PrefsRepo.KEY_SKIP_SILENCE, false))
      assertEquals(
        "the malformed key must keep its previous (absent) value",
        0L,
        prefs.getLong(PrefsRepo.KEY_JUMP_FORWARD_SECONDS, 0L),
      )
    }

  /**
   * The cu-133 payload, end to end: a *well-formed* string that is not a permitted value.
   *
   * Distinct from the malformed case above — `"x"` parses fine as a string, so only a per-key
   * value allowlist can refuse it. Before that existed it was written straight to preferences and
   * crashed `AudiobookAdapter` from a property initializer on the next library render, on every
   * launch.
   */
  @Test
  fun `an out-of-range string value is skipped and reported`() =
    runTest {
      backupFile.writeText(
        """
        {"version":$BACKUP_SCHEMA_VERSION,"settings":{
          "key_library_view_style":"x",
          "key_skip_silence":"true"
        }}
        """.trimIndent(),
      )

      val result = repo.importFrom(backupFile.toUri())

      assertEquals(
        SettingsBackupRepo.ImportResult.Applied(applied = 1, skipped = 1),
        result,
      )
      assertEquals(true, prefs.getBoolean(PrefsRepo.KEY_SKIP_SILENCE, false))
      assertEquals(
        "an unknown view style must never reach preferences",
        null,
        prefs.getString(PrefsRepo.KEY_LIBRARY_VIEW_STYLE, null),
      )
    }

  /** A permitted value for the same key must still apply, or the guard is too strict. */
  @Test
  fun `an in-range string value applies normally`() =
    runTest {
      backupFile.writeText(
        """
        {"version":$BACKUP_SCHEMA_VERSION,"settings":{
          "key_library_view_style":"${PrefsRepo.VIEW_STYLE_TEXT_LIST}"
        }}
        """.trimIndent(),
      )

      val result = repo.importFrom(backupFile.toUri())

      assertEquals(
        SettingsBackupRepo.ImportResult.Applied(applied = 1, skipped = 0),
        result,
      )
      assertEquals(
        PrefsRepo.VIEW_STYLE_TEXT_LIST,
        prefs.getString(PrefsRepo.KEY_LIBRARY_VIEW_STYLE, null),
      )
    }

  @Test
  fun `an export overwriting a longer file leaves no trailing garbage`() =
    runTest {
      // "wt" truncation: without it, overwriting a longer file leaves the old tail behind
      // and the result is valid JSON followed by junk.
      backupFile.writeText("x".repeat(8000))
      prefs.edit().putBoolean(PrefsRepo.KEY_SKIP_SILENCE, true).commit()

      repo.exportTo(backupFile.toUri())

      val written = backupFile.readText()
      assertTrue("stale bytes survived the overwrite", !written.contains("xxxx"))
      // And it must still read back cleanly.
      assertTrue(repo.importFrom(backupFile.toUri()) is SettingsBackupRepo.ImportResult.Applied)
    }

  /**
   * The whole point of criterion 3: a bookmark written to a file comes back from it.
   *
   * Through the real Moshi adapter and a real file, so the JSON shape is exercised rather than
   * assumed — a field Moshi cannot serialize would pass a pure round-trip test of the mapping
   * functions and fail here.
   */
  @Test
  fun `a bookmark survives an export and import through a real file`() =
    runTest {
      bookmarks.restore(
        listOf(
          Bookmark(
            id = "bm-1",
            bookId = "1001",
            position = BookOffset(90_000L),
            note = "the riddle game",
            createdAt = 1_700_000_000_000L,
          ),
        ),
      )

      val export = repo.exportTo(backupFile.toUri())
      assertTrue("export must succeed", export is SettingsBackupRepo.ExportResult.Written)

      bookmarks.stored.clear()
      val import = repo.importFrom(backupFile.toUri())

      assertTrue(import is SettingsBackupRepo.ImportResult.Applied)
      assertEquals(1, (import as SettingsBackupRepo.ImportResult.Applied).bookmarks)
      val restored = bookmarks.stored.getValue("bm-1")
      assertEquals("1001", restored.bookId)
      assertEquals(BookOffset(90_000L), restored.position)
      assertEquals("the riddle game", restored.note)
      assertEquals(1_700_000_000_000L, restored.createdAt)
    }

  /**
   * Importing the same file twice must not duplicate. A restore keyed on anything but the id — a
   * position, say — would grow the list on every import.
   */
  @Test
  fun `importing the same file twice does not duplicate bookmarks`() =
    runTest {
      bookmarks.restore(
        listOf(Bookmark(id = "bm-1", bookId = "1001", position = BookOffset(1_000L))),
      )
      repo.exportTo(backupFile.toUri())

      repo.importFrom(backupFile.toUri())
      repo.importFrom(backupFile.toUri())

      assertEquals(1, bookmarks.stored.size)
    }

  /**
   * A restore must be **additive**. A bookmark made after the export was taken is not in the file,
   * and deleting it would be unrecoverable — the note is the user's own writing and no server
   * holds a copy.
   */
  @Test
  fun `importing does not delete bookmarks made since the export`() =
    runTest {
      bookmarks.restore(
        listOf(Bookmark(id = "bm-old", bookId = "1001", position = BookOffset(1_000L))),
      )
      repo.exportTo(backupFile.toUri())

      bookmarks.restore(
        listOf(Bookmark(id = "bm-new", bookId = "1002", position = BookOffset(2_000L))),
      )
      repo.importFrom(backupFile.toUri())

      assertEquals(setOf("bm-old", "bm-new"), bookmarks.stored.keys)
    }

  /**
   * A file whose export happened before any bookmark existed must not be read as "delete them all".
   */
  @Test
  fun `importing a file with no bookmarks leaves existing ones alone`() =
    runTest {
      repo.exportTo(backupFile.toUri())
      bookmarks.restore(
        listOf(Bookmark(id = "bm-1", bookId = "1001", position = BookOffset(1_000L))),
      )

      repo.importFrom(backupFile.toUri())

      assertEquals(setOf("bm-1"), bookmarks.stored.keys)
    }
}

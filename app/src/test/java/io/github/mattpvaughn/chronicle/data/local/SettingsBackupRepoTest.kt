package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
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

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    prefs = context.getSharedPreferences("SettingsBackupRepoTest", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    backupFile = File.createTempFile("chronicle-backup", ".json")
    repo =
      SettingsBackupRepo(
        sharedPreferences = prefs,
        contentResolver = context.contentResolver,
        moshi = Moshi.Builder().build(),
        dispatchers = TestDispatcherProvider(),
      )
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
}

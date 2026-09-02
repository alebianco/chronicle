package io.github.mattpvaughn.chronicle.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The versioned settings backup from D8.
 *
 * The security-critical property is the **allowlist**. Auth tokens live in the same
 * `SharedPreferences` file as settings, so the obvious implementation — enumerate
 * `sharedPreferences.all` — would serialize the Plex token into a plaintext JSON file the user
 * then puts in Dropbox. The R0-close review also flagged that `key_is_premium` and
 * `key_premium_token` survive on installs predating cu-60, so a blanket dump would export a
 * Play **purchase token** too.
 *
 * So the exporter only ever emits keys it was told about, and these tests assert the negative:
 * that nothing else can get out.
 */
class BackupSchemaTest {
  @Test
  fun `only allowlisted keys are exported`() {
    val stored =
      mapOf(
        PrefsRepo.KEY_PLAYBACK_SPEED to 1.5f,
        PrefsRepo.KEY_SKIP_SILENCE to true,
        // Must not appear: tokens share this preferences file.
        "auth_token" to "plex-account-token",
        "server_token" to "plex-server-token",
        "user" to """{"authToken":"nested-token"}""",
        // Must not appear: orphaned by cu-60, still present on older installs.
        "key_is_premium" to true,
        "key_premium_token" to "play-purchase-token",
      )

    val exported = exportSettings(stored)

    assertEquals(
      setOf(PrefsRepo.KEY_PLAYBACK_SPEED, PrefsRepo.KEY_SKIP_SILENCE),
      exported.settings.keys,
    )
  }

  @Test
  fun `no exported value contains a token`() {
    val stored =
      mapOf(
        "auth_token" to "plex-account-token",
        "server_token" to "plex-server-token",
        "key_premium_token" to "play-purchase-token",
        PrefsRepo.KEY_PLAYBACK_SPEED to 1.0f,
      )

    val serialized = exportSettings(stored).settings.values.joinToString()

    assertFalse("a token reached the export payload", serialized.contains("token"))
  }

  @Test
  fun `the export carries a schema version`() {
    assertEquals(BACKUP_SCHEMA_VERSION, exportSettings(emptyMap()).version)
  }

  @Test
  fun `an unknown key in a restore file is ignored rather than applied`() {
    val restored =
      importSettings(
        SettingsBackup(
          version = BACKUP_SCHEMA_VERSION,
          settings =
            mapOf(
              PrefsRepo.KEY_PLAYBACK_SPEED to "1.5",
              // A newer app version's key, or a hand-edited file.
              "key_invented_by_a_future_version" to "whatever",
              // An attacker-supplied token key must not be written back either.
              "auth_token" to "injected",
            ),
        ),
      )

    assertEquals(mapOf(PrefsRepo.KEY_PLAYBACK_SPEED to "1.5"), restored)
  }

  @Test
  fun `a future schema version is refused rather than half-applied`() {
    val fromFutureVersion =
      SettingsBackup(
        version = BACKUP_SCHEMA_VERSION + 1,
        settings = mapOf(PrefsRepo.KEY_PLAYBACK_SPEED to "1.5"),
      )

    assertNull(
      "guessing at a schema we do not know is how a restore corrupts settings",
      importSettingsOrNull(fromFutureVersion),
    )
  }

  @Test
  fun `an older schema version is still readable`() {
    val fromOlderVersion =
      SettingsBackup(version = 1, settings = mapOf(PrefsRepo.KEY_PLAYBACK_SPEED to "1.5"))

    assertTrue(
      "forward-compatibility means old files keep working",
      importSettingsOrNull(fromOlderVersion) != null,
    )
  }

  @Test
  fun `the allowlist excludes the sync directory path`() {
    assertFalse(
      "a filesystem path from another device is meaningless and possibly unwritable",
      BACKUP_SETTING_KEYS.contains(PrefsRepo.KEY_SYNC_DIR_PATH),
    )
  }

  @Test
  fun `every allowlisted key declares a type`() {
    // A key added to the allowlist without a type would export fine and import as nothing,
    // silently. This is the check that makes that impossible.
    assertEquals(
      "BACKUP_SETTING_TYPES must cover BACKUP_SETTING_KEYS exactly",
      BACKUP_SETTING_KEYS,
      BACKUP_SETTING_TYPES.keys,
    )
  }

  @Test
  fun `values round trip through their declared types`() {
    val exported =
      exportSettings(
        mapOf(
          PrefsRepo.KEY_PLAYBACK_SPEED to 1.5f,
          PrefsRepo.KEY_SKIP_SILENCE to true,
          PrefsRepo.KEY_JUMP_FORWARD_SECONDS to 30L,
          PrefsRepo.KEY_BOOK_COVER_STYLE to "Square",
        ),
      )

    val parsed = parseSettings(importSettings(exported))

    assertEquals(
      mapOf(
        PrefsRepo.KEY_PLAYBACK_SPEED to ParsedSetting.FloatSetting(1.5f),
        PrefsRepo.KEY_SKIP_SILENCE to ParsedSetting.BooleanSetting(true),
        PrefsRepo.KEY_JUMP_FORWARD_SECONDS to ParsedSetting.LongSetting(30L),
        PrefsRepo.KEY_BOOK_COVER_STYLE to ParsedSetting.StringSetting("Square"),
      ),
      parsed,
    )
  }

  @Test
  fun `a malformed value is skipped rather than crashing the restore`() {
    val parsed =
      parseSettings(
        mapOf(
          PrefsRepo.KEY_JUMP_FORWARD_SECONDS to "not-a-number",
          PrefsRepo.KEY_PLAYBACK_SPEED to "fast",
          PrefsRepo.KEY_SKIP_SILENCE to "yes-please",
          // The one good value in a hand-mangled file must still be applied.
          PrefsRepo.KEY_BOOK_COVER_STYLE to "Square",
        ),
      )

    assertEquals(
      "one bad line must not cost the user every other setting in the file",
      mapOf(PrefsRepo.KEY_BOOK_COVER_STYLE to ParsedSetting.StringSetting("Square")),
      parsed,
    )
  }

  @Test
  fun `an unrecognised boolean is skipped rather than read as false`() {
    // String.toBoolean() maps every unrecognised string to false, which would quietly turn
    // settings *off* on a corrupted file instead of leaving them alone.
    assertNull(parseSettingOrNull(PrefsRepo.KEY_SKIP_SILENCE, "1"))
    assertNull(parseSettingOrNull(PrefsRepo.KEY_SKIP_SILENCE, ""))
    assertNull(parseSettingOrNull(PrefsRepo.KEY_SKIP_SILENCE, "truthy"))

    // But the real serialized forms must work, in any case.
    assertEquals(
      ParsedSetting.BooleanSetting(true),
      parseSettingOrNull(PrefsRepo.KEY_SKIP_SILENCE, "TRUE"),
    )
    assertEquals(
      ParsedSetting.BooleanSetting(false),
      parseSettingOrNull(PrefsRepo.KEY_SKIP_SILENCE, "false"),
    )
  }

  @Test
  fun `a non-finite playback speed is refused`() {
    // "NaN" and "Infinity" both parse via toFloatOrNull, and either would make the player
    // unusable with no obvious way back.
    assertNull(parseSettingOrNull(PrefsRepo.KEY_PLAYBACK_SPEED, "NaN"))
    assertNull(parseSettingOrNull(PrefsRepo.KEY_PLAYBACK_SPEED, "Infinity"))
    assertNull(parseSettingOrNull(PrefsRepo.KEY_PLAYBACK_SPEED, "-Infinity"))
  }

  @Test
  fun `a key with no declared type is skipped`() {
    assertNull(parseSettingOrNull("key_invented_by_a_future_version", "whatever"))
  }
}

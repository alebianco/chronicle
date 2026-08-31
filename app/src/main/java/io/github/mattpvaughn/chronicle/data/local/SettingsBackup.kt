package io.github.mattpvaughn.chronicle.data.local

import com.squareup.moshi.JsonClass
import timber.log.Timber

/**
 * A versioned export of the settings that Plex cannot re-derive (D8).
 *
 * Values are stored as strings rather than a typed union: the set of settings changes over time
 * and JSON has no stable way to distinguish a `Long` from an `Int` on the way back in, so each
 * key is parsed against its known type at import. That keeps the file readable and hand-editable
 * — which matters for the file-over-app principle — at the cost of parsing on restore.
 */
@JsonClass(generateAdapter = true)
data class SettingsBackup(
  val version: Int = BACKUP_SCHEMA_VERSION,
  val settings: Map<String, String> = emptyMap(),
)

/**
 * Bumped whenever the meaning of an existing key changes.
 *
 * Adding a key does **not** require a bump: unknown keys are ignored on import, so an older app
 * reading a newer file degrades rather than failing.
 */
const val BACKUP_SCHEMA_VERSION = 1

/**
 * The settings that may be exported — an **allowlist, deliberately**.
 *
 * Auth tokens live in the same `SharedPreferences` file as these settings (both prefs repos
 * inject the single instance provided for `APP_NAME`), so enumerating `sharedPreferences.all`
 * would write the Plex account and server tokens into a plaintext JSON file the user then syncs
 * to a cloud folder. `key_is_premium` and `key_premium_token` also survive on installs
 * predating cu-60, so a blanket dump would leak a Play purchase token too.
 *
 * Anything not named here cannot leave the device by this route. Adding a setting is a
 * deliberate act, which is the point.
 *
 * `KEY_SYNC_DIR_PATH` is excluded on purpose: a filesystem path from another device is
 * meaningless and possibly unwritable, and restoring it would point downloads at nothing.
 * `KEY_LAST_REFRESH` and `KEY_APP_OPEN_COUNT` are excluded as device-local bookkeeping.
 */
val BACKUP_SETTING_KEYS: Set<String> =
  setOf(
    PrefsRepo.KEY_BOOK_COVER_STYLE,
    PrefsRepo.KEY_OFFLINE_MODE,
    PrefsRepo.KEY_REFRESH_RATE,
    PrefsRepo.KEY_JUMP_FORWARD_SECONDS,
    PrefsRepo.KEY_JUMP_BACKWARD_SECONDS,
    PrefsRepo.KEY_PLAYBACK_SPEED,
    PrefsRepo.KEY_SKIP_SILENCE,
    PrefsRepo.KEY_AUTO_REWIND_ENABLED,
    PrefsRepo.KEY_ALLOW_AUTO,
    PrefsRepo.KEY_SHAKE_TO_SNOOZE_ENABLED,
    PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST,
    PrefsRepo.KEY_BOOK_SORT_BY,
    PrefsRepo.KEY_IS_LIBRARY_SORT_DESCENDING,
    PrefsRepo.KEY_HIDE_PLAYED_AUDIOBOOKS,
    PrefsRepo.KEY_LIBRARY_MEDIA_TYPE,
    PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
  )

/**
 * Builds an export from raw preference contents, keeping only [BACKUP_SETTING_KEYS].
 *
 * Takes a plain map rather than `SharedPreferences` so the filtering is testable without
 * Android — and so the allowlist is enforced in one place that a test can point at.
 */
fun exportSettings(storedPreferences: Map<String, Any?>): SettingsBackup =
  SettingsBackup(
    version = BACKUP_SCHEMA_VERSION,
    settings =
      storedPreferences
        .filterKeys { it in BACKUP_SETTING_KEYS }
        .mapNotNull { (key, value) -> value?.let { key to it.toString() } }
        .toMap(),
  )

/**
 * The settings from [backup] that should be applied, or null if the file cannot be trusted.
 *
 * Returns null for a **newer** schema version: guessing at a format we do not know is how a
 * restore silently corrupts settings. Older versions are accepted, since adding keys does not
 * change the meaning of existing ones.
 */
fun importSettingsOrNull(backup: SettingsBackup): Map<String, String>? {
  if (backup.version > BACKUP_SCHEMA_VERSION) {
    Timber.w(
      "Refusing a backup from schema version ${backup.version}; this app understands " +
        "$BACKUP_SCHEMA_VERSION",
    )
    return null
  }
  return importSettings(backup)
}

/**
 * Filters [backup] to the allowlist, dropping anything unrecognised.
 *
 * The allowlist is applied on the way *in* as well as out: a hand-edited or malicious file must
 * not be able to write `auth_token`, and a key invented by a future version must not be applied
 * blind.
 */
fun importSettings(backup: SettingsBackup): Map<String, String> =
  backup.settings.filterKeys { key ->
    val allowed = key in BACKUP_SETTING_KEYS
    if (!allowed) {
      Timber.i("Ignoring unrecognised backup key: $key")
    }
    allowed
  }

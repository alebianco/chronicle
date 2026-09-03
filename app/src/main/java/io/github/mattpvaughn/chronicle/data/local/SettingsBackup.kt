package io.github.mattpvaughn.chronicle.data.local

import com.squareup.moshi.JsonClass
import io.github.mattpvaughn.chronicle.data.model.Audiobook
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
    PrefsRepo.KEY_AUTO_RESTART_SLEEP_TIMER,
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

/**
 * The type each allowlisted key is stored as, so a restore can parse strings back.
 *
 * Kept next to [BACKUP_SETTING_KEYS] deliberately: a key added to the allowlist without a type
 * here would import as nothing, silently. [settingTypesCoverAllowlist] fails the build instead.
 */
internal enum class SettingType { BOOLEAN, LONG, FLOAT, STRING }

internal val BACKUP_SETTING_TYPES: Map<String, SettingType> =
  mapOf(
    PrefsRepo.KEY_BOOK_COVER_STYLE to SettingType.STRING,
    PrefsRepo.KEY_OFFLINE_MODE to SettingType.BOOLEAN,
    PrefsRepo.KEY_REFRESH_RATE to SettingType.LONG,
    PrefsRepo.KEY_JUMP_FORWARD_SECONDS to SettingType.LONG,
    PrefsRepo.KEY_JUMP_BACKWARD_SECONDS to SettingType.LONG,
    PrefsRepo.KEY_PLAYBACK_SPEED to SettingType.FLOAT,
    PrefsRepo.KEY_SKIP_SILENCE to SettingType.BOOLEAN,
    PrefsRepo.KEY_AUTO_REWIND_ENABLED to SettingType.BOOLEAN,
    PrefsRepo.KEY_ALLOW_AUTO to SettingType.BOOLEAN,
    PrefsRepo.KEY_SHAKE_TO_SNOOZE_ENABLED to SettingType.BOOLEAN,
    PrefsRepo.KEY_AUTO_RESTART_SLEEP_TIMER to SettingType.BOOLEAN,
    PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST to SettingType.BOOLEAN,
    PrefsRepo.KEY_BOOK_SORT_BY to SettingType.STRING,
    PrefsRepo.KEY_IS_LIBRARY_SORT_DESCENDING to SettingType.BOOLEAN,
    PrefsRepo.KEY_HIDE_PLAYED_AUDIOBOOKS to SettingType.BOOLEAN,
    PrefsRepo.KEY_LIBRARY_MEDIA_TYPE to SettingType.STRING,
    PrefsRepo.KEY_LIBRARY_VIEW_STYLE to SettingType.STRING,
  )

/**
 * The values a constrained STRING key accepts, keyed the same way as [BACKUP_SETTING_TYPES].
 *
 * The cu-17 allowlist gates **keys**; this gates **values** (cu-133). Three of these keys have
 * setters in `SharedPreferencesPrefsRepo` that throw on an unknown value, and import bypasses
 * those setters by writing through `putString` — so an out-of-range value reached preferences and
 * then crashed the app from a *property initializer* on the next render, on every launch, with
 * the settings screen needed to undo it potentially unreachable.
 *
 * Sourced from the same constants the setters check, never a second copy: a divergence here would
 * reintroduce the bug in the opposite direction, refusing values the app itself sets.
 *
 * A STRING key absent from this map is unconstrained and imports as before. That is deliberate —
 * this is a per-key allowlist, not a blanket distrust of strings — but any key whose setter can
 * throw must appear here, which `ImportValueValidationTest` enforces.
 */
internal val BACKUP_SETTING_VALUES: Map<String, List<String>> =
  mapOf(
    PrefsRepo.KEY_LIBRARY_VIEW_STYLE to PrefsRepo.VIEW_STYLES,
    PrefsRepo.KEY_LIBRARY_MEDIA_TYPE to PrefsRepo.LIBRARY_MEDIA_TYPES,
    PrefsRepo.KEY_BOOK_SORT_BY to Audiobook.SORT_KEYS,
    PrefsRepo.KEY_BOOK_COVER_STYLE to PrefsRepo.BOOK_COVER_STYLES,
  )

/**
 * One parsed setting, ready to write.
 *
 * A sealed type rather than `Any`: the writer has to handle every case, so a new setting type
 * cannot be dropped on the floor by an `else` branch.
 */
internal sealed interface ParsedSetting {
  data class BooleanSetting(val value: Boolean) : ParsedSetting

  data class LongSetting(val value: Long) : ParsedSetting

  data class FloatSetting(val value: Float) : ParsedSetting

  data class StringSetting(val value: String) : ParsedSetting
}

/**
 * Parses one exported string against its declared type, or null if it cannot be trusted.
 *
 * Null covers three separate cases, all of which mean "skip this key and keep the current value":
 * a key with no declared type, a value that does not parse, and — for booleans — a value that is
 * neither `true` nor `false`. `String.toBoolean()` is **not** used on purpose: it maps every
 * unrecognised string to `false`, so a corrupted file would quietly turn settings off rather than
 * leaving them alone.
 */
internal fun parseSettingOrNull(
  key: String,
  raw: String,
): ParsedSetting? {
  val type = BACKUP_SETTING_TYPES[key]
  if (type == null) {
    Timber.w("No declared type for backup key $key; skipping it")
    return null
  }
  val parsed =
    when (type) {
      SettingType.BOOLEAN ->
        when (raw.lowercase()) {
          "true" -> ParsedSetting.BooleanSetting(true)
          "false" -> ParsedSetting.BooleanSetting(false)
          else -> null
        }

      SettingType.LONG -> raw.toLongOrNull()?.let { ParsedSetting.LongSetting(it) }
      SettingType.FLOAT ->
        raw.toFloatOrNull()
          ?.takeIf { it.isFinite() }
          ?.let { ParsedSetting.FloatSetting(it) }

      SettingType.STRING -> {
        // Unconstrained keys accept anything; constrained ones must name a permitted value.
        val allowed = BACKUP_SETTING_VALUES[key]
        if (allowed == null || raw in allowed) {
          ParsedSetting.StringSetting(raw)
        } else {
          null
        }
      }
    }
  if (parsed == null) {
    Timber.w("Skipping backup key $key: '$raw' is not a valid $type")
  }
  return parsed
}

/**
 * Parses a whole restore payload, dropping the entries that cannot be parsed.
 *
 * A malformed value must not abort the restore — one bad line in a hand-edited file would
 * otherwise cost the user every other setting in it.
 */
internal fun parseSettings(settings: Map<String, String>): Map<String, ParsedSetting> =
  settings.mapNotNull { (key, raw) -> parseSettingOrNull(key, raw)?.let { key to it } }.toMap()

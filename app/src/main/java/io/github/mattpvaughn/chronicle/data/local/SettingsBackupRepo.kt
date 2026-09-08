package io.github.mattpvaughn.chronicle.data.local

import android.content.ContentResolver
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mattpvaughn.chronicle.data.ChronicleJson
import io.github.mattpvaughn.chronicle.data.ChronicleJsonPretty
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okio.buffer
import okio.sink
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the D8 settings backup through a caller-supplied [Uri].
 *
 * The URI comes from the Storage Access Framework, so this class never learns *where* the file
 * lives — the user picks a Drive, Dropbox or Nextcloud folder themselves and the app cannot tell
 * the difference. That is the point of the SAF route (D12 rule 7): no cloud SDK, no provider
 * account, no permission beyond the one document the user pointed at.
 */
@Singleton
class SettingsBackupRepo
  @Inject
  constructor(
    private val settings: SettingsDataStore,
    private val contentResolver: ContentResolver,
    private val dispatchers: DispatcherProvider,
    private val bookmarkRepository: IBookmarkRepository,
  ) {
    /**
     * Serializes the allowlisted settings to [destination].
     *
     * `sharedPreferences.all` is passed through whole, deliberately: [exportSettings] owns the
     * allowlist, and pre-filtering here would move the security property out of the function whose
     * tests assert it.
     */
    suspend fun exportTo(destination: Uri): ExportResult =
      withContext(dispatchers.io) {
        try {
          val backup =
            exportSettings(settings.all()).copy(
              // Bookmarks are the user's own writing and the server holds no copy, so they are the
              // part of this file that actually cannot be re-derived (D8).
              bookmarks = bookmarkRepository.getAllAsync().map { it.toBackup() },
            )
          val json = ChronicleJsonPretty.encodeToString(backup)
          // "wt" truncates. Without it, overwriting an existing longer file leaves the old
          // tail behind and produces trailing garbage after valid JSON.
          val stream =
            contentResolver.openOutputStream(destination, "wt")
              ?: return@withContext ExportResult.Failed(
                IllegalStateException("Could not open $destination for writing"),
              )
          stream.sink().buffer().use { sink ->
            sink.writeUtf8(json)
            // A trailing newline: this is a file-over-app artifact someone may open in an
            // editor or diff, and POSIX tools expect one.
            sink.writeUtf8("\n")
          }
          ExportResult.Written(backup.settings.size)
        } catch (e: Exception) {
          Timber.e(e, "Failed to export settings to $destination")
          ExportResult.Failed(e)
        }
      }

    /**
     * Reads [source], applies the settings it is allowed to, and reports what happened.
     *
     * Every failure mode is a distinct result rather than a bare false: a refused schema version
     * and an unparseable file need different words on screen, and "applied 0 settings" is a
     * legitimate outcome that must not read as an error.
     */
    suspend fun importFrom(source: Uri): ImportResult =
      withContext(dispatchers.io) {
        val backup =
          try {
            val stream =
              contentResolver.openInputStream(source)
                ?: return@withContext ImportResult.Unreadable(
                  IllegalStateException("Could not open $source for reading"),
                )
            // Read whole rather than streamed. A settings backup is a handful of kilobytes — the
            // allowlist is seventeen keys plus the user's bookmarks — and kotlinx's streaming
            // decoder is still experimental, so this trades nothing real for a stable API.
            val json = stream.use { it.readBytes().decodeToString() }
            // The plain instance on the way in: pretty-printing is purely a *writing* choice, and
            // reading through the indented one would suggest the file's formatting mattered to the
            // parse. It does not — a hand-edited file with no indentation must restore identically.
            ChronicleJson.decodeFromString<SettingsBackup>(json)
          } catch (e: SerializationException) {
            // Every way the *content* can be wrong lands here — malformed JSON, an empty file, a
            // JSON array where an object is required. Checked by narrowing this catch and watching
            // the tests still pass, rather than assumed: the parser's `IllegalArgumentException`
            // subclasses turned out not to escape it.
            Timber.w(e, "Backup file at $source is not a settings backup")
            return@withContext ImportResult.Unreadable(e)
          } catch (e: Exception) {
            // The stream itself, not the parse: an unreadable descriptor, a revoked SAF grant. Kept
            // broad because this is a file the *user* picked, and no way of failing to read one is
            // worth crashing the settings screen over.
            Timber.e(e, "Failed to read a backup from $source")
            return@withContext ImportResult.Unreadable(e)
          }

        val allowed =
          importSettingsOrNull(backup)
            ?: return@withContext ImportResult.WrongVersion(backup.version)

        val parsed = parseSettings(allowed)
        applyParsed(parsed)

        // Additive and idempotent, keyed on the id in the file: a second import of the same file
        // overwrites the same rows, and bookmarks made since the export are left alone. Deliberately
        // *not* a replace-all — a restore that deleted newer notes would be unrecoverable.
        val restoredBookmarks = bookmarkRepository.restore(importBookmarks(backup))

        ImportResult.Applied(
          applied = parsed.size,
          skipped = allowed.size - parsed.size,
          bookmarks = restoredBookmarks,
        )
      }

    /**
     * Writes the parsed settings in a single `commit()`.
     *
     * `DataStore.edit` is a suspending transaction, which is the property `commit()` was chosen
     * for: the caller reports success to the user and the settings screen re-reads immediately
     * afterwards, so a write the reader can beat is the async-write race that cost three separate
     * bugs in the first session. `edit` returns only once the write is durable *and* updates the
     * snapshot, so the re-read cannot lose it.
     */
    private suspend fun applyParsed(parsed: Map<String, ParsedSetting>) {
      settings.edit { prefs ->
        parsed.forEach { (key, setting) ->
          when (setting) {
            is ParsedSetting.BooleanSetting -> prefs[booleanPreferencesKey(key)] = setting.value
            is ParsedSetting.LongSetting -> prefs[longPreferencesKey(key)] = setting.value
            is ParsedSetting.FloatSetting -> prefs[floatPreferencesKey(key)] = setting.value
            is ParsedSetting.StringSetting -> prefs[stringPreferencesKey(key)] = setting.value
          }
        }
      }
    }

    /** The outcome of writing a backup. */
    sealed interface ExportResult {
      data class Written(val settingCount: Int) : ExportResult

      data class Failed(val cause: Exception) : ExportResult
    }

    /** The outcome of reading a backup. */
    sealed interface ImportResult {
      /** [applied] settings were written; [skipped] were named but unusable. */
      data class Applied(
        val applied: Int,
        val skipped: Int,
        /** How many bookmarks were restored. Reported separately: a file can carry
         *  bookmarks and no settings, and "0 settings applied" must not read as a failed import. */
        val bookmarks: Int = 0,
      ) : ImportResult

      /** The file declares a schema this build does not understand. */
      data class WrongVersion(val fileVersion: Int) : ImportResult

      /** The file could not be opened or is not a settings backup at all. */
      data class Unreadable(val cause: Exception) : ImportResult
    }
  }

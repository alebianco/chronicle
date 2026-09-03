package io.github.mattpvaughn.chronicle.data.local

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
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
    private val sharedPreferences: SharedPreferences,
    private val contentResolver: ContentResolver,
    moshi: Moshi,
    private val dispatchers: DispatcherProvider,
    private val bookmarkRepository: IBookmarkRepository,
  ) {
    private val adapter = moshi.adapter(SettingsBackup::class.java).indent("  ")

    /**
     * Serializes the allowlisted settings to [destination].
     *
     * `sharedPreferences.all` is passed through whole, deliberately: [exportSettings] owns the
     * allowlist, and pre-filtering here would move the security property out of the function whose
     * tests assert it (cu-77).
     */
    suspend fun exportTo(destination: Uri): ExportResult =
      withContext(dispatchers.io) {
        try {
          val backup =
            exportSettings(sharedPreferences.all).copy(
              // Bookmarks are the user's own writing and the server holds no copy, so they are the
              // part of this file that actually cannot be re-derived (cu-22, D8).
              bookmarks = bookmarkRepository.getAllAsync().map { it.toBackup() },
            )
          val json = adapter.toJson(backup)
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
            stream.source().buffer().use { adapter.fromJson(it) }
          } catch (e: JsonDataException) {
            Timber.w(e, "Backup file at $source is not a settings backup")
            return@withContext ImportResult.Unreadable(e)
          } catch (e: Exception) {
            Timber.e(e, "Failed to read a backup from $source")
            return@withContext ImportResult.Unreadable(e)
          }

        if (backup == null) {
          Timber.w("Backup file at $source parsed to null")
          return@withContext ImportResult.Unreadable(
            IllegalStateException("Empty backup file"),
          )
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
     * `commit()` rather than `apply()`: the caller reports success to the user and the settings
     * screen re-reads the preferences immediately afterwards, and `apply()`'s write is only
     * guaranteed in memory — a deferred write that the reader beats is the async-write race that
     * cost three separate bugs in cu-73's first session.
     */
    private fun applyParsed(parsed: Map<String, ParsedSetting>) {
      val editor = sharedPreferences.edit()
      parsed.forEach { (key, setting) ->
        when (setting) {
          is ParsedSetting.BooleanSetting -> editor.putBoolean(key, setting.value)
          is ParsedSetting.LongSetting -> editor.putLong(key, setting.value)
          is ParsedSetting.FloatSetting -> editor.putFloat(key, setting.value)
          is ParsedSetting.StringSetting -> editor.putString(key, setting.value)
        }
      }
      editor.commit()
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
        /** How many bookmarks were restored (cu-22). Reported separately: a file can carry
         *  bookmarks and no settings, and "0 settings applied" must not read as a failed import. */
        val bookmarks: Int = 0,
      ) : ImportResult

      /** The file declares a schema this build does not understand. */
      data class WrongVersion(val fileVersion: Int) : ImportResult

      /** The file could not be opened or is not a settings backup at all. */
      data class Unreadable(val cause: Exception) : ImportResult
    }
  }

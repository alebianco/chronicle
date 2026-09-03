package io.github.mattpvaughn.chronicle.data.local

import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.ParsedSeriesIndexRules
import io.github.mattpvaughn.chronicle.data.model.SERIES_INDEX_RULES_FILENAME
import io.github.mattpvaughn.chronicle.data.model.parseSeriesIndexRules
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the user's own series-index parsing rules at startup, if they wrote any (cu-148).
 *
 * The file is **absent by default** and its absence is not an error: an install that never creates
 * one keeps the built-in rules and behaves exactly as cu-147 shipped. That is the reason nothing
 * here reports a failure to the user — there is no failure, only a file that is not there.
 *
 * Reads off the main thread. The app enables StrictMode's disk-read penalties in debug, and this
 * runs from `Application.onCreate`, so a synchronous read here would be a crash in a debug build
 * before it was ever a slow startup in a release one.
 */
@Singleton
class SeriesIndexRulesLoader
  @Inject
  constructor(
    private val filesDir: File,
    private val moshi: Moshi,
    private val dispatchers: DispatcherProvider,
  ) {
    /** Where a user puts their rules. Named in the KDoc of the file format so it is discoverable. */
    val rulesFile: File
      get() = File(filesDir, SERIES_INDEX_RULES_FILENAME)

    /**
     * Reads the file and installs whatever it yields.
     *
     * Installing nothing is the normal outcome and is not logged as a problem. Anything the file
     * *did* contain but could not be used has already been logged by name, by the parser or by the
     * pattern set.
     */
    suspend fun install() {
      val parsed = read()
      if (parsed.isEmpty) return

      Audiobook.installSeriesIndexPatterns(parsed.rules, parsed.order)
      Timber.i(
        "Installed ${parsed.rules.size} user series-index rule(s), " +
          "${parsed.order.name.lowercase()} the built-ins",
      )
    }

    /** The file's contents, or nothing usable. Never throws. */
    suspend fun read(): ParsedSeriesIndexRules =
      withContext(dispatchers.io) {
        val file = rulesFile
        if (!file.exists()) return@withContext ParsedSeriesIndexRules.NONE
        val json =
          try {
            file.readText()
          } catch (e: Exception) {
            // An unreadable file is the same outcome as an absent one, and equally not the user's
            // emergency — the built-ins still work.
            Timber.w(e, "Could not read $SERIES_INDEX_RULES_FILENAME")
            return@withContext ParsedSeriesIndexRules.NONE
          }
        parseSeriesIndexRules(json, moshi)
      }
  }

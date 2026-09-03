package io.github.mattpvaughn.chronicle.data.model

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import timber.log.Timber

/**
 * A user's own series-index parsing rules, as a file they can edit (cu-148, decision-18).
 *
 * JSON rather than preferences, following `SettingsBackup`: a regex is not a setting with a closed
 * set of values, and the point of the format is that it opens in a text editor (D12 rule 7). The
 * file is **absent by default** and its absence is not an error — an install that never creates one
 * behaves exactly as cu-147 shipped, with the built-in rules alone.
 *
 * Example:
 *
 * ```json
 * {
 *   "version": 1,
 *   "order": "before",
 *   "rules": [
 *     {
 *       "name": "my_shelf",
 *       "pattern": "^(?<series>.+?) - Part (?<index>\\d+)",
 *       "description": "How my own tagger writes a series"
 *     }
 *   ]
 * }
 * ```
 */
@JsonClass(generateAdapter = true)
data class SeriesIndexRulesFile(
  val version: Int = RULES_SCHEMA_VERSION,
  /**
   * Where the user's rules sit relative to the built-ins: `before`, `after` or `replace`.
   *
   * A string rather than the enum, so a hand-edited file with a typo can be *reported* rather than
   * failing to parse — Moshi would reject an unknown enum constant outright, and the whole file
   * with it, taking the valid rules down alongside the typo.
   */
  val order: String = PatternOrder.BEFORE.name.lowercase(),
  val rules: List<SeriesIndexRuleEntry> = emptyList(),
)

/** One rule as written in the file. */
@JsonClass(generateAdapter = true)
data class SeriesIndexRuleEntry(
  val name: String = "",
  val pattern: String = "",
  val description: String = "",
)

/**
 * The file format's version.
 *
 * Bumped only when the *shape* changes, not when a field is added — unknown keys are ignored, and
 * an older reader refusing a newer file is the thing this number is for.
 */
const val RULES_SCHEMA_VERSION = 1

/** The file the app looks for, in its own files directory. */
const val SERIES_INDEX_RULES_FILENAME = "series-index-rules.json"

/**
 * Reads a rules file into the pattern types, dropping anything unusable.
 *
 * **Never throws and never returns null for a bad file.** A malformed rule, an unknown order, or a
 * file from a newer version degrades to the built-ins, because the alternative — a parse failure
 * taking the *whole* index down — is exactly tvnamer's failure mode that decision-18 exists to
 * avoid. Everything dropped is logged with the name the user gave it, so a typo is findable.
 */
fun parseSeriesIndexRules(
  json: String,
  moshi: Moshi,
): ParsedSeriesIndexRules {
  val file =
    try {
      moshi.adapter(SeriesIndexRulesFile::class.java).fromJson(json)
    } catch (e: Exception) {
      Timber.w(e, "Ignoring $SERIES_INDEX_RULES_FILENAME: it is not valid JSON")
      null
    } ?: return ParsedSeriesIndexRules.NONE

  if (file.version > RULES_SCHEMA_VERSION) {
    // Refusing is safer than guessing: a newer file may mean something different by the same keys,
    // and silently misreading a user's rules is worse than ignoring them and saying so.
    Timber.w(
      "Ignoring $SERIES_INDEX_RULES_FILENAME: it is version ${file.version}, " +
        "and this build understands $RULES_SCHEMA_VERSION",
    )
    return ParsedSeriesIndexRules.NONE
  }

  val order =
    PatternOrder.entries.firstOrNull { it.name.equals(file.order, ignoreCase = true) }
      ?: PatternOrder.BEFORE.also {
        Timber.w("Unknown order '${file.order}' in $SERIES_INDEX_RULES_FILENAME; using 'before'")
      }

  val rules =
    file.rules.mapNotNull { entry ->
      when {
        entry.name.isBlank() -> {
          Timber.w("Ignoring a rule in $SERIES_INDEX_RULES_FILENAME: it has no name")
          null
        }
        entry.pattern.isBlank() -> {
          Timber.w("Ignoring rule '${entry.name}': it has no pattern")
          null
        }
        else ->
          SeriesIndexPattern(
            name = entry.name,
            source = entry.pattern,
            description = entry.description,
            isUserDefined = true,
          )
      }
    }

  // A rule that does not compile, or that captures no position, is dropped by the set itself —
  // validated at load rather than at match, which is the other half of decision-18's contract.
  return ParsedSeriesIndexRules(rules = rules, order = order)
}

/** What a rules file yielded: the usable rules and where they sit. */
data class ParsedSeriesIndexRules(
  val rules: List<SeriesIndexPattern>,
  val order: PatternOrder,
) {
  val isEmpty: Boolean get() = rules.isEmpty()

  companion object {
    /** No user rules — the built-ins alone, which is the default state. */
    val NONE = ParsedSeriesIndexRules(emptyList(), PatternOrder.BEFORE)
  }
}

package io.github.mattpvaughn.chronicle.data.model

import timber.log.Timber

// The patterns that read a series position out of a `titleSort` string (cu-147).
//
// Modelled on tvnamer (https://github.com/dbr/tvnamer), which solves the same problem for TV
// filenames: the tagging conventions in the wild are open-ended, so the expression list is *data*
// rather than compiled-in constants, and a user can add their own without a new build.
//
// Three things carried over, each for a reason cu-146 ran into:
//
// 1. Named capture groups. A pattern says what it captured (`index`, `series`) instead of relying
//    on group *number*, so a user editing one cannot silently shift the meaning of group 1 —
//    cu-146's patterns all captured position in group 1 by convention alone.
// 2. First match wins, in list order. Ordering is the whole mechanism for disambiguation:
//    `audnexus` must precede `label_first` or "Book 2 of the Saga, Book 5" reads 2.
// 3. A name per pattern. Which pattern matched is the most useful thing to know when a title
//    parses wrongly, and tvnamer's open issue #216 is exactly that complaint.
//
// Three of its failure modes are deliberately *not* copied, all confirmed in its own issue
// tracker: a user config replaces every built-in (#191, filed by its maintainer and still open),
// required groups are validated only *after* a match so a bad pattern aborts a parse a later one
// would have handled, and there is no way to see why a pattern did not match (#216). See
// `SeriesIndexPatternSetTest` for the tests that pin the alternatives.

/** A capture group a pattern may define. Anything else is ignored, so patterns can be readable. */
object SeriesIndexGroups {
  /** The position itself — the only **required** group. */
  const val INDEX = "index"

  /**
   * The series name, when the pattern happens to isolate it.
   *
   * Captured but not yet consumed: series name comes from the `Mood` tag (cu-24), which is more
   * reliable than a substring of a sort title. Declared so a pattern that names it is valid rather
   * than rejected, and so cu-143 can use it as a fallback for an untagged library.
   */
  const val SERIES = "series"
}

/**
 * One parsing rule: a name, a regex, and why it exists.
 *
 * [source] is kept alongside the compiled [regex] because a user-facing error has to quote the
 * pattern the user typed, and `Regex.toString()` is not guaranteed to round-trip what they wrote.
 */
data class SeriesIndexPattern(
  val name: String,
  val source: String,
  val description: String = "",
  val isUserDefined: Boolean = false,
) {
  /**
   * The compiled form, or null when [source] is not a valid regex.
   *
   * Lazy and nullable rather than throwing in the constructor: a bad *user* pattern must not stop
   * the app from parsing anything (see [SeriesIndexPatternSet]), and a bad built-in one is a build
   * error caught by a test rather than a crash on a user's device.
   */
  val regex: Regex? by lazy {
    try {
      Regex(source, RegexOption.IGNORE_CASE)
    } catch (e: IllegalArgumentException) {
      Timber.e(e, "Ignoring series-index pattern '$name': not a valid regular expression")
      null
    }
  }

  /** Whether this pattern is usable at all: it compiles, and it captures a position. */
  val isValid: Boolean
    get() = regex != null && source.contains("(?<${SeriesIndexGroups.INDEX}>")
}

/**
 * Where a user's own patterns sit relative to the built-ins.
 *
 * tvnamer has no such knob: a config naming `filename_patterns` **replaces** all 22 built-ins,
 * because the merge is a plain `dict.update`. Its own maintainer filed that as a bug
 * ([issue #191](https://github.com/dbr/tvnamer/issues/191)) proposing exactly these three options,
 * and six years on it is still unimplemented — so this is the fix ported rather than the flaw.
 *
 * The cost of getting it wrong is concrete: a user who saved a config froze the pattern list at
 * that version and silently stopped receiving improvements, which tvnamer's README has to warn
 * about in prose.
 */
enum class PatternOrder {
  /** User patterns win, built-ins still available as a fallback. The default, and the safe one. */
  BEFORE,

  /** Built-ins win; user patterns catch only what none of them matched. */
  AFTER,

  /**
   * Only the user's patterns. Nothing falls back.
   *
   * Kept because a user whose library uses one convention may legitimately want to exclude a
   * built-in that misreads their titles — but it is the only option that can make the index
   * *worse* than shipping no config at all, so it is never the default.
   */
  REPLACE,
}

/**
 * The ordered rules, compiled once.
 *
 * Compiled **once** and held for the process: `Audiobook.from` runs per book on every library
 * refresh, so at the cu-51 target of 1000+ books a per-call rebuild would recompile several
 * thousand expressions.
 */
class SeriesIndexPatternSet(
  patterns: List<SeriesIndexPattern>,
) {
  /**
   * Only the patterns that compile *and* capture a position.
   *
   * An unusable pattern is **dropped with a log line, not fatally** — the tvnamer failure mode
   * worth avoiding is a single bad expression in a config file breaking every parse. Dropping one
   * leaves the rest working, which is the same reasoning `asAudiobooks()` applies to an item with
   * an unsafe id (cu-111).
   */
  val usable: List<SeriesIndexPattern> =
    patterns.filter { pattern ->
      pattern.isValid.also { valid ->
        if (!valid) {
          Timber.w(
            "Ignoring series-index pattern '${pattern.name}': " +
              "it must compile and capture (?<${SeriesIndexGroups.INDEX}>...)",
          )
        }
      }
    }

  /**
   * The first pattern that yields a usable position, or null.
   *
   * First-match-wins in list order, which is why [DEFAULT_SERIES_INDEX_PATTERNS] is ordered
   * most-specific-first and user patterns are prepended.
   */
  fun match(titleSort: String): SeriesIndexMatch? {
    for (pattern in usable) {
      val regex = pattern.regex ?: continue
      val match = regex.find(titleSort) ?: continue
      val position = match.namedGroupOrNull(SeriesIndexGroups.INDEX)?.toDoubleOrNull() ?: continue
      // Out of range keeps looking with the looser patterns rather than giving up: a pattern can
      // match the wrong run of digits in a string another pattern reads correctly.
      if (position <= 0.0 || position >= MAX_SERIES_POSITION) continue
      return SeriesIndexMatch(
        patternName = pattern.name,
        position = position,
        series = match.namedGroupOrNull(SeriesIndexGroups.SERIES)?.trim().orEmpty(),
      )
    }
    return null
  }

  /**
   * Every pattern's verdict on [titleSort], in the order they were tried.
   *
   * Exists because "why did my pattern not match?" is the single hardest thing to answer about a
   * design like this — tvnamer's open [issue #216](https://github.com/dbr/tvnamer/issues/216) is
   * exactly that complaint, and it offers no trace at all, so a user's only tool is trial and
   * error. This makes the answer inspectable: which rules matched, what each captured, and which
   * one won.
   *
   * Not called in production; it backs a test and, later, a settings screen that can show a user
   * what their pattern does to a real title before they save it.
   */
  fun explain(titleSort: String): List<PatternAttempt> =
    usable.map { pattern ->
      val match = pattern.regex?.find(titleSort)
      val captured = match?.namedGroupOrNull(SeriesIndexGroups.INDEX)
      val position = captured?.toDoubleOrNull()
      PatternAttempt(
        patternName = pattern.name,
        matched = match != null,
        capturedIndex = captured,
        rejectedReason =
          when {
            match == null -> "did not match"
            captured == null -> "matched but captured no '${SeriesIndexGroups.INDEX}' group"
            position == null -> "captured '$captured', which is not a number"
            position <= 0.0 -> "captured '$captured', which is not a positive position"
            position >= MAX_SERIES_POSITION -> "captured '$captured', out of range"
            else -> null
          },
      )
    }

  companion object {
    /**
     * The exclusive upper bound on a position.
     *
     * A four-digit run is a year or part of a name, never a book number; the built-in patterns
     * already bound the digit count, so this is what protects against a *user* pattern that does
     * not.
     */
    const val MAX_SERIES_POSITION = 1000.0

    /**
     * Combines user patterns with the built-ins according to [order].
     *
     * The single place the ordering policy lives, so a caller cannot accidentally drop the
     * built-ins by concatenating in the wrong direction.
     */
    fun of(
      userPatterns: List<SeriesIndexPattern>,
      order: PatternOrder = PatternOrder.BEFORE,
    ): SeriesIndexPatternSet {
      val user = userPatterns.map { it.copy(isUserDefined = true) }
      val combined =
        when (order) {
          PatternOrder.BEFORE -> user + DEFAULT_SERIES_INDEX_PATTERNS
          PatternOrder.AFTER -> DEFAULT_SERIES_INDEX_PATTERNS + user
          PatternOrder.REPLACE -> user
        }
      return SeriesIndexPatternSet(combined)
    }
  }
}

/**
 * One pattern's verdict on a title, for [SeriesIndexPatternSet.explain].
 *
 * [rejectedReason] is null exactly when this pattern produced a usable position.
 */
data class PatternAttempt(
  val patternName: String,
  val matched: Boolean,
  val capturedIndex: String? = null,
  val rejectedReason: String? = null,
) {
  val succeeded: Boolean get() = rejectedReason == null
}

/**
 * A named group's value, or null when this pattern does not define that group.
 *
 * `MatchResult.groups["name"]` **throws** `IllegalArgumentException` for a group the *matching*
 * pattern never declared — it does not return null. Since an optional group like
 * [SeriesIndexGroups.SERIES] is by definition absent from most patterns, reading one directly
 * crashes on the majority of matches. That is precisely the fragility user-supplied patterns
 * introduce, so every named read goes through here.
 */
private fun MatchResult.namedGroupOrNull(name: String): String? =
  try {
    groups[name]?.value
  } catch (e: IllegalArgumentException) {
    null
  }

/** What a successful parse found, including which rule found it. */
data class SeriesIndexMatch(
  val patternName: String,
  val position: Double,
  val series: String = "",
) {
  /** The position in the hundredths [Audiobook.seriesIndex] is stored in (cu-146). */
  val storedIndex: Int
    get() = Math.round(position * Audiobook.SERIES_INDEX_SCALE).toInt()
}

/** One to three digits, optionally with up to two decimal places. */
private const val NUM = """\d{1,3}(?:\.\d{1,2})?"""

/** The position group, spelled once so every built-in pattern agrees on it. */
private const val INDEX = """(?<index>$NUM)"""

/**
 * The formats real taggers write, **most specific first**.
 *
 * Order is load-bearing, not cosmetic — see the class KDoc above and cu-146's notes. Each entry
 * names the convention it serves so a mis-parse can be traced to a rule.
 */
val DEFAULT_SERIES_INDEX_PATTERNS: List<SeriesIndexPattern> =
  listOf(
    SeriesIndexPattern(
      name = "audnexus",
      source = """^(?<series>.+?),\s*(?:Book|Bk\.?|Vol\.?|Volume)\s+$INDEX(?:\s*[-+]\s*\d{1,3})?\b(?:\s*-\s*|\s*$)""",
      description =
        "\"Mistborn, Book 2 - The Well of Ascension\" — what the Audnexus agent generates, so " +
          "the commonest form by construction. Accepts an omnibus (Book 1-3) or open range " +
          "(Book 4+), taking the first book: a collection containing books one to three belongs " +
          "where book one does. Must precede label_first, or \"Book 2 of the Saga, Book 5\" " +
          "reads 2.",
    ),
    SeriesIndexPattern(
      name = "label_first",
      source = """^(?:Vol\.?|Volume|Book|Bk\.?)\s*$INDEX\b(?:\s*[-.:]\s*|\s+)""",
      description = "\"Book 5: Sourcery: Discworld\" — the label opens the string.",
    ),
    SeriesIndexPattern(
      name = "label_mid",
      source = """(?:\s|^)[-–]\s*(?:Vol\.?|Volume|Book|Bk\.?)\s*$INDEX\b""",
      description = "\"1994 - Book 1 - Wizards First Rule\" — a label after a leading year.",
    ),
    SeriesIndexPattern(
      name = "hash",
      source = """[(\[]?\s*(?<series>[^()\[\]#]+?)\s*#$INDEX\s*[)\]]?""",
      description =
        "\"Stormlight Archive #4\", \"Book Title (Mistborn #2)\" — the Goodreads/Audible " +
          "display form.",
    ),
    SeriesIndexPattern(
      name = "seanap",
      source = """^(?<series>.+?)\s+$INDEX\s*-\s+""",
      description =
        "\"Expanse 1 - Leviathan Wakes\" — what the seanap Plex-Audiobook-Guide prescribes.",
    ),
    SeriesIndexPattern(
      name = "num_first",
      source = """^$INDEX\s*(?:-\s+|\.\s+)""",
      description =
        "\"01 - Book Title\", \"1. Wizards First Rule\" — an Audiobookshelf-shaped tree, where " +
          "the series name is the parent folder and never reaches the string. The separator is " +
          "required, which is what stops \"101 Dalmatians\" reading as book 101.",
    ),
    SeriesIndexPattern(
      name = "comma_trail",
      source = """^(?<series>.+?),\s*$INDEX\s*$""",
      description =
        "\"Mistborn, 2\" — a trailing bare number after a comma, with no label. Last because it " +
          "is the loosest; it must not win over a labelled form elsewhere in the string. Kept " +
          "because the pre-cu-146 parser accepted it and dropping it was a regression.",
    ),
  )

package io.github.mattpvaughn.chronicle.features.settings.compose

import io.github.mattpvaughn.chronicle.data.model.LibraryParseSummary
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt

/**
 * The series-index tester's whole state (cu-151, migrated in cu-202).
 *
 * Was five flows driving eight independent `isVisible` decisions, plus a documented workaround:
 * the parse headline had to be driven off `attempts` rather than off `winningRule`, because
 * `winningRule` is a `StateFlow` and **conflates** — testing two different unparseable titles emits
 * `null` twice, the second is dropped, and the headline never appears. It only broke on the cu-52
 * `LiveData` merge, which is precisely the kind of coupling a single state removes: [winningRule]
 * here is a field of a value that changes whenever the input does, so there is nothing to conflate.
 */
data class SeriesIndexTesterUiState(
  val titleSort: String = "",
  val attempts: List<PatternAttempt> = emptyList(),
  val summary: LibraryParseSummary? = null,
  val samples: List<String> = emptyList(),
  /** What the active rules are, and whether any of them are the user's own. */
  val ruleOrderLabel: RuleOrderLabel = RuleOrderLabel.NoUserRules,
) {
  /**
   * The rule that decided the position — the **first** that succeeded, not the only one.
   *
   * More than one rule routinely succeeds: `"Mistborn, Book 2 - …"` satisfies both `audnexus` and
   * `seanap`, and first-match-wins is the disambiguation mechanism (cu-146). Derived here so it
   * cannot disagree with [attempts].
   */
  val winningRule: PatternAttempt?
    get() = attempts.firstOrNull { it.succeeded }

  /** Whether there is any input to report a verdict about. */
  val hasInput: Boolean
    get() = titleSort.isNotBlank()

  /**
   * Whether the library read has finished.
   *
   * Distinguishes "no samples because nothing needs fixing" — a real and reassuring state — from
   * "no samples because the books have not been read yet", which must say nothing at all.
   */
  val libraryLoaded: Boolean
    get() = summary != null
}

/** Where the user's own rules sit relative to the built-ins, or that there are none. */
enum class RuleOrderLabel {
  NoUserRules,
  Before,
  After,
  Replace,
}

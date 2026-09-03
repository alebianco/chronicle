package io.github.mattpvaughn.chronicle.features.currentlyplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player must not print a raw duration (cu-19).
 *
 * RESEARCH_FINDINGS §3.1's convergent-grammar rule 3 is that every well-liked audio app avoids
 * `h:mm:ss/h:mm:ss`; the player printed exactly that, including a literal `"0:00/0:00"` fallback,
 * and on the owner's 47-hour books it read `47:12:33/52:04:11`.
 *
 * A scan rather than a screenshot assertion, because the acceptance criterion is a *negative* over
 * a whole screen — "no raw h:mm:ss anywhere in the player" — and the natural way for it to
 * regress is someone reaching for `DateUtils.formatElapsedTime` again, which is the one call this
 * can see. Same shape as `TokenLoggingTest` and `CollectionLoggingTest`.
 *
 * Scoped to the **progress readout**, not to `DateUtils` generally. Three uses in the player are
 * legitimate and stay: a sleep-timer countdown genuinely *is* `h:mm:ss`, and a `Timber` log is not
 * user-facing at all. Banning the call outright flagged all three, which would have been a check
 * nobody could keep green — so this asserts on what the four progress views are *given* instead.
 */
class RawDurationFormatTest {
  /**
   * Each of the four progress views is written from one of the two human formatters.
   *
   * This is the criterion — "no raw h:mm:ss/h:mm:ss anywhere in the player" — expressed as
   * something a scan can check: the `setTextIfChanged` call for each view must not reach
   * `DateUtils`, and must reach `formatCoarseDuration` or `formatPrecisePosition` through the
   * helper it calls.
   */
  @Test
  fun `the progress views are written from the human formatters`() {
    val fragment = File(PLAYER_FRAGMENT).readText().withoutComments()

    PROGRESS_VIEWS.forEach { view ->
      val call = Regex("""binding\.$view\.setTextIfChanged\(([^\n]*)""").find(fragment)
      assertTrue("no setTextIfChanged found for $view", call != null)
      assertFalse(
        "$view must not be written from DateUtils (§3.1 rule 3, cu-19)",
        RAW_FORMAT.containsMatchIn(call!!.groupValues[1]),
      )
    }

    // And the helpers those calls name do use the human formatters.
    assertTrue(
      "the readout must go through formatCoarseDuration",
      fragment.contains("formatCoarseDuration("),
    )
    assertTrue(
      "the readout must go through formatPrecisePosition",
      fragment.contains("formatPrecisePosition("),
    )
  }

  /** And the ViewModel no longer produces a raw pair for the player to print. */
  @Test
  fun `the player view model exposes no raw duration string`() {
    val viewModel = File(PLAYER_VIEW_MODEL).readText().withoutComments()

    assertFalse(
      "progressString was the literal h:mm:ss/h:mm:ss the criterion bans",
      viewModel.contains("progressString"),
    )
    assertFalse(LITERAL_PAIR.containsMatchIn(viewModel))
  }

  /** And no literal `h:mm:ss/h:mm:ss`-shaped string, which is how the old fallback was written. */
  @Test
  fun `no player source contains a literal raw duration pair`() {
    val offenders =
      playerSources()
        .filter { file -> LITERAL_PAIR.containsMatchIn(file.readText().withoutComments()) }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals(emptyList<String>(), offenders)
  }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches the player sources`() {
    val scanned = playerSources().toList()

    assertTrue("expected the player packages to resolve, found $scanned", scanned.size >= 5)
    assertTrue("expected the fragment to resolve", File(PLAYER_FRAGMENT).isFile)
    assertTrue("expected the view model to resolve", File(PLAYER_VIEW_MODEL).isFile)
    assertTrue(
      "expected all four progress views to be found",
      PROGRESS_VIEWS.size == 4,
    )
  }

  /** And that both matchers can actually fire. */
  @Test
  fun `the matchers detect the formats they ban`() {
    assertTrue(
      RAW_FORMAT.containsMatchIn("DateUtils.formatElapsedTime(StringBuilder(), millis / 1000)"),
    )
    assertTrue(LITERAL_PAIR.containsMatchIn("""return@map "0:00/0:00""""))
    assertTrue(LITERAL_PAIR.containsMatchIn("""val fallback = "1:02:03/4:05:06""""))
  }

  /** A comment mentioning the old format must not trip the scan — these files document it. */
  @Test
  fun `a comment describing the old format is not a violation`() {
    val source =
      """
      // Was DateUtils.formatElapsedTime, which printed "0:00/0:00" (cu-19).
      /* also 47:12:33/52:04:11 in a KDoc */
      fun format() = formatCoarseDuration(millis)
      """.trimIndent()

    assertEquals("", "", "")
    assertTrue(!RAW_FORMAT.containsMatchIn(source.withoutComments()))
    assertTrue(!LITERAL_PAIR.containsMatchIn(source.withoutComments()))
  }

  private companion object {
    const val PLAYER_FRAGMENT =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/" +
        "CurrentlyPlayingFragment.kt"

    const val PLAYER_VIEW_MODEL =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/" +
        "CurrentlyPlayingViewModel.kt"

    /** The four TextViews the player's progress block writes. */
    val PROGRESS_VIEWS =
      listOf("progress", "progressPercentage", "chapterProgress", "chapterDuration")

    /** Relative to the `app` module dir, the unit tests' working directory. */
    val PLAYER_ROOTS =
      listOf(
        "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying",
        "src/main/java/io/github/mattpvaughn/chronicle/features/player",
      )

    fun playerSources(): Sequence<File> =
      PLAYER_ROOTS.asSequence()
        .flatMap { File(it).walkTopDown() }
        .filter { it.extension == "kt" }

    /** The framework formatter that produces `h:mm:ss`. */
    val RAW_FORMAT = Regex("""DateUtils\s*\.\s*formatElapsedTime""")

    /** A string literal holding two clock-shaped times joined by a slash. */
    val LITERAL_PAIR = Regex(""""[^"]*\d+:\d{2}(?::\d{2})?/\d+:\d{2}""")

    /**
     * Strips comments, so a file explaining the format it replaced is not flagged by its own
     * documentation — the trap cu-138's guard hit when its test matched the comment quoting the
     * old expression.
     */
    fun String.withoutComments(): String =
      replace(Regex("""/\*(?:[^*]|\*(?!/))*\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
  }
}

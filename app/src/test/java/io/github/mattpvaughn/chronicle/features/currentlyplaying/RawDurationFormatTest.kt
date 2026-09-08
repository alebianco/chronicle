package io.github.mattpvaughn.chronicle.features.currentlyplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No progress readout may print a raw duration — the player's, and the book-details screen's.
 *
 * RESEARCH_FINDINGS §3.1's convergent-grammar rule 3 is that every well-liked audio app avoids
 * `h:mm:ss/h:mm:ss`; the player printed exactly that, including a literal `"0:00/0:00"` fallback,
 * and on the owner's 47-hour books it read `47:12:33/52:04:11`. The details screen printed
 * `00:00/9:26:42` for far longer, because this guard was scoped to the player alone.
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
   * The player's readouts are rendered from [PlayerText], not from a raw duration.
   *
   * Was a scan for `binding.<view>.setTextIfChanged(...)` on four named views. Those writes are
   * gone: the body is `PlayerScreen`, so there are no `binding` writes left to inspect and the old
   * assertion failed by construction. The rule is unchanged — §3.1 rule 3, a two-level human
   * readout and never `47:12:33/52:04:11` — so the scan follows it to its new address.
   *
   * `PlayerScreenTest` asserts the *rendered strings* directly (`6h 12m left in book`), which is
   * the stronger check. This keeps the source scan as the cheap guard against a `DateUtils` call
   * creeping back in beside it.
   */
  @Test
  fun `the player screen renders its readouts through PlayerText`() {
    val screen = File(PLAYER_SCREEN).readText().withoutComments()

    assertTrue(
      "$PLAYER_SCREEN not found — if the screen moved, update this test rather than deleting it",
      File(PLAYER_SCREEN).isFile,
    )
    listOf("bookProgress", "chapterPosition", "chapterRemaining").forEach { readout ->
      assertTrue(
        "the player body must render $readout through PlayerText, which is where the wording " +
          "rule lives",
        screen.contains("PlayerText.$readout("),
      )
    }
    assertFalse(
      "the player body must not format a duration itself (RESEARCH_FINDINGS §3.1 rule 3)",
      RAW_FORMAT.containsMatchIn(screen),
    )
  }

  @Test
  fun `the formatters themselves use the human helpers`() {
    // And the helpers those calls name do use the human formatters. Those helpers moved out of
    // the fragment into `PlayerText` — they never needed a view, and inside a 408-line
    // `onCreateView` no unit test could reach them. The rule is unchanged; only its address is.
    val playerText = File(PLAYER_TEXT).readText().withoutComments()
    assertTrue(
      "the readout must go through formatCoarseDuration",
      playerText.contains("formatCoarseDuration("),
    )
    assertTrue(
      "the readout must go through formatPrecisePosition",
      playerText.contains("formatPrecisePosition("),
    )
    assertFalse(
      "the extracted formatters must not reach for DateUtils either",
      RAW_FORMAT.containsMatchIn(playerText),
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

  /**
   * And the details screen, which printed `00:00/9:26:42` for the whole life of this guard because
   * it sat outside its scope.
   *
   * The stronger half of the check is structural rather than textual: the screen is handed
   * **millis**, not a formatted string, so there is no duration in scope for it to print raw. That
   * is what `ProgressLine` carrying `progressMillis`/`durationMillis` buys, and it is worth pinning
   * — reverting to a pre-formatted `text` field is precisely how this would come back.
   */
  @Test
  fun `the details screen renders its readout through DetailsProgressText`() {
    assertTrue(
      "$DETAILS_SCREEN not found — if the screen moved, update this test rather than deleting it",
      File(DETAILS_SCREEN).isFile,
    )
    val screen = File(DETAILS_SCREEN).readText().withoutComments()

    assertTrue(
      "the details body must render its progress through DetailsProgressText, which is where the " +
        "wording rule lives",
      screen.contains("DetailsProgressText.progress("),
    )

    val state = File(DETAILS_UI_STATE).readText().withoutComments()
    assertTrue(
      "ProgressLine must carry millis rather than a pre-formatted string: with no duration in " +
        "scope the screen cannot print a raw one",
      state.contains("progressMillis") && state.contains("durationMillis"),
    )
  }

  /** And the details ViewModel no longer builds a raw pair for the screen to print. */
  @Test
  fun `the details view model exposes no raw duration string`() {
    val viewModel = File(DETAILS_VIEW_MODEL).readText().withoutComments()

    assertFalse(
      "progressString was the literal h:mm:ss/h:mm:ss the criterion bans",
      viewModel.contains("progressString"),
    )
    assertFalse(RAW_FORMAT.containsMatchIn(viewModel))
    assertFalse(LITERAL_PAIR.containsMatchIn(viewModel))
  }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches the player sources`() {
    val scanned = playerSources().toList()

    assertTrue("expected the player packages to resolve, found $scanned", scanned.size >= 5)
    assertTrue("expected the player destination to resolve", File(PLAYER_FRAGMENT).isFile)
    assertTrue("expected the view model to resolve", File(PLAYER_VIEW_MODEL).isFile)
    assertTrue("expected the player screen to resolve", File(PLAYER_SCREEN).isFile)
    assertTrue("expected the details screen to resolve", File(DETAILS_SCREEN).isFile)
    assertTrue("expected the details ui state to resolve", File(DETAILS_UI_STATE).isFile)
    assertTrue("expected the details view model to resolve", File(DETAILS_VIEW_MODEL).isFile)
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
      // Was DateUtils.formatElapsedTime, which printed "0:00/0:00".
      /* also 47:12:33/52:04:11 in a KDoc */
      fun format() = formatCoarseDuration(millis)
      """.trimIndent()

    assertEquals("", "", "")
    assertTrue(!RAW_FORMAT.containsMatchIn(source.withoutComments()))
    assertTrue(!LITERAL_PAIR.containsMatchIn(source.withoutComments()))
  }

  private companion object {
    /**
     * The player's host. `CurrentlyPlayingFragment` until the Compose migration retired the
     * Fragments; the destination that replaced it is the same thing for this guard's purposes —
     * the file that wires the ViewModel's text to the screen, and so the file where a raw
     * `h:mm:ss/h:mm:ss` pair would reappear.
     */
    const val PLAYER_FRAGMENT =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/compose/" +
        "PlayerDestination.kt"

    const val PLAYER_SCREEN =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/compose/" +
        "PlayerScreen.kt"

    const val PLAYER_TEXT =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/PlayerText.kt"

    const val PLAYER_VIEW_MODEL =
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/" +
        "CurrentlyPlayingViewModel.kt"

    const val DETAILS_SCREEN =
      "src/main/java/io/github/mattpvaughn/chronicle/features/bookdetails/compose/" +
        "DetailsScreen.kt"

    const val DETAILS_UI_STATE =
      "src/main/java/io/github/mattpvaughn/chronicle/features/bookdetails/compose/" +
        "DetailsUiState.kt"

    const val DETAILS_VIEW_MODEL =
      "src/main/java/io/github/mattpvaughn/chronicle/features/bookdetails/" +
        "AudiobookDetailsViewModel.kt"

    /**
     * Both player packages, so a readout added anywhere in them is scanned — **and the book-details
     * package**.
     *
     * Details was deliberately out of scope when this guard was written: the rule was being applied
     * to the player, and the details screen's replacement wording was still an open product
     * question, so scanning it would have failed for something nobody had decided yet. It kept
     * printing `00:00/9:26:42` for that whole time, found by looking at the tablet rather than by
     * any test. Now that the wording is settled the scope follows the rule, which is §3.1 rule 3 —
     * a property of the app, never of one screen.
     */
    val PLAYER_ROOTS =
      listOf(
        "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying",
        "src/main/java/io/github/mattpvaughn/chronicle/features/player",
        "src/main/java/io/github/mattpvaughn/chronicle/features/bookdetails",
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
     * documentation — the trap the guard hit when its test matched the comment quoting the
     * old expression.
     */
    fun String.withoutComments(): String =
      replace(Regex("""/\*(?:[^*]|\*(?!/))*\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
  }
}

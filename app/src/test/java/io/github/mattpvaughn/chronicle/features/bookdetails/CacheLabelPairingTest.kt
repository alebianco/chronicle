package io.github.mattpvaughn.chronicle.features.bookdetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download control's icon and its spoken label cover the same states (cu-149).
 *
 * The bug: the icon swapped in Kotlin (`cacheIconDrawable`) while the label was a static
 * `android:contentDescription="@string/download"` in the layout, so a screen reader announced
 * "Download" for a book that was already downloaded — one control with three meanings and one
 * label. The label is now derived from `cacheStatus` immediately beside the icon.
 *
 * This is a **source guard**, not a behaviour test, because the failure mode is *divergence over
 * time*: the states are an enum, so adding a fourth to the icon's `when` and forgetting the label's
 * compiles fine and is silently wrong for anyone using TalkBack. Reading the two `when` blocks and
 * comparing their branches is the only check that catches that; a test of the current three states
 * would keep passing.
 */
class CacheLabelPairingTest {
  private val viewModelSource: String by lazy {
    val file =
      File(
        "src/main/java/io/github/mattpvaughn/chronicle/features/bookdetails/" +
          "AudiobookDetailsViewModel.kt",
      )
    assertTrue("cannot find AudiobookDetailsViewModel at ${file.absolutePath}", file.exists())
    file.readText()
  }

  /**
   * The `when` branch labels inside the named flow's initialiser, excluding `null`.
   *
   * `cacheStatus` is nullable — null means "not known yet", not a fourth cache state (cu-92) — so
   * every one of these `when`s carries a `null ->` branch that is not a state to pair on. It is
   * dropped here rather than in the assertions so all three tests agree on what a *state* is.
   *
   * Note the branch labels must stay one per line. A combined `CACHING, NOT_CACHED ->` matches
   * nothing here, which would make this guard silently see fewer states and pass while the icon
   * and the label had genuinely diverged — the failure mode it exists to catch.
   */
  private fun branchesOf(propertyName: String): Set<String> {
    val start = viewModelSource.indexOf("val $propertyName")
    assertTrue("$propertyName not found — was it renamed?", start >= 0)
    // The initialiser ends at the closing brace of the `map` lambda; the next blank line followed
    // by a non-indented-continuation is a good enough boundary for a `when` this small.
    val body = viewModelSource.substring(start, viewModelSource.indexOf("\n\n", start))
    return Regex("""^\s*(\w+)\s*->""", RegexOption.MULTILINE)
      .findAll(body)
      .map { it.groupValues[1] }
      .filterNot { it == "null" }
      .toSet()
  }

  @Test
  fun `the icon and the label branch on the same cache states`() {
    val iconStates = branchesOf("cacheIconDrawable")
    val labelStates = branchesOf("cacheContentDescription")

    assertTrue("no states parsed from cacheIconDrawable", iconStates.isNotEmpty())
    assertEquals(
      "the icon and its spoken label must cover the same states, or a screen reader " +
        "announces the wrong action for one of them",
      iconStates,
      labelStates,
    )
  }

  @Test
  fun `all three cache states are covered`() {
    assertEquals(setOf("CACHING", "NOT_CACHED", "CACHED"), branchesOf("cacheContentDescription"))
  }

  /**
   * The label says what a tap *does*, and the three differ.
   *
   * A `when` covering every state still fails the user if two branches return the same string —
   * "Download" for both NOT_CACHED and CACHED would satisfy the pairing test above.
   */
  @Test
  fun `each state announces a distinct action`() {
    val start = viewModelSource.indexOf("val cacheContentDescription")
    val body = viewModelSource.substring(start, viewModelSource.indexOf("\n\n", start))
    // Same exclusion as `branchesOf`: the `null ->` branch is "not known yet", not a state.
    val strings =
      body
        .lines()
        .filterNot { it.trimStart().startsWith("null ->") }
        .mapNotNull { Regex("""->\s*(R\.string\.\w+)""").find(it)?.groupValues?.get(1) }

    assertEquals("expected one string per state", 3, strings.size)
    assertEquals("the three states must announce three different things", 3, strings.toSet().size)
  }

  /**
   * The static-label check retires with the layout (cu-200).
   *
   * It substringed `fragment_audiobook_details.xml` from `android:id="@+id/download"` to assert
   * the control carried no `android:contentDescription` — because a static one said "Download"
   * for a book that was already downloaded, which is what a screen reader announced (cu-149).
   *
   * The download control is `DetailsScreen` now and the XML view is gone, so `indexOf` returned
   * -1 and the test threw `StringIndexOutOfBoundsException` rather than failing cleanly. Retired
   * rather than deleted: the invariant it protected is **unrepresentable** in the replacement,
   * where one sealed `DownloadState` drives the icon and the label together and there is no
   * static attribute to set. `DetailsScreenTest` asserts each state announces its own action,
   * which is the part a type cannot check.
   *
   * **The remaining tests here are now the only readers of what they check.** `cacheIconDrawable`,
   * `cacheContentDescription` and `cacheIconTint` are no longer consumed by anything: the screen
   * renders from the sealed `DownloadState`, and grepping the app finds no other caller. I first
   * wrote that Android Auto still used them — it does not. They and these tests should go
   * together in a follow-up; leaving them is deliberate for one commit only, so the migration's
   * diff stays about rendering rather than also deleting ViewModel surface.
   */
}

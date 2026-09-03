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

  /** The `when` branch labels inside the named `LiveData`'s initialiser. */
  private fun branchesOf(propertyName: String): Set<String> {
    val start = viewModelSource.indexOf("val $propertyName")
    assertTrue("$propertyName not found — was it renamed?", start >= 0)
    // The initialiser ends at the closing brace of the `map` lambda; the next blank line followed
    // by a non-indented-continuation is a good enough boundary for a `when` this small.
    val body = viewModelSource.substring(start, viewModelSource.indexOf("\n\n", start))
    return Regex("""^\s*(\w+)\s*->""", RegexOption.MULTILINE)
      .findAll(body)
      .map { it.groupValues[1] }
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
    val strings =
      Regex("""->\s*(R\.string\.\w+)""").findAll(body).map { it.groupValues[1] }.toList()

    assertEquals("expected one string per state", 3, strings.size)
    assertEquals("the three states must announce three different things", 3, strings.toSet().size)
  }

  /**
   * The layout must not reintroduce a static label.
   *
   * A `tools:contentDescription` is fine — it is preview-only and never reaches a screen reader.
   */
  @Test
  fun `the layout declares no static contentDescription on the download control`() {
    val layout =
      File("src/main/res/layout/fragment_audiobook_details.xml").readText()
    val control =
      layout.substring(
        layout.indexOf("""android:id="@+id/download""""),
        layout.indexOf("/>", layout.indexOf("""android:id="@+id/download"""")),
      )

    assertTrue(
      "the download control must not carry android:contentDescription — it has three meanings " +
        "and the fragment sets the right one; found: $control",
      !control.contains("android:contentDescription"),
    )
  }
}

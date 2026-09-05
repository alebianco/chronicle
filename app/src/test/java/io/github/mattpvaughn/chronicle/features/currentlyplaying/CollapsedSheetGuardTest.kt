package io.github.mattpvaughn.chronicle.features.currentlyplaying

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guard that decides whether the expanded player is on screen must test the sheet's **height**,
 * never `isShown` alone (cu-141).
 *
 * The player lives in a bottom sheet whose container collapses to **zero height** rather than
 * going GONE. Every child therefore keeps `VISIBLE` with real bounds inside a container with no
 * room, and `isShown` — which walks only the visibility *flags* up the ancestor chain — stays
 * `true` throughout. Measured on the tablet while fully collapsed:
 *
 * ```
 * container = 0,990-1920,990   (zero height)
 * seekbar   = 48,108-1872,180  isShown=true  width=1824
 * root height = 0
 * ```
 *
 * Two failures followed from that, and both were intermittent, which is what made this expensive
 * to find:
 *
 * 1. `renderPlayerText` passed its guard while collapsed and wrote text into a hierarchy with no
 *    room, so `wrap_content` readouts below the collapsed region measured to **zero width**. The
 *    book-progress line was blank in landscape depending only on which tick happened to land
 *    after an expand — six attempts at the *constraints* found nothing, because the constraints
 *    were never wrong.
 * 2. The re-render listener keyed on an `isShown` transition that never fired: `wasShown` went
 *    true while still collapsed, so the `shown && !wasShown` edge was missed on every expand.
 *
 * A source guard rather than a rendering test, because a Robolectric view is laid out at its
 * measured size with no `BottomSheetBehavior` driving it — the collapsed state this protects
 * against is not reachable from a unit test at all. The check is deliberately narrow: it asserts
 * only that the two guards in this file, and the listener that re-renders on expand, mention a
 * height. It does not try to parse the condition.
 */
class CollapsedSheetGuardTest {
  private val fragment =
    File(
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/" +
        "CurrentlyPlayingFragment.kt",
    )

  @Test
  fun `fragment source is present`() {
    assertTrue(
      "CurrentlyPlayingFragment.kt not found at ${fragment.absolutePath} — if it moved, " +
        "update this test rather than deleting it.",
      fragment.isFile,
    )
  }

  @Test
  fun `every isShown guard is paired with a height check`() {
    val lines = fragment.readLines()
    val offenders = mutableListOf<String>()

    lines.forEachIndexed { index, line ->
      if (!line.contains(".isShown")) return@forEachIndexed
      // The guard may wrap, so look at the statement rather than the single line.
      val statement = lines.subList(index, minOf(index + 3, lines.size)).joinToString(" ")
      if (!statement.contains("height")) {
        offenders += "line ${index + 1}: ${line.trim()}"
      }
    }

    assertTrue(
      "An `isShown` check in CurrentlyPlayingFragment is not paired with a height check.\n" +
        "A collapsed bottom sheet keeps every child VISIBLE at zero height, so `isShown` alone " +
        "reports the expanded player as on screen when it is not (cu-141).\n" +
        offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }

  @Test
  fun `the expand listener watches height rather than an isShown transition`() {
    val source = fragment.readText()

    assertTrue(
      "The layout-change listener that re-renders the player on expand must key on the root's " +
        "height. An `isShown` transition never fires, because the sheet collapses to zero " +
        "height with its children still VISIBLE — so the stale text written while collapsed is " +
        "never corrected (cu-141).",
      source.contains("addOnLayoutChangeListener") && source.contains("view.height > 0"),
    )
  }
}

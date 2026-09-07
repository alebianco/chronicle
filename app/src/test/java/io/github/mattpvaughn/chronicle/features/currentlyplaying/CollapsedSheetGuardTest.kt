package io.github.mattpvaughn.chronicle.features.currentlyplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A collapsed player does no rendering work — now enforced by structure, not by a guard.
 *
 * ## What this used to be, and why it changed
 *
 * The player lives in a bottom sheet that collapses to **zero height with every child still
 * `VISIBLE`**, so `isShown` stays true while nothing is on screen. Two bugs came out of that:
 *
 * 1. `renderPlayerText` passed its guard while collapsed and wrote text into a hierarchy with no
 *    room, so `wrap_content` readouts measured to zero width. The book-progress line was blank in
 *    landscape depending only on which tick landed after an expand — six attempts at the
 *    *constraints* found nothing, because the constraints were never wrong.
 * 2. The re-render listener keyed on an `isShown` transition that never fired, so the stale text
 *    written while collapsed was never corrected.
 *
 * This was a **source scan** — it asserted that every `isShown` in the fragment sat within three
 * lines of a `height` check — because a Robolectric view is laid out at its measured size with no
 * `BottomSheetBehavior` driving it, so the collapsed state was unreachable from a unit test.
 *
 * The Compose migration removed the mechanism rather than re-guarding it. The body is
 * `PlayerScreen`, composed only when the sheet reports `EXPANDED`. That migration moved *where*
 * that gate lives: the fragment and its `CurrentlyPlayingInterface` are gone, and `ChronicleApp`
 * composes the player under a plain `if (sheetState == EXPANDED)`. So "is the player on screen?"
 * is a fact the shell already knows instead of something inferred from view geometry, and the
 * failure mode is **unrepresentable**: there is no write site left at which a guard could be
 * forgotten.
 *
 * The scan is kept, inverted: it now asserts the geometry inference has not come back. That is
 * cheap and it is the only thing still worth pinning here — the positive behaviour (a collapsed
 * sheet renders nothing) is pinned by `PlayerScreenTest` against the state itself.
 *
 * **The `if` in `ChronicleApp` is load-bearing and this test is why it is not an
 * `AnimatedVisibility`.** That composable keeps its content composed while hidden, so the player
 * would recompose once a second behind a collapsed sheet — the exact cost an earlier profiling
 * pass measured. The first Compose migration draft used one, and repointing this guard is what
 * caught it.
 */
class CollapsedSheetGuardTest {
  private val player =
    File(
      "src/main/java/io/github/mattpvaughn/chronicle/features/currentlyplaying/compose/" +
        "PlayerDestination.kt",
    )

  private val shell =
    File("src/main/java/io/github/mattpvaughn/chronicle/application/compose/ChronicleApp.kt")

  @Test
  fun `player and shell sources are present`() {
    assertTrue(
      "PlayerDestination.kt not found at ${player.absolutePath} — if it moved, " +
        "update this test rather than deleting it.",
      player.isFile,
    )
    assertTrue(
      "ChronicleApp.kt not found at ${shell.absolutePath} — if it moved, " +
        "update this test rather than deleting it.",
      shell.isFile,
    )
  }

  /**
   * No `isShown` probe returns to this screen.
   *
   * `isShown` cannot answer the question this screen asks. It reports the visibility *flags* up
   * the ancestor chain, and a collapsed sheet's children are all `VISIBLE` — so it reads true
   * while nothing is on screen. Any reappearance means someone has started inferring
   * on-screen-ness from the view tree again.
   */
  @Test
  fun `the player does not infer visibility from view geometry`() {
    // Code only. A comment naming the old mechanism is how the reasoning survives — banning the
    // words would mean the next person cannot be told why the guard went away. (This is not
    // hypothetical: the first run of this test flagged its own explanatory comment.)
    val source =
      player.readLines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    assertFalse(
      "`isShown` reads true for a collapsed sheet, because it only checks visibility flags and " +
        "the sheet collapses to zero height with its children VISIBLE. Gate composition on the " +
        "sheet's own state instead — that is what replaced this guard.",
      source.contains(".isShown"),
    )
    assertFalse(
      "`root.height == 0` is the other half of the same inference. The sheet's state is " +
        "available directly; do not re-derive it from measurement.",
      Regex("""\broot\.height\b""").containsMatchIn(source),
    )
  }

  /** The replacement is actually in place, so this file cannot pass by the screen being gutted. */
  @Test
  fun `composition is gated on the sheet's reported state`() {
    val source = shell.readText()

    assertTrue(
      "the player body must be composed only while the sheet is EXPANDED — otherwise this " +
        "file's assertions above pass vacuously against a shell that guards nothing at all.",
      source.contains("if (sheetState == EXPANDED)"),
    )
  }

  /**
   * The gate is a plain `if`, never an `AnimatedVisibility`.
   *
   * `AnimatedVisibility` composes its content while hidden, so wrapping the player in one would
   * put every per-tick recomposition back behind a collapsed sheet while this file's other
   * assertions still passed. The collapsed *handle* may animate — it is cheap and always composed
   * — so this checks only the expanded branch.
   */
  @Test
  fun `the expanded player is not wrapped in AnimatedVisibility`() {
    val expandedBranch = shell.readText().substringAfter("if (sheetState == EXPANDED)", "")

    assertFalse(
      "the expanded player must not sit inside an AnimatedVisibility: it keeps content composed " +
        "while hidden, which is the per-second work an earlier profiling pass measured and removed.",
      expandedBranch.contains("AnimatedVisibility"),
    )
  }
}

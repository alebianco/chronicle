package io.github.mattpvaughn.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How many files under `features/` reach into `data.sources.plex` directly — the standing measure of
 * the coupling debt that the multi-backend seam exists to remove.
 *
 * **A ratchet, not a pin.** The list may shrink freely; adding to it fails. That is the same shape
 * as the coverage ratchet and `FrameworkFreeCoreTest`, and for the same reason: the number is
 * genuinely useful for sizing the backend carve, and a number nobody enforces is worse than no
 * number at all, because it gets quoted in planning as though it were current.
 *
 * It had already drifted twice before this guard existed. It was documented as **27**, was really
 * **29** when the drift was first noticed, and is **25** now — the direction changed as well as the
 * value, so neither "it only grows" nor "the docs are roughly right" was safe to assume.
 *
 * **The baseline is a committed file, not a computed set.** Deriving it at test time would let a
 * newly-coupled file slip in silently, which is the failure mode that makes a guard worthless. A
 * file leaving the list is progress and needs no ceremony; a file joining it should be a visible
 * line in a diff, argued for in review.
 */
class PlexCouplingRatchetTest {
  private val featuresRoot =
    File("src/main/java/io/github/mattpvaughn/chronicle/features")

  private val baselineFile = File("../plex-coupling-baseline.txt")

  private fun currentlyCoupled(): Set<String> =
    featuresRoot
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filter { file -> file.readLines().any { it.contains(PLEX_PACKAGE) } }
      .map { it.relativeTo(featuresRoot).path }
      .toSortedSet()

  /** Guards the guard: a wrong root would scan nothing and pass. */
  @Test
  fun `the scan reaches the features sources`() {
    val scanned = featuresRoot.walkTopDown().count { it.isFile && it.extension == "kt" }

    assertTrue("expected to scan the features sources, saw $scanned", scanned > 50)
  }

  /** And that the baseline was actually read, rather than silently defaulting to empty. */
  @Test
  fun `the baseline file is present and populated`() {
    assertTrue("missing baseline at ${baselineFile.absolutePath}", baselineFile.isFile)
    assertTrue("baseline is empty", baselineFile.readLines().any { it.isNotBlank() })
  }

  @Test
  fun `no features file newly reaches into the plex package`() {
    val baseline = baselineFile.readLines().filter { it.isNotBlank() }.toSortedSet()
    val added = currentlyCoupled() - baseline

    assertEquals(
      "These files under `features/` now import `$PLEX_PACKAGE` and are not in the baseline.\n" +
        "The multi-backend seam exists to remove this coupling, so it may shrink freely but must " +
        "not grow. Depend on `MediaSource` / `SourceManager` instead — or, if the coupling is " +
        "genuinely justified, add the file to `plex-coupling-baseline.txt` in the same commit so " +
        "the decision is visible in the diff:\n",
      emptySet<String>(),
      added,
    )
  }

  /**
   * Progress is recorded rather than merely permitted: a file that has left the list should leave
   * the baseline too, or the ratchet slowly loses its grip as stale entries accumulate.
   */
  @Test
  fun `the baseline has no entries that are no longer coupled`() {
    val baseline = baselineFile.readLines().filter { it.isNotBlank() }.toSortedSet()
    val stale = baseline - currentlyCoupled()

    assertEquals(
      "These files are in `plex-coupling-baseline.txt` but no longer import `$PLEX_PACKAGE`. " +
        "Good — remove them from the baseline so the ratchet tightens:\n",
      emptySet<String>(),
      stale,
    )
  }

  private companion object {
    const val PLEX_PACKAGE = "io.github.mattpvaughn.chronicle.data.sources.plex"
  }
}

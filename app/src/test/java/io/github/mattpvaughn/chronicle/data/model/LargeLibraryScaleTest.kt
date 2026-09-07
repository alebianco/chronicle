package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * The in-memory library scans stay usable at 1000+ books.
 *
 * the grouped search and the facet grouping both read the **whole library** and scan it —
 * the search per keystroke (debounced 250 ms), the facets per screen open. Both were designed
 * against this task's 1000+ book target and are cheap by construction, but neither had been
 * *measured* at that scale: the fixture pack has three books and the owner's library has 196.
 *
 * **This asserts complexity, not milliseconds.** A wall-clock budget on a shared CI machine is a
 * flaky test, and the number it would pin says more about the runner than about the code. What
 * matters is that the work stays roughly linear in library size — the task's own wording is
 * "repository gets scale better than n^2", and a quadratic scan is the failure that would make a
 * 10,000-book library unusable while 196 books looked fine. So each case measures at two sizes and
 * asserts the ratio, which is stable across machines in a way a duration is not.
 *
 * The measured numbers are recorded in the task file rather than asserted here.
 */
class LargeLibraryScaleTest {
  /**
   * A library with realistic *shape*, not just size.
   *
   * Every field the scans read is populated and varied: search walks title, author, narrator and
   * series, and facet grouping keys on author, narrator and series. A library of identical books
   * would collapse every group to one and measure nothing — and one with entirely distinct values
   * would miss the grouping cost. Roughly 1 series per 8 books and 1 narrator per 20 matches the
   * owner's own library.
   */
  private fun library(size: Int): List<Audiobook> =
    (1..size).map { i ->
      Audiobook(
        id = i.toString(),
        source = TEST_SOURCE,
        title = "Book $i of the Long Series",
        titleSort = "Long Series ${i / 8}, Book ${i % 8 + 1} - Book $i",
        author = "Author ${i % 120}",
        narrator = "Narrator ${i % 20}",
        series = "Long Series ${i / 8}",
        duration = 3_600_000L + i,
        progress = (i % 100) * 1000L,
      )
    }

  /** Runs [block] once to warm the JIT, then takes the best of five. */
  private fun bestOfFive(block: () -> Unit): Long {
    block()
    return (1..5).minOf { measureNanoTime(block) }
  }

  /**
   * The growth factor between [smaller] and [larger], where 1.0 would be free and 2.0 is linear
   * for a doubling.
   *
   * Compared against a generous ceiling: the point is to catch a *quadratic* scan (which would
   * show 4x for a doubling), not to police constant factors or JIT noise.
   */
  private fun growthFactor(
    smaller: Int,
    larger: Int,
    work: (List<Audiobook>) -> Unit,
  ): Double {
    val small = library(smaller)
    val big = library(larger)
    val smallTime = bestOfFive { work(small) }
    val bigTime = bestOfFive { work(big) }
    return bigTime.toDouble() / smallTime.toDouble()
  }

  /**
   * Fuzzy search over 1000 -> 5000 books stays roughly linear.
   *
   * A 5x library must not cost 25x. `BookSearch` prefilters on length and character counts before
   * any edit distance, so the expensive Damerau-Levenshtein runs on a small fraction of the
   * library — that prefilter is what this pins.
   */
  @Test
  fun `fuzzy search scales roughly linearly with library size`() {
    val factor = growthFactor(1_000, 5_000) { it.searchFuzzy("Long Series") }

    assertTrue(
      "a 5x library made search $factor x slower; a quadratic scan would be ~25x and would make " +
        "a large library unusable per keystroke",
      factor < QUADRATIC_ALARM_5X,
    )
  }

  /** A typo-bearing query, which is the case that reaches the edit-distance path at all. */
  @Test
  fun `fuzzy search with a typo scales roughly linearly`() {
    val factor = growthFactor(1_000, 5_000) { it.searchFuzzy("Lnog Seires") }

    assertTrue(
      "a 5x library made a typo search $factor x slower; the prefilter is what keeps the edit " +
        "distance off most of the library",
      factor < QUADRATIC_ALARM_5X,
    )
  }

  /** Facet grouping over 1000 -> 5000 books stays roughly linear, for each of the three kinds. */
  @Test
  fun `facet grouping scales roughly linearly with library size`() {
    FacetKind.entries.forEach { kind ->
      val factor = growthFactor(1_000, 5_000) { it.facetsBy(kind) }

      assertTrue(
        "a 5x library made $kind grouping $factor x slower",
        factor < QUADRATIC_ALARM_5X,
      )
    }
  }

  /** The series-index parse runs per book on every refresh, so it must not be superlinear either. */
  @Test
  fun `series index parsing scales linearly with library size`() {
    val factor =
      growthFactor(1_000, 5_000) { books ->
        books.forEach { Audiobook.seriesIndexFromTitleSort(it.titleSort) }
      }

    assertTrue(
      "a 5x library made series-index parsing $factor x slower; the rules are compiled once, so " +
        "this should be flat per book",
      factor < QUADRATIC_ALARM_5X,
    )
  }

  /** At the task's stated target, one pass over the whole library still completes promptly. */
  @Test
  fun `the target library size completes a search and a grouping without pathological cost`() {
    val books = library(10_000)

    val searchNanos = bestOfFive { books.searchFuzzy("Long Series 40") }
    val groupNanos = bestOfFive { books.facetsBy(FacetKind.Series) }

    // A very loose ceiling — an order of magnitude above anything measured — so this fails on a
    // genuine regression rather than on a slow machine. The real numbers are in the task file.
    assertTrue(
      "a search over 10,000 books took ${searchNanos / 1_000_000}ms",
      searchNanos < PATHOLOGICAL_NANOS,
    )
    assertTrue(
      "grouping 10,000 books took ${groupNanos / 1_000_000}ms",
      groupNanos < PATHOLOGICAL_NANOS,
    )
  }

  private companion object {
    /**
     * The ceiling for a 5x library growth.
     *
     * Linear is 5.0. Quadratic would be ~25.0. The gap between them is wide, so 12.0 catches a
     * genuine complexity regression while tolerating the constant-factor and cache effects that
     * make a real 5x measurement land anywhere from 4x to 8x.
     */
    const val QUADRATIC_ALARM_5X = 12.0

    /** Two seconds. Nothing measured here comes close; this only catches a runaway. */
    const val PATHOLOGICAL_NANOS = 2_000_000_000L
  }
}

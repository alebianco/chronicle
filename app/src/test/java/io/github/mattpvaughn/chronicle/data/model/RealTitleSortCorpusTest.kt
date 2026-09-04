package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the built-in series-index patterns over `titleSort` values captured from a **real** Plex
 * server (196 audiobooks, Audnexus-agent tagged), rather than over fixtures written to match the
 * code.
 *
 * cu-24 is the reason this exists: `plexGenres` carried the wrong `@Json` name for the life of the
 * project while every hand-written fixture agreed with the code. cu-146 then repeated the shape in
 * a new field — the parser was end-anchored and read exactly one of eight real formats, the one
 * being our own fixture. A corpus the code did not get to choose is the only guard against that.
 *
 * The corpus is committed as a test resource so the check runs headless with no server.
 */
class RealTitleSortCorpusTest {
  private fun corpus(): List<String> =
    checkNotNull(javaClass.classLoader?.getResourceAsStream("real-titlesorts.txt")) {
      "real-titlesorts.txt missing from test resources"
    }.bufferedReader().readLines().filter { it.isNotBlank() }

  @Test
  fun `corpus is present and the expected size`() {
    assertEquals(139, corpus().size)
  }

  @Test
  fun `the built-in patterns read a series index from almost every real titleSort`() {
    val values = corpus()
    val parsed = values.filter { Audiobook.seriesIndexFromTitleSort(it) != Audiobook.NO_SERIES_INDEX }

    // 138/139 on the captured library, up from 136 when cu-155 added `audnexus_subseries`. Pinned
    // as a floor rather than an equality so that adding a pattern is an improvement rather than a
    // failure; a regression below it is a real one.
    //
    // The one that does not parse is correct: "Hell Divers Series 0 - ..." is `Book 0`, deliberately
    // unknown, since 0 is the NO_SERIES_INDEX sentinel and a prequel numbered zero should sort last
    // (cu-146).
    assertTrue(
      "only ${parsed.size}/${values.size} real titleSort values parsed; the measured floor is 138",
      parsed.size >= 138,
    )
  }

  @Test
  fun `the audnexus format is the dominant real-world shape`() {
    // 111 of 139 -- this is why `audnexus` is tried before `label-first` (cu-146).
    val audnexusShaped = corpus().count { Regex(""".*,\s*Book\s+\d+(\.\d+)?\s*-\s*.+""").matches(it) }
    assertTrue("expected the audnexus shape to dominate, got $audnexusShaped", audnexusShaped >= 100)
  }

  @Test
  fun `a real audnexus titleSort parses to the right book number`() {
    assertEquals(
      3 * Audiobook.SERIES_INDEX_SCALE,
      Audiobook.seriesIndexFromTitleSort("The Age of Madness, Book 3 - The Wisdom of Crowds"),
    )
  }
}

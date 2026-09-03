package io.github.mattpvaughn.chronicle.data.local

import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.PatternOrder
import io.github.mattpvaughn.chronicle.data.model.SERIES_INDEX_RULES_FILENAME
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Loading a user's rules file from disk (cu-148).
 *
 * Against a **real file**, because the states that matter are file states: absent (the default and
 * by far the commonest), unreadable, and present-but-wrong. A mocked reader would answer whatever
 * it was told and prove none of them.
 *
 * Every test restores the built-in rules afterwards — the installed set is process state, so a test
 * that installed rules and did not clean up would change the answers of every test after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesIndexRulesLoaderTest {
  @get:Rule
  val folder = TemporaryFolder()

  /** Codegen adapters, matching what the app ships (cu-62) rather than the reflective factory. */
  private val moshi = Moshi.Builder().build()

  @After
  fun tearDown() {
    Audiobook.resetSeriesIndexPatterns()
  }

  private fun loader() = SeriesIndexRulesLoader(folder.root, moshi, TestDispatcherProvider())

  private fun writeRules(json: String) {
    folder.newFile(SERIES_INDEX_RULES_FILENAME).writeText(json)
  }

  /** The default state, and the one that must cost nothing. */
  @Test
  fun `an absent file installs nothing and is not an error`() =
    runTest {
      val parsed = loader().read()

      assertTrue(parsed.isEmpty)
    }

  @Test
  fun `an absent file leaves the built-in rules in place`() =
    runTest {
      loader().install()

      // A built-in form still parses, which it would not under a REPLACE of nothing.
      assertEquals(200, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"))
    }

  @Test
  fun `a valid file is read`() =
    runTest {
      writeRules("""{"version":1,"order":"after","rules":[{"name":"mine","pattern":"^Part (?<index>\\d+)"}]}""")

      val parsed = loader().read()

      assertEquals(listOf("mine"), parsed.rules.map { it.name })
      assertEquals(PatternOrder.AFTER, parsed.order)
    }

  /** The headline: a rule from the file parses a title the built-ins cannot. */
  @Test
  fun `installing a user rule changes what the parser accepts`() =
    runTest {
      val exotic = "Wheel of Time :: Volume 7 :: A Crown of Swords"
      assertEquals(
        "the built-ins should not already handle this, or the test proves nothing",
        Audiobook.NO_SERIES_INDEX,
        Audiobook.seriesIndexFromTitleSort(exotic),
      )
      writeRules("""{"rules":[{"name":"colons","pattern":":: Volume (?<index>\\d+) ::"}]}""")

      loader().install()

      assertEquals(700, Audiobook.seriesIndexFromTitleSort(exotic))
    }

  @Test
  fun `installing a user rule keeps the built-ins working`() =
    runTest {
      writeRules("""{"rules":[{"name":"colons","pattern":":: Volume (?<index>\\d+) ::"}]}""")

      loader().install()

      assertEquals(200, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"))
    }

  /**
   * A file the user broke costs them their rules and nothing else.
   *
   * The app keeps parsing exactly as it did before the file existed — which is the whole
   * degradation contract, and the thing tvnamer's config does not do.
   */
  @Test
  fun `a malformed file leaves the built-ins working`() =
    runTest {
      writeRules("""{"rules": [ {"name": "broken" """)

      loader().install()

      assertEquals(200, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"))
    }

  @Test
  fun `a file whose only rule is unusable leaves the built-ins working`() =
    runTest {
      writeRules("""{"rules":[{"name":"broken","pattern":"(?<index>["}]}""")

      loader().install()

      assertEquals(200, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"))
    }

  /** REPLACE is the one setting that can make things worse, so it must actually be honoured. */
  @Test
  fun `replace drops the built-ins as asked`() =
    runTest {
      writeRules("""{"order":"replace","rules":[{"name":"only","pattern":"^Part (?<index>\\d+)"}]}""")

      loader().install()

      assertEquals(300, Audiobook.seriesIndexFromTitleSort("Part 3"))
      assertEquals(
        "a built-in form must no longer parse under replace",
        Audiobook.NO_SERIES_INDEX,
        Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"),
      )
    }

  @Test
  fun `the loader names the file it looks for`() {
    assertEquals(SERIES_INDEX_RULES_FILENAME, loader().rulesFile.name)
  }
}

package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pattern list *as a list* — ordering, validation, and override (cu-147).
 *
 * These are the tests tvnamer does not have. Its 63 fixtures all check *filenames* against the
 * built-in list; `grep filename_patterns tests/` finds nothing, so nothing asserts the ordering,
 * the invalid-pattern skip, or the required-group rejection. Those are precisely the mechanisms a
 * user's own patterns exercise, and precisely where its open issues sit (#191, #216).
 */
class SeriesIndexPatternSetTest {
  private fun userPattern(
    name: String,
    source: String,
  ) = SeriesIndexPattern(name = name, source = source, isUserDefined = true)

  // ---- the built-in list is internally sound ----

  @Test
  fun `every built-in pattern compiles and captures a position`() {
    val unusable = DEFAULT_SERIES_INDEX_PATTERNS.filterNot { it.isValid }

    assertEquals("these built-in patterns are unusable: ${unusable.map { it.name }}", emptyList<String>(), unusable.map { it.name })
  }

  @Test
  fun `built-in pattern names are unique, so a diagnostic can identify one`() {
    val names = DEFAULT_SERIES_INDEX_PATTERNS.map { it.name }

    assertEquals(names.distinct(), names)
  }

  @Test
  fun `every built-in pattern explains itself`() {
    val undocumented = DEFAULT_SERIES_INDEX_PATTERNS.filter { it.description.isBlank() }

    assertEquals(emptyList<String>(), undocumented.map { it.name })
  }

  /**
   * The ordering that disambiguation depends on.
   *
   * `audnexus` must be tried before `label_first`, or `"Book 2 of the Saga, Book 5"` reads 2 — the
   * case cu-146's end-anchored parser existed to protect. tvnamer's default list has the same kind
   * of dependency and documents it nowhere; this test is the documentation.
   */
  @Test
  fun `audnexus is ordered before label_first`() {
    val names = DEFAULT_SERIES_INDEX_PATTERNS.map { it.name }

    assertTrue(
      "audnexus must precede label_first, or a series name containing a number wins",
      names.indexOf("audnexus") < names.indexOf("label_first"),
    )
  }

  @Test
  fun `comma_trail is last, being the loosest`() {
    assertEquals("comma_trail", DEFAULT_SERIES_INDEX_PATTERNS.last().name)
  }

  @Test
  fun `the ordering dependency is real, not incidental`() {
    // Reversed, the looser rule claims the leading number instead of the trailing one.
    val reversed =
      SeriesIndexPatternSet(
        DEFAULT_SERIES_INDEX_PATTERNS.sortedBy { if (it.name == "label_first") 0 else 1 },
      )

    assertEquals(500, SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS).match("Book 2 of the Saga, Book 5")?.storedIndex)
    assertEquals(200, reversed.match("Book 2 of the Saga, Book 5")?.storedIndex)
  }

  // ---- validation happens before use, not during ----

  /**
   * An invalid regex is dropped, not fatal.
   *
   * tvnamer skips-with-a-warning too, and that part is right: one bad expression in a config must
   * not stop every other pattern from working.
   */
  @Test
  fun `an uncompilable pattern is dropped and the rest still work`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("broken", "(?<index>[")))

    assertFalse(set.usable.any { it.name == "broken" })
    assertEquals(200, set.match("Mistborn, Book 2")?.storedIndex)
  }

  /**
   * A pattern with no position group is rejected at **load** time.
   *
   * tvnamer validates groups only *after* a match, so a pattern missing a required group sits in
   * the compiled list looking healthy and then aborts the parse of a file a later pattern would
   * have handled. Rejecting it up front is strictly better.
   */
  @Test
  fun `a pattern that captures no index is rejected before it can match`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("groupless", """^(?<series>.+?) \d+""")))

    assertFalse(set.usable.any { it.name == "groupless" })
  }

  @Test
  fun `a valid user pattern is kept`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("mine", """^bk(?<index>\d+)""")))

    assertTrue(set.usable.any { it.name == "mine" })
  }

  // ---- override semantics: the tvnamer #191 fix ----

  /**
   * A user pattern must not silently discard the built-ins.
   *
   * This is tvnamer's headline flaw: naming `filename_patterns` in a config replaces all 22
   * defaults, because the merge is a plain `dict.update`. A user who wanted to add one pattern
   * loses every other, and stops receiving improvements.
   */
  @Test
  fun `adding a user pattern keeps the built-ins working`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("mine", """^bk(?<index>\d+)""")))

    assertEquals(200, set.match("Mistborn, Book 2")?.storedIndex)
    assertEquals(700, set.match("bk7")?.storedIndex)
  }

  @Test
  fun `BEFORE lets a user pattern pre-empt a built-in`() {
    // Would be read by `seanap` as position 1; the user's rule says otherwise.
    val mine = userPattern("mine", """^Expanse (?<index>\d+) - """)
    val set = SeriesIndexPatternSet.of(listOf(mine), PatternOrder.BEFORE)

    assertEquals("mine", set.match("Expanse 1 - Leviathan Wakes")?.patternName)
  }

  @Test
  fun `AFTER lets the built-ins win and uses a user pattern only as a fallback`() {
    val mine = userPattern("mine", """^Expanse (?<index>\d+) - """)
    val set = SeriesIndexPatternSet.of(listOf(mine), PatternOrder.AFTER)

    assertEquals("seanap", set.match("Expanse 1 - Leviathan Wakes")?.patternName)
  }

  @Test
  fun `REPLACE drops the built-ins entirely`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("mine", """^bk(?<index>\d+)""")), PatternOrder.REPLACE)

    assertEquals(700, set.match("bk7")?.storedIndex)
    assertNull("a built-in form must no longer parse", set.match("Mistborn, Book 2"))
  }

  @Test
  fun `an empty user list leaves the built-ins untouched`() {
    val set = SeriesIndexPatternSet.of(emptyList())

    assertEquals(DEFAULT_SERIES_INDEX_PATTERNS.size, set.usable.size)
  }

  @Test
  fun `a user pattern is marked as user-defined however it was constructed`() {
    val set = SeriesIndexPatternSet.of(listOf(SeriesIndexPattern("mine", """(?<index>\d+)""")))

    assertTrue(set.usable.first { it.name == "mine" }.isUserDefined)
  }

  // ---- a pattern that matches but yields nothing must not abort ----

  /**
   * A broad pattern capturing an unusable value must **fall through**, not give up.
   *
   * tvnamer aborts the whole parse here, so one greedy user pattern can break titles that a later
   * built-in reads correctly. Falling through is the behaviour worth having.
   */
  @Test
  fun `a pattern capturing an out-of-range value falls through to a later one`() {
    // Matches any four-digit run — a year — which is out of range and must not win.
    val greedy = userPattern("greedy", """(?<index>\d{4})""")
    val set = SeriesIndexPatternSet.of(listOf(greedy), PatternOrder.BEFORE)

    val match = set.match("1994 - Book 1 - Wizards First Rule")

    assertEquals("label_mid", match?.patternName)
    assertEquals(100, match?.storedIndex)
  }

  @Test
  fun `a pattern capturing a non-numeric value falls through`() {
    val nonNumeric = userPattern("letters", """^(?<index>[a-z]+)""")
    val set = SeriesIndexPatternSet.of(listOf(nonNumeric), PatternOrder.BEFORE)

    assertEquals("audnexus", set.match("Mistborn, Book 2")?.patternName)
  }

  /**
   * An optional group absent from the matching pattern must not throw.
   *
   * `MatchResult.groups["name"]` raises `IllegalArgumentException` for a group the matching pattern
   * never declared — it does not return null. Four of the seven built-ins declare no `series`
   * group, so reading it naively crashed the majority of matches while this was being written.
   */
  @Test
  fun `a match from a pattern with no series group reports an empty series`() {
    val match = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS).match("01 - Book Title")

    assertNotNull(match)
    assertEquals("", match?.series)
  }

  @Test
  fun `a match from a pattern with a series group reports it`() {
    val match = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS).match("Mistborn, Book 2 - Well")

    assertEquals("Mistborn", match?.series)
  }

  // ---- the diagnostic ----

  /**
   * "Why did my pattern not match?" must be answerable.
   *
   * tvnamer's open issue #216 is exactly this: a user gets `Cannot parse '<filename>'` and nothing
   * else, even with `--verbose`, so the only tool is trial and error.
   */
  @Test
  fun `explain reports every pattern's verdict`() {
    val set = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS)

    val attempts = set.explain("Mistborn, Book 2 - Well")

    assertEquals(DEFAULT_SERIES_INDEX_PATTERNS.size, attempts.size)
    assertEquals("audnexus", attempts.first { it.succeeded }.patternName)
  }

  @Test
  fun `explain says why a pattern was rejected`() {
    val set = SeriesIndexPatternSet.of(listOf(userPattern("greedy", """(?<index>\d{4})""")))

    val greedy = set.explain("1994 - Book 1 - Wizards").first { it.patternName == "greedy" }

    assertTrue(greedy.matched)
    assertEquals("1994", greedy.capturedIndex)
    assertNotNull("a rejected match must say why", greedy.rejectedReason)
    assertFalse(greedy.succeeded)
  }

  @Test
  fun `explain reports a non-match as such`() {
    val set = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS)

    val attempts = set.explain("Standalone Book")

    assertTrue("nothing should succeed", attempts.none { it.succeeded })
    assertTrue(attempts.all { it.rejectedReason != null })
  }
}

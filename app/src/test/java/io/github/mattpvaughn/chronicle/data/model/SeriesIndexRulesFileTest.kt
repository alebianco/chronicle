package io.github.mattpvaughn.chronicle.data.model

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a user's own parsing rules from a file (cu-148, decision-18).
 *
 * Almost every case here is a **degradation** case, and that is the point. The rules are a regex in
 * a hand-edited file: typos are the normal state, not the exception, and decision-18's contract is
 * that a bad one costs the user that rule and nothing else. tvnamer's failure — a malformed config
 * taking every built-in down with it — is what these assert against.
 */
class SeriesIndexRulesFileTest {
  /**
   * The **codegen** adapters, not the reflective ones.
   *
   * The app removed `KotlinJsonAdapterFactory` in cu-62 and parses with `@JsonClass`-generated
   * adapters, so a test using the reflective factory would exercise a different parser than the
   * one that ships — and would leave the generated code with no coverage at all, which is what the
   * per-package gate caught.
   */
  private val moshi = Moshi.Builder().build()

  private fun parse(json: String) = parseSeriesIndexRules(json, moshi)

  // ---- the happy path ----

  @Test
  fun `a well-formed file yields its rules`() {
    val parsed =
      parse(
        """
        {
          "version": 1,
          "order": "before",
          "rules": [
            { "name": "my_shelf", "pattern": "^Part (?<index>\\d+)", "description": "mine" }
          ]
        }
        """.trimIndent(),
      )

    assertEquals(1, parsed.rules.size)
    assertEquals("my_shelf", parsed.rules.single().name)
    assertEquals(PatternOrder.BEFORE, parsed.order)
  }

  @Test
  fun `a rule from a file is marked as user-defined`() {
    val parsed = parse("""{"rules":[{"name":"mine","pattern":"(?<index>\\d+)"}]}""")

    assertTrue(parsed.rules.single().isUserDefined)
  }

  @Test
  fun `each order is understood, case-insensitively`() {
    assertEquals(PatternOrder.AFTER, parse("""{"order":"after","rules":[]}""").order)
    assertEquals(PatternOrder.REPLACE, parse("""{"order":"REPLACE","rules":[]}""").order)
    assertEquals(PatternOrder.BEFORE, parse("""{"order":"Before","rules":[]}""").order)
  }

  @Test
  fun `an absent order defaults to before`() {
    assertEquals(PatternOrder.BEFORE, parse("""{"rules":[]}""").order)
  }

  // ---- degradation ----

  /** The commonest accident: a half-edited file. It must cost the rules, not the app. */
  @Test
  fun `malformed JSON yields no rules rather than throwing`() {
    val parsed = parse("""{"rules": [ {"name": "broken" """)

    assertTrue(parsed.isEmpty)
    assertEquals(PatternOrder.BEFORE, parsed.order)
  }

  @Test
  fun `an empty file yields no rules`() {
    assertTrue(parse("").isEmpty)
  }

  @Test
  fun `a file with no rules array yields no rules`() {
    assertTrue(parse("""{"version":1}""").isEmpty)
  }

  /**
   * A newer file is refused rather than guessed at.
   *
   * The same reasoning as `importSettingsOrNull` (cu-22): a later version may mean something
   * different by the same keys, and silently misreading a user's rules is worse than ignoring them.
   */
  @Test
  fun `a file from a newer version is ignored`() {
    val parsed =
      parse("""{"version":${RULES_SCHEMA_VERSION + 1},"rules":[{"name":"x","pattern":"(?<index>\\d+)"}]}""")

    assertTrue(parsed.isEmpty)
  }

  @Test
  fun `an unknown order falls back to before rather than failing`() {
    val parsed = parse("""{"order":"sideways","rules":[{"name":"x","pattern":"(?<index>\\d+)"}]}""")

    assertEquals(PatternOrder.BEFORE, parsed.order)
    assertEquals(1, parsed.rules.size)
  }

  /** One bad rule must not take the good ones with it. */
  @Test
  fun `a nameless rule is dropped and the rest survive`() {
    val parsed =
      parse(
        """{"rules":[
          {"name":"","pattern":"(?<index>\\d+)"},
          {"name":"good","pattern":"^Part (?<index>\\d+)"}
        ]}""",
      )

    assertEquals(listOf("good"), parsed.rules.map { it.name })
  }

  @Test
  fun `a patternless rule is dropped and the rest survive`() {
    val parsed =
      parse(
        """{"rules":[
          {"name":"empty","pattern":""},
          {"name":"good","pattern":"^Part (?<index>\\d+)"}
        ]}""",
      )

    assertEquals(listOf("good"), parsed.rules.map { it.name })
  }

  // ---- the handover to the pattern set ----

  /**
   * An uncompilable pattern survives parsing and is rejected by the set.
   *
   * Deliberate: validation happens at *load*, in one place, rather than being duplicated here —
   * and `SeriesIndexPatternSet` is where the "must compile and capture an index" rule lives.
   */
  @Test
  fun `an uncompilable pattern is rejected by the pattern set, not the parser`() {
    val parsed = parse("""{"rules":[{"name":"broken","pattern":"(?<index>["}]}""")

    assertEquals(1, parsed.rules.size)

    val set = SeriesIndexPatternSet.of(parsed.rules, parsed.order)
    assertFalse(set.usable.any { it.name == "broken" })
  }

  @Test
  fun `a pattern capturing no index is rejected by the pattern set`() {
    val parsed = parse("""{"rules":[{"name":"groupless","pattern":"^Part \\d+"}]}""")

    val set = SeriesIndexPatternSet.of(parsed.rules, parsed.order)

    assertFalse(set.usable.any { it.name == "groupless" })
  }

  /** End to end: a file's rule actually parses a title the built-ins cannot. */
  @Test
  fun `a user rule parses a title the built-ins do not`() {
    val exotic = "Wheel of Time :: Volume 7 :: A Crown of Swords"
    assertTrue(
      "the built-ins should not already handle this, or the test proves nothing",
      SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS).match(exotic) == null,
    )

    val parsed = parse("""{"rules":[{"name":"colons","pattern":":: Volume (?<index>\\d+) ::"}]}""")
    val set = SeriesIndexPatternSet.of(parsed.rules, parsed.order)

    assertEquals(700, set.match(exotic)?.storedIndex)
    assertEquals("colons", set.match(exotic)?.patternName)
  }

  @Test
  fun `a user rule does not displace the built-ins by default`() {
    val parsed = parse("""{"rules":[{"name":"colons","pattern":":: Volume (?<index>\\d+) ::"}]}""")
    val set = SeriesIndexPatternSet.of(parsed.rules, parsed.order)

    assertEquals(200, set.match("Mistborn, Book 2 - The Well of Ascension")?.storedIndex)
  }
}

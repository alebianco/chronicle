package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.ChronicleJson
import io.github.mattpvaughn.chronicle.data.ChronicleJsonPretty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a user's own parsing rules from a file (decision-18).
 *
 * Almost every case here is a **degradation** case, and that is the point. The rules are a regex in
 * a hand-edited file: typos are the normal state, not the exception, and decision-18's contract is
 * that a bad one costs the user that rule and nothing else. tvnamer's failure — a malformed config
 * taking every built-in down with it — is what these assert against.
 */
class SeriesIndexRulesFileTest {
  /**
   * The app's own parser, not a locally configured one.
   *
   * `parseSeriesIndexRules` goes through `ChronicleJson`, so these exercise the parser that ships
   * — settings and all. A test that built its own `Json` would assert against a parser the app
   * does not use, which is how a leniency difference reaches a user's file unnoticed.
   */
  private fun parse(json: String) = parseSeriesIndexRules(json)

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
   * The same reasoning as `importSettingsOrNull`: a later version may mean something
   * different by the same keys, and silently misreading a user's rules is worse than ignoring them.
   */
  @Test
  fun `a file from a newer version is ignored`() {
    val parsed =
      parse("""{"version":${RULES_SCHEMA_VERSION + 1},"rules":[{"name":"x","pattern":"(?<index>\\d+)"}]}""")

    assertTrue(parsed.isEmpty)
  }

  /**
   * The tolerance that costs a field its type.
   *
   * `order` is a `String`, not [PatternOrder], precisely so a typo is *reported* rather than
   * fatal — a serializer asked for the enum rejects an unknown constant and takes the whole file
   * with it, valid rules included. That is true of every serializer this project has used, and
   * `ignoreUnknownKeys` does not help: it covers unknown *keys*, not unknown enum *values*. So the
   * assertion that matters here is the second one — the rule survived the bad order.
   */
  @Test
  fun `an unknown order falls back to before rather than failing`() {
    val parsed = parse("""{"order":"sideways","rules":[{"name":"x","pattern":"(?<index>\\d+)"}]}""")

    assertEquals(PatternOrder.BEFORE, parsed.order)
    assertEquals("a typo in `order` must not discard the rules beside it", 1, parsed.rules.size)
  }

  /**
   * The shape in [SeriesIndexRulesFile]'s own KDoc, written and read back.
   *
   * The KDoc shows an example file and tells a user to write one — so it is documentation that can
   * be wrong, and the only way it stays true is a test that produces the same shape from the types
   * themselves. It also covers the *writing* direction, which nothing else does: the parser builds
   * these through a generated serializer, so an ordinary construction is otherwise never exercised.
   */
  @Test
  fun `the documented file shape round-trips through the types`() {
    val file =
      SeriesIndexRulesFile(
        version = RULES_SCHEMA_VERSION,
        order = "before",
        rules =
          listOf(
            SeriesIndexRuleEntry(
              name = "my_shelf",
              pattern = """^(?<series>.+?) - Part (?<index>\d+)""",
              description = "How my own tagger writes a series",
            ),
          ),
      )

    val json = ChronicleJsonPretty.encodeToString(file)

    // Every field the KDoc names must actually appear — `encodeDefaults` is what puts `version`
    // and `order` there even though both sit at their defaults.
    assertTrue("the written file must name its version: $json", json.contains("\"version\""))
    assertTrue(json.contains("\"order\""))
    assertTrue(json.contains("\"my_shelf\""))

    assertEquals(file, ChronicleJson.decodeFromString<SeriesIndexRulesFile>(json))

    // And the parser accepts what the writer produced, which is the property a user relies on
    // when they copy the example out of the KDoc.
    val parsed = parse(json)
    assertEquals(PatternOrder.BEFORE, parsed.order)
    assertEquals(listOf("my_shelf"), parsed.rules.map { it.name })
  }

  /**
   * The defaults the KDoc leans on: a file naming only what it changes.
   *
   * `description` is optional and `version`/`order` have defaults, so the smallest useful rules
   * file is a name and a pattern. Constructing one that way asserts the defaults are what the
   * documentation says — and that an omitted `description` is an empty string rather than a
   * failure, which is what the parser relies on for a hand-written file.
   */
  @Test
  fun `the smallest useful file uses every default`() {
    val minimal = SeriesIndexRulesFile(rules = listOf(SeriesIndexRuleEntry(name = "x", pattern = "(?<index>\\d+)")))

    assertEquals(RULES_SCHEMA_VERSION, minimal.version)
    assertEquals("before", minimal.order)
    assertEquals("", minimal.rules.single().description)

    // And it still yields a usable rule, which is the thing a user writing the minimum expects.
    assertEquals(listOf("x"), parse(ChronicleJson.encodeToString(minimal)).rules.map { it.name })
  }

  @Test
  fun `an unknown field does not discard the file`() {
    // A rules file written by a future build, or hand-edited with a stray key. Unknown keys at
    // both levels — beside `rules`, and inside a rule — must be ignored rather than thrown on,
    // which is `ChronicleJson`'s `ignoreUnknownKeys` and not the parser's default.
    val parsed =
      parse(
        """{
          "order":"after",
          "somethingTheFutureAdded":{"nested":[1,2]},
          "rules":[{"name":"x","pattern":"(?<index>\\d+)","futureField":true}]
        }""",
      )

    assertEquals(PatternOrder.AFTER, parsed.order)
    assertEquals(listOf("x"), parsed.rules.map { it.name })
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

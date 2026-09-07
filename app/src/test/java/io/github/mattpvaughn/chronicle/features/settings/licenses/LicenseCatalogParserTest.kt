package io.github.mattpvaughn.chronicle.features.settings.licenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the generated `aboutlibraries.json`.
 *
 * A plain JVM test, which is the reason the parse was split out of [GeneratedLicenseCatalogSource]
 * at all: everything with a fallback in it lives here, where it can be driven with a hand-written
 * document rather than through the app's own generated resource.
 */
class LicenseCatalogParserTest {
  @Test
  fun `reads libraries and resolves their licence ids`() {
    val catalog =
      LicenseCatalogParser.parse(
        json(
          libraries = """{"uniqueId":"io.ktor:ktor-core","name":"Ktor","artifactVersion":"3.2.1","licenses":["Apache-2.0"]}""",
        ),
      )

    val library = catalog?.libraries?.single()
    assertEquals("io.ktor:ktor-core", library?.uniqueId)
    assertEquals("Ktor", library?.name)
    assertEquals("3.2.1", library?.version)
    assertEquals(
      listOf(LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")),
      library?.licenses,
    )
  }

  /**
   * A library whose POM carries no `<name>` falls back to its coordinate.
   *
   * An entry rendered with an empty title reads as a rendering bug and hides which dependency it
   * even is — which on this page is the difference between a finding and a mystery.
   */
  @Test
  fun `falls back to the coordinate when a library has no name`() {
    val catalog =
      LicenseCatalogParser.parse(
        json(libraries = """{"uniqueId":"com.example:nameless","licenses":["Apache-2.0"]}"""),
      )

    assertEquals("com.example:nameless", catalog?.libraries?.single()?.name)
  }

  @Test
  fun `falls back to the coordinate when a library's name is blank`() {
    val catalog =
      LicenseCatalogParser.parse(
        json(libraries = """{"uniqueId":"com.example:blank","name":"   ","licenses":["Apache-2.0"]}"""),
      )

    assertEquals("com.example:blank", catalog?.libraries?.single()?.name)
  }

  /**
   * A licence id with no entry in the `licenses` map is dropped, and the **library is kept**.
   *
   * The library then renders with the "no license declared" marker, which is a visible finding.
   * Dropping the library instead would shorten the page and leave the count agreeing with it, so
   * nothing would look wrong.
   */
  @Test
  fun `keeps a library whose licence id cannot be resolved`() {
    val catalog =
      LicenseCatalogParser.parse(
        json(libraries = """{"uniqueId":"com.example:dangling","name":"Dangling","licenses":["Nonexistent-1.0"]}"""),
      )

    val library = catalog?.libraries?.single()
    assertEquals("com.example:dangling", library?.uniqueId)
    assertEquals(emptyList<LicenseSummary>(), library?.licenses)
  }

  @Test
  fun `keeps a library that declares no licences at all`() {
    val catalog =
      LicenseCatalogParser.parse(json(libraries = """{"uniqueId":"com.example:none","name":"None"}"""))

    assertEquals(1, catalog?.total)
    assertEquals(emptyList<LicenseSummary>(), catalog?.libraries?.single()?.licenses)
  }

  /** A blank licence URL becomes null, so the screen has one thing to check rather than two. */
  @Test
  fun `normalises a blank licence URL to null`() {
    val catalog =
      LicenseCatalogParser.parse(
        """
        {
          "libraries": [{"uniqueId":"com.example:a","name":"A","licenses":["X"]}],
          "licenses": {"X": {"name": "Some licence", "url": "  "}}
        }
        """.trimIndent(),
      )

    assertNull(catalog?.libraries?.single()?.licenses?.single()?.url)
  }

  /** A licence with no name falls back to its SPDX id, then to the map key — never to blank. */
  @Test
  fun `falls back through spdxId to the map key for a licence name`() {
    val bySpdx =
      LicenseCatalogParser.parse(
        """
        {
          "libraries": [{"uniqueId":"com.example:a","name":"A","licenses":["key"]}],
          "licenses": {"key": {"spdxId": "MIT", "url": "https://example.test"}}
        }
        """.trimIndent(),
      )
    assertEquals("MIT", bySpdx?.libraries?.single()?.licenses?.single()?.name)

    val byKey =
      LicenseCatalogParser.parse(
        """
        {
          "libraries": [{"uniqueId":"com.example:a","name":"A","licenses":["key"]}],
          "licenses": {"key": {"url": "https://example.test"}}
        }
        """.trimIndent(),
      )
    assertEquals("key", byKey?.libraries?.single()?.licenses?.single()?.name)
  }

  /**
   * Unknown keys are ignored.
   *
   * The generator writes a dozen fields per library that this app never reads — developers, funding,
   * SCM, targets — and adds more between versions. `ChronicleJson.ignoreUnknownKeys` is what stops
   * a plugin upgrade turning the licences screen into an error page, so it is worth pinning here
   * rather than trusting a shared setting to stay set.
   */
  @Test
  fun `ignores the generator's fields this app does not read`() {
    val catalog =
      LicenseCatalogParser.parse(
        """
        {
          "libraries": [{
            "uniqueId":"com.example:a","name":"A","licenses":["Apache-2.0"],
            "developers":[{"name":"Someone"}],"funding":[],"tag":"x",
            "scm":{"url":"https://example.test"},"somethingAddedNextRelease": 7
          }],
          "licenses": {"Apache-2.0": {"name":"Apache License 2.0","url":"https://spdx.org/licenses/Apache-2.0.html"}}
        }
        """.trimIndent(),
      )

    assertEquals("A", catalog?.libraries?.single()?.name)
  }

  /**
   * Malformed input is null, never an empty catalogue.
   *
   * An empty licences page and a broken one are indistinguishable to a reader, and this app always
   * has dependencies — so the screen must be able to tell them apart and say which happened.
   */
  @Test
  fun `returns null for input that is not the expected document`() {
    assertNull(LicenseCatalogParser.parse("not json at all"))
    assertNull(LicenseCatalogParser.parse(""))
    assertNull(
      "a library with no uniqueId has no identity, so the document is unusable rather than partial",
      LicenseCatalogParser.parse("""{"libraries":[{"name":"A"}],"licenses":{}}"""),
    )
  }

  /** An empty but well-formed document parses to an empty catalogue, not to null. */
  @Test
  fun `an empty document is an empty catalogue, not a failure`() {
    assertEquals(LicenseCatalog.EMPTY, LicenseCatalogParser.parse("""{"libraries":[],"licenses":{}}"""))
  }

  private fun json(libraries: String) =
    """
    {
      "libraries": [$libraries],
      "licenses": {"Apache-2.0": {"name":"Apache License 2.0","url":"https://spdx.org/licenses/Apache-2.0.html"}}
    }
    """.trimIndent()
}

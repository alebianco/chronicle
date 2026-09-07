package io.github.mattpvaughn.chronicle.features.settings.licenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the licences screen renders is reconciled against the **resolved release dependency graph**.
 *
 * ### Why this test is the point of the feature
 *
 * A generated licences page that silently misses a dependency is worse than no page at all, because
 * it looks like diligence. Every other test here asserts that the projection behaves; this one
 * asserts that it did not *lose* anything between the graph and the screen.
 *
 * The reference is `aboutlibraries.json`, which the AboutLibraries Gradle plugin writes after
 * resolving `releaseRuntimeClasspath` — the classpath of the variant that actually ships. The debug
 * variant would be the wrong reference: it carries test and tooling artifacts that never reach a
 * user, so a page reconciled against it would over-report.
 *
 * ### Why it parses the JSON itself
 *
 * Deliberately **not** through `Libs.Builder`. Reconciling the projection against the same reader
 * the projection uses would make the guard assert against itself: a parser that dropped every entry
 * would produce an empty catalogue *and* an empty reference, and the counts would agree. This reads
 * the file with plain kotlinx-serialization instead, so the two sides fail independently.
 *
 * It cannot go through `GeneratedLicenseCatalogSource` either, which needs a `Context` and a
 * packaged raw resource. What it can and does check is the [LicenseCatalog.from] contract the
 * source's output passes through — dropping an entry there would shorten the page just as
 * effectively as dropping it at the parse.
 */
class LicenseCatalogCountTest {
  private val generated = File(GENERATED_CATALOG)

  /**
   * Guards the guard.
   *
   * Without this, a moved or unwritten file would leave `entries` empty and every assertion below
   * would compare zero against zero and pass — a reconciliation that silently does not run, which
   * is the same failure this test exists to catch, one level up. The build wires
   * `prepareLibraryDefinitionsRelease` into every `Test` task so the file is always there.
   */
  @Test
  fun `the generated catalogue exists and is not trivially small`() {
    assertTrue(
      "no generated catalogue at $GENERATED_CATALOG — is the AboutLibraries plugin still applied, " +
        "and does the test task still depend on prepareLibraryDefinitionsRelease?",
      generated.isFile,
    )
    assertTrue(
      "the generated catalogue lists ${graphEntries().size} dependencies, which is too few to be " +
        "this app's release classpath — the plugin is probably resolving the wrong configuration",
      graphEntries().size > 100,
    )
  }

  /**
   * Every coordinate on the release runtime classpath reaches the rendered list.
   *
   * Asserted as a **set difference**, not as two counts. Equal counts would also hold if the
   * projection dropped one dependency and duplicated another, which is exactly the kind of quiet
   * wrongness a compliance page must not have.
   */
  @Test
  fun `every dependency in the resolved release graph is rendered`() {
    val inGraph = graphEntries().map { it.uniqueId }.toSet()
    val rendered = LicenseCatalog.from(graphEntries()).libraries.map { it.uniqueId }.toSet()

    assertEquals(
      "these dependencies are on the release runtime classpath but would not appear on the " +
        "licences screen. A page that misses one is worse than no page, because it looks like " +
        "diligence — fix the projection rather than the expectation.",
      emptySet<String>(),
      inGraph - rendered,
    )
    assertEquals(
      "the licences screen would list dependencies that are not on the release runtime classpath",
      emptySet<String>(),
      rendered - inGraph,
    )
  }

  /** The count the screen publishes is the number of dependencies in the graph. */
  @Test
  fun `the rendered count equals the number of dependencies in the graph`() {
    val entries = graphEntries()
    assertEquals(
      "the screen renders its total as evidence; it has to be the graph's own number",
      entries.map { it.uniqueId }.toSet().size,
      LicenseCatalog.from(entries).total,
    )
  }

  /**
   * Every entry carries a licence, or is visibly marked as carrying none.
   *
   * Not "every entry has a licence" — that would be an assertion about other people's POMs, and it
   * would fail the build the day a dependency ships without one, which is a fact to surface rather
   * than a break to fix. What is asserted is that such an entry is still **on the page**: it is
   * kept and rendered with the "no license declared" marker, never dropped. The count of them is
   * reported so a new one is noticed.
   */
  @Test
  fun `an entry with no declared licence is kept on the page`() {
    val entries = graphEntries()
    val unlicensed = entries.filter { it.licenses.isEmpty() }.map { it.uniqueId }
    val rendered = LicenseCatalog.from(entries).libraries.map { it.uniqueId }.toSet()

    assertTrue(
      "these dependencies declare no licence and were dropped instead of marked: " +
        "${unlicensed.filterNot { it in rendered }}",
      unlicensed.all { it in rendered },
    )
  }

  /**
   * Every licence the page names is linkable.
   *
   * The acceptance criterion is licence text *or* a working link, and this build takes the link
   * route on purpose: embedding full text means fetching it from the GitHub API at build time,
   * which is rate-limited without a token and would make the build depend on the network. This
   * pins the half that was chosen — a licence rendered with neither text nor URL would satisfy
   * neither.
   */
  @Test
  fun `every licence the page names carries a URL`() {
    val linkless =
      graphEntries()
        .flatMap { library -> library.licenses.map { library.uniqueId to it } }
        .filter { (_, license) -> license.url.isNullOrBlank() }
        .map { (id, license) -> "$id -> ${license.name}" }

    assertEquals(
      "these licences would render with no text and nowhere to go. Compliance needs the terms " +
        "reachable: either the metadata gains a URL, or this build starts embedding licence text.",
      emptyList<String>(),
      linkless,
    )
  }

  /**
   * The dependency that used to generate this page is gone.
   *
   * `play-services-oss-licenses` is a **Google Play Services** dependency, and decision-1 puts
   * sideload/F-Droid/homelab distribution first — F-Droid does not accept a GMS dependency, so the
   * tool nominally doing this job was itself a distribution blocker. Asserted against the resolved
   * graph rather than the build file, because a transitive reintroduction would not show up there.
   *
   * The Cast artifacts are a separate and accepted matter (decision-19, `CastPlayerProvider`), so
   * this names one coordinate rather than banning the group.
   */
  @Test
  fun `the Play Services licences library is no longer on the release classpath`() {
    assertEquals(
      "play-services-oss-licenses is back on the release runtime classpath. It is a GMS " +
        "dependency and F-Droid does not accept one (decision-1); AboutLibraries generates this " +
        "page instead.",
      emptyList<String>(),
      graphEntries().map { it.uniqueId }.filter { it == OSS_LICENSES_COORDINATE },
    )
  }

  /**
   * The generated catalogue, read with a parser independent of the app's own.
   *
   * Mirrors `GeneratedLicenseCatalogSource`'s projection — the same fallbacks for a blank name and
   * a blank URL — because it is that projection's *output* the page renders. Written out here
   * rather than shared with production code on purpose: a helper both sides called would make an
   * error in it invisible to this test.
   */
  private fun graphEntries(): List<LicensedLibrary> {
    val root = Json.parseToJsonElement(generated.readText()).jsonObject
    val licencesById =
      root.getValue("licenses").jsonObject.mapValues { (id, element) ->
        val license = element.jsonObject
        LicenseSummary(
          name = license["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: id,
          url = license["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        )
      }

    return root.getValue("libraries").jsonArray.map { element ->
      val library = element.jsonObject
      val uniqueId = library.getValue("uniqueId").jsonPrimitive.content
      LicensedLibrary(
        uniqueId = uniqueId,
        name = library["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: uniqueId,
        version = library["artifactVersion"]?.jsonPrimitive?.content.orEmpty(),
        licenses =
          library["licenses"]?.jsonArray.orEmpty().mapNotNull { licencesById[it.jsonPrimitive.content] },
      )
    }
  }

  private companion object {
    /**
     * Written by `prepareLibraryDefinitionsRelease`, relative to the `app` module — which is the
     * working directory a Gradle `Test` task runs in.
     */
    const val GENERATED_CATALOG = "build/generated/aboutLibraries/release/res/raw/aboutlibraries.json"
    const val OSS_LICENSES_COORDINATE = "com.google.android.gms:play-services-oss-licenses"
  }
}

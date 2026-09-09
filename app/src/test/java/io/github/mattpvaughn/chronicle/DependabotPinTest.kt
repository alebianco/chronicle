package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every deliberate version pin is actually held by `.github/dependabot.yml`.
 *
 * The pin table in the backlog exists to stop a weekly PR against a version held on purpose. A pin
 * that is documented in the table but not encoded in the config is worse than no table: it reads
 * as covered while the bot proposes the bump anyway.
 *
 * Two ways that has already happened, both caught in review rather than by a test — which is why
 * this test exists:
 *
 * **A half-ignored coordinate pair.** `libs.versions.toml` declares a deliberately unbalanced
 * hamcrest pair: `hamcrest-all:1.3` *and* `org.hamcrest:hamcrest:2.2`. Ignoring only the first
 * leaves the second free to move. Espresso's `ViewMatchers` reference `org.hamcrest.Matchers` at
 * runtime; with the pair out of balance that class lands in neither merged dex and `withId()` dies
 * with `NoClassDefFoundError` while the dependency still looks present on the resolved classpath.
 *
 * **An `update-types` filter that leaks patches.** KSP is versioned `<kotlin>-<ksp>` and built
 * against one exact Kotlin version, so a Kotlin *patch* bump is as breaking as a minor one: it
 * would open with no published KSP twin. Kotlin 2.4 is published, but KSP's newest release is for
 * 2.3, and Room, Hilt, Moshi and Ktorfit all run through it, so nothing here can outrun KSP.
 * Restricting the Kotlin/KSP ignores to minor and major re-opens exactly that door. Grouping does
 * not rescue it —
 * grouping batches updates Dependabot independently decides to propose, and it cannot invent an
 * unpublished KSP version.
 *
 * Neither failure is caught downstream: `verify.sh` never runs instrumented tests, so such a PR
 * goes green and the breakage reaches the branch.
 */
class DependabotPinTest {
  private val config = File(DEPENDABOT_CONFIG)

  /**
   * The `ignore:` entries of the gradle ecosystem, as (coordinate, body) pairs, where the body is
   * every line belonging to that entry up to the next one.
   *
   * Split on the `- dependency-name:` lines rather than matching a body pattern. An earlier version
   * captured the body with `([^-]*)`, which stops dead at the first hyphen — so it never saw an
   * `update-types: [ "version-update:semver-minor" ]` line, and the very defect these tests exist
   * to catch passed clean. The parser must not be more fragile than the thing it guards.
   */
  private fun gradleIgnoreEntries(): List<Pair<String, String>> {
    val text = config.readText()
    val gradleBlock =
      text.substringAfter("package-ecosystem: gradle").substringBefore("package-ecosystem: github-actions")
    val ignoreBlock = gradleBlock.substringAfter("ignore:")

    val starts = Regex("""(?m)^\s*-\s+dependency-name:\s*"([^"]+)"""").findAll(ignoreBlock).toList()
    return starts.mapIndexed { index, match ->
      val bodyStart = match.range.last + 1
      val bodyEnd = starts.getOrNull(index + 1)?.range?.first ?: ignoreBlock.length
      match.groupValues[1] to ignoreBlock.substring(bodyStart, bodyEnd)
    }
  }

  private fun entryFor(coordinate: String): Pair<String, String>? =
    gradleIgnoreEntries().firstOrNull { (pattern, _) ->
      val regex = Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })
      regex.matches(coordinate)
    }

  @Test
  fun `config exists and declares both ecosystems`() {
    assertTrue("`.github/dependabot.yml` is missing", config.isFile)
    val text = config.readText()
    assertTrue("gradle ecosystem missing", text.contains("package-ecosystem: gradle"))
    assertTrue("github-actions ecosystem missing", text.contains("package-ecosystem: github-actions"))
    assertTrue(
      "Dependabot must target feature/agentic-dev (decision-23), not the default branch",
      text.contains("target-branch: feature/agentic-dev"),
    )
  }

  @Test
  fun `both hamcrest coordinates are ignored, not just hamcrest-all`() {
    // The pair from libs.versions.toml. Moving EITHER one alone recreates the dex mismatch.
    for (coordinate in listOf("org.hamcrest:hamcrest-all", "org.hamcrest:hamcrest")) {
      val entry = entryFor(coordinate)
      assertTrue(
        "$coordinate is not ignored. The hamcrest pin is a deliberately unbalanced pair " +
          "(hamcrest-all:1.3 + hamcrest:2.2); ignoring one coordinate and not the other lets " +
          "Dependabot rebalance it and break Espresso at runtime.",
        entry != null,
      )
      assertFalse(
        "$coordinate is version-bounded, but this pin has no safe upper range: any movement of " +
          "either coordinate alone breaks the pair.",
        entry!!.second.contains("versions:"),
      )
    }
  }

  @Test
  fun `kotlin and KSP ignores do not exempt patch releases`() {
    for (coordinate in listOf(
      "org.jetbrains.kotlin:kotlin-stdlib",
      "org.jetbrains.kotlin:kotlin-gradle-plugin",
      "com.google.devtools.ksp",
    )) {
      val entry = entryFor(coordinate)
      assertTrue("$coordinate is not ignored at all", entry != null)

      val body = entry!!.second
      if (body.contains("update-types:")) {
        assertTrue(
          "$coordinate restricts update-types without including semver-patch. KSP is built " +
            "against one exact Kotlin version, so a patch bump (2.3.21 -> 2.3.22) would open " +
            "with no published KSP twin — the broken build the kotlin group exists to prevent.",
          body.contains("version-update:semver-patch"),
        )
      }
      assertFalse(
        "$coordinate must not carry a `versions:` ceiling — the whole coordinate is pinned " +
          "until KSP ships for the target Kotlin.",
        body.contains("versions:"),
      )
    }
  }

  @Test
  fun `every deliberate pin is held`() {
    // Each deliberate pin, by a coordinate it must cover.
    val pins =
      listOf(
        "org.jetbrains.kotlin:kotlin-stdlib" to "Kotlin — no KSP release for Kotlin 2.4",
        "com.google.devtools.ksp" to "KSP — versioned against one exact Kotlin",
        "androidx.compose:compose-bom" to "Compose BOM > 2026.06.x needs compileSdk 37",
        "androidx.lifecycle:lifecycle-runtime-ktx" to "lifecycle >= 2.11 needs compileSdk 37 and AGP 9.1",
        "androidx.room:room-runtime" to "Room 3.0 is a breaking major, alpha, no consumer",
        "org.hamcrest:hamcrest" to "hamcrest pair is balanced by hand",
      )

    val unheld = pins.filter { (coordinate, _) -> entryFor(coordinate) == null }
    assertTrue(
      "These pins are deliberate but not held by dependabot.yml, so the bot " +
        "will propose them weekly: " + unheld.joinToString("; ") { "${it.first} (${it.second})" },
      unheld.isEmpty(),
    )
  }

  @Test
  fun `every ignore entry explains itself`() {
    // Every pin needs the condition that lifts it. A pin with no stated reason cannot be retired
    // safely, because nobody can tell when it stopped applying — so it becomes permanent by
    // default, which is how a temporary ceiling outlives the constraint that caused it.
    val text = config.readText()
    val ignoreBlock =
      text.substringAfter("package-ecosystem: gradle").substringBefore("package-ecosystem: github-actions")
        .substringAfter("ignore:")

    val entries = Regex("""-\s+dependency-name:\s*"([^"]+)"""").findAll(ignoreBlock).count()

    // An explicit roster, not a count and not a comment scan.
    //
    // Two weaker versions of this check were tried and both passed against a real sabotage that
    // appended two undocumented pins. `notes >= entries / 2` failed because a surplus of notes in
    // one block silently pays for a pin documented nowhere. Attributing each pin to the comment
    // block above it failed too: the sabotage sat *inside* an already-documented group, directly
    // under the Room entry, so it inherited Room's note. No amount of comment parsing distinguishes
    // "this pin is covered by the comment above" from "this pin was slipped in beneath it".
    //
    // So the roster is the assertion. Adding a pin means adding it here, which is the point: the
    // second author has to state what it is and why, in a file a reviewer reads.
    val expected =
      setOf(
        "org.jetbrains.kotlin:*",
        "org.jetbrains.kotlin.*",
        "com.google.devtools.ksp*",
        "androidx.compose:compose-bom",
        "androidx.lifecycle:*",
        // `androidx.navigation:*` was here, holding Navigation Compose below 2.10.0 until
        // compileSdk 37 landed. Both the hold and the *dependency* are gone: Circuit owns routing
        // (decision-27), and nothing imports `androidx.navigation` any more.
        "androidx.room:*",
        "org.hamcrest:*",
      )
    val actual = gradleIgnoreEntries().map { it.first }.toSet()

    assertTrue("no ignore entries found — the parser or the config shape changed", entries > 0)
    assertEquals(
      "the set of ignored coordinates changed. Anything added here must be a deliberate pin with " +
        "its reason and an 'Unblock when:' note in the config, and must be listed in this test. " +
        "Anything removed means a pin silently stopped being held.",
      expected,
      actual,
    )
  }

  companion object {
    // Unit tests run with the working directory at `app/`.
    const val DEPENDABOT_CONFIG = "../.github/dependabot.yml"
  }
}

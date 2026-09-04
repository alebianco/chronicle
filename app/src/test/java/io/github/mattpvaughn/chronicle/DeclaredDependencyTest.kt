package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every AndroidX package the app imports is **declared**, not inherited (cu-69).
 *
 * A transitive dependency is a version someone else chose and can drop without warning. It has
 * happened three times here — cu-60's `androidx.lifecycle`, and `androidx.localbroadcastmanager` and
 * `androidx.media` in cu-65 — each surfacing as a compile failure after an unrelated library bump,
 * with nothing in this repo saying the package was ever wanted.
 *
 * Declaring one does **not** mean upgrading it: each is pinned at the version it already resolved
 * to, so this change pins today's behaviour rather than altering it. The point is that the version
 * becomes a decision recorded in the catalogue instead of a side effect of appcompat's.
 *
 * **A source guard rather than a Gradle check**, because the question is "does the source import a
 * package the build file does not name", and only reading both can answer it. Gradle can report
 * what resolves, but not what the Kotlin actually reaches for.
 */
class DeclaredDependencyTest {
  @Test
  fun `every androidx package imported by the app is declared in the build file`() {
    val imported = importedAndroidXPackages()
    assertTrue("expected to find androidx imports at all", imported.size > 5)

    val declared = File(BUILD_FILE).readText()
    val undeclared = imported.filterNot { pkg -> isDeclared(pkg, declared) }.sorted()

    assertEquals(
      "these androidx packages are imported but not declared, so their version is whatever a " +
        "transitive dependency happens to pick — the shape of three breakages (cu-60, cu-65 x2). " +
        "Declare each at the version it already resolves to, which changes nothing today and " +
        "makes the choice explicit.",
      emptyList<String>(),
      undeclared,
    )
  }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the source root and build file both resolve`() {
    assertTrue("cannot find $BUILD_FILE", File(BUILD_FILE).exists())
    assertTrue(
      "expected Kotlin sources under $SOURCE_ROOT",
      File(SOURCE_ROOT).walkTopDown().count { it.extension == "kt" } > 100,
    )
  }

  /**
   * The second-level package of every `androidx.*` import in the app's own sources.
   *
   * `androidx.core.view.isVisible` -> `androidx.core`, which is the granularity a Gradle
   * coordinate has. Test sources are excluded: a test-only dependency is declared with
   * `testImplementation` and is a separate question.
   */
  private fun importedAndroidXPackages(): Set<String> =
    File(SOURCE_ROOT)
      .walkTopDown()
      .filter { it.extension == "kt" }
      .flatMap { it.readLines() }
      .mapNotNull { IMPORT.find(it)?.groupValues?.get(1) }
      .filterNot { it in ARRIVES_WITH_A_DECLARED_ARTIFACT }
      .toSet()

  /**
   * Whether [pkg] is named by any `libs.` alias in the build file.
   *
   * Matched on the **short** name (`androidx.recyclerview` -> `recyclerview`) because the
   * catalogue's aliases are not uniform: some carry the `androidx` prefix (`libs.androidx.core`)
   * and older ones do not (`libs.appcompat`, `libs.material`). Requiring one shape would flag
   * dependencies that are genuinely declared, which is worse than useless in a guard.
   */
  private fun isDeclared(
    pkg: String,
    buildFile: String,
  ): Boolean {
    val shortName = ALIAS_OVERRIDES[pkg] ?: pkg.substringAfterLast('.')
    return buildFile.contains("libs.$shortName)") ||
      buildFile.contains("libs.$shortName.") ||
      buildFile.contains(".$shortName)")
  }

  private companion object {
    const val SOURCE_ROOT = "src/main/java/io/github/mattpvaughn/chronicle"
    const val BUILD_FILE = "build.gradle.kts"

    /** `import androidx.core.view.isVisible` -> captures `androidx.core`. */
    val IMPORT = Regex("""^import (androidx\.[a-z0-9]+)\.""")

    /**
     * Aliases that do not contain their package's name.
     *
     * The catalogue grew organically and two entries predate the `androidx-` convention. They are
     * genuinely declared; only the lookup needs telling.
     */
    val ALIAS_OVERRIDES =
      mapOf(
        "androidx.browser" to "browserx",
        "androidx.swiperefreshlayout" to "swiperefresh",
      )

    /**
     * Packages that ship *inside* an artifact declared under a different name, so looking for
     * their own alias would fail while the dependency is genuinely explicit.
     *
     * - `androidx.arch.core` is `androidx.arch.core:core-testing`, declared as `libs.arch.core`.
     * - `androidx.test` is the test runner family, declared with `androidTestImplementation`.
     * - `androidx.annotation` ships in `androidx.annotation:annotation`, already declared.
     */
    val ARRIVES_WITH_A_DECLARED_ARTIFACT =
      setOf(
        "androidx.arch",
        "androidx.test",
        "androidx.databinding",
      )
  }
}

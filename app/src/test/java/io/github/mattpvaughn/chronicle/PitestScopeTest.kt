package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No Robolectric test may enter PIT's scope.
 *
 * PIT + Robolectric is broken upstream and unfixed (koral--/gradle-pitest-plugin#80, open since
 * 2022). The important word is *silently*: it does not error, it reports false SURVIVED and
 * NO_COVERAGE. A Robolectric class in scope therefore does not break the mutation run so much as
 * make it lie, and a lie from a tool whose entire job is to tell you which tests are worthless is
 * worse than no tool.
 *
 * The exclusion was once a hand-maintained list whose own comment warned that forgetting an entry
 * would produce exactly that silent lie. The warning held and the list still rotted: **62**
 * Robolectric classes had accumulated against **14** listed, one listed class no longer existed at
 * all, and `./gradlew pitestDebug` had been failing outright — "130 tests did not pass without
 * mutation" — for long enough that nobody noticed. `./verify.sh` stayed green throughout, because
 * nothing ran PIT.
 *
 * So the list is derived in `app/build.gradle.kts`, and this is the guard that the derivation still
 * matches reality. The lesson is the guard, not the list: a configuration invariant documented by a
 * comment is an invariant nothing checks.
 *
 * **It compares against what the build actually configured**, not against a re-derivation of its
 * own: `writePitestScope` writes the exact list handed to `excludedTestClasses`, and every unit
 * test task depends on it. A guard that re-implemented the scan would assert against itself and
 * pass while the build drifted.
 */
class PitestScopeTest {
  @Test
  fun `every Robolectric test is excluded from PIT's scope`() {
    val robolectric = robolectricTestClassesInSources()
    val excluded = configuredExclusion()

    val inScope = robolectric.filterNot { it in excluded }.sorted()

    assertEquals(
      "these Robolectric test classes are in PIT's scope. PIT + Robolectric fails *silently* " +
        "(koral--/gradle-pitest-plugin#80), reporting false SURVIVED/NO_COVERAGE — so this does " +
        "not break the mutation run, it makes it lie about which tests are worthless. The " +
        "exclusion is derived by robolectricTestClasses() in app/build.gradle.kts; if a class is " +
        "listed here, that derivation no longer matches how the test declares its runner.",
      emptyList<String>(),
      inScope,
    )
  }

  /**
   * The exclusion is *derived*, not typed out.
   *
   * The previous list passed every check that existed while being four years of drift out of date,
   * because the only thing asserting it was a comment. Reverting to literals would restore exactly
   * that, and the first test above would keep passing for as long as someone kept the literals
   * current — which is precisely the assumption that failed.
   */
  @Test
  fun `the exclusion is derived from the sources rather than hand-maintained`() {
    val build = File(BUILD_FILE).readText()

    assertTrue(
      "app/build.gradle.kts no longer sets excludedTestClasses from pitestRobolectricExclusion, " +
        "which is the value robolectricTestClasses() derives. A hand-maintained list is the " +
        "defect this replaced: it fell 48 classes behind before anyone noticed, and PIT's " +
        "failure mode makes falling behind invisible.",
      build.contains("excludedTestClasses.set(pitestRobolectricExclusion)"),
    )

    assertTrue(
      "pitestRobolectricExclusion is no longer the unmodified result of robolectricTestClasses(). " +
        "Filtering or truncating it puts Robolectric classes back into PIT's scope, where they " +
        "produce false SURVIVED/NO_COVERAGE rather than an error.",
      build.contains("val pitestRobolectricExclusion: List<String> = robolectricTestClasses()\n"),
    )

    val excludedBlock = build.substringAfter("excludedTestClasses.set(").substringBefore(")\n")
    assertFalse(
      "excludedTestClasses names a class literally. Derive it instead — the point is that " +
        "forgetting becomes impossible rather than merely documented.",
      excludedBlock.contains("io.github.mattpvaughn.chronicle"),
    )
  }

  /**
   * Guards the guard.
   *
   * Both assertions above are satisfied by scanning nothing: an empty source walk finds no
   * Robolectric classes, so none can be in scope. Given that a wrong path is exactly how the
   * original list decayed unnoticed, the counts are asserted rather than assumed.
   */
  @Test
  fun `the scan and the configured exclusion both resolve`() {
    assertTrue("cannot find $BUILD_FILE", File(BUILD_FILE).exists())
    assertTrue("cannot find $TEST_ROOT", File(TEST_ROOT).isDirectory)

    val robolectric = robolectricTestClassesInSources()
    assertTrue(
      "expected to find Robolectric test classes in $TEST_ROOT, found ${robolectric.size}. " +
        "Either the scan is looking in the wrong place or every Robolectric test was deleted.",
      robolectric.size > 40,
    )

    val excluded = configuredExclusion()
    assertTrue(
      "$SCOPE_FILE is empty. It is written by the writePitestScope task, which every unit test " +
        "task depends on; an empty file means the build derived an empty exclusion and PIT " +
        "would run the whole Robolectric suite.",
      excluded.isNotEmpty(),
    )
  }

  /**
   * Every class the build excluded, as written by `writePitestScope`.
   *
   * Read as a file rather than recomputed, so this test sees the build's real configuration.
   */
  private fun configuredExclusion(): Set<String> {
    val file = File(SCOPE_FILE)
    assertTrue(
      "$SCOPE_FILE is missing. It is produced by the :app:writePitestScope task, which every " +
        "unit test task depends on — if it is absent that wiring was removed, and this guard " +
        "can no longer see what the build configured.",
      file.exists(),
    )
    return file.readLines().filter { it.isNotBlank() }.toSet()
  }

  /**
   * Every unit-test class annotated `@RunWith(RobolectricTestRunner::class)`, as binary names.
   *
   * Independent of the build's derivation on purpose: this is the *question* (which tests are
   * Robolectric), and the build's list is the *answer under test*. Nested classes are emitted as
   * `Outer$Inner` — `ReauthenticationTest.AgainstTheRealImplementation` is a Robolectric class
   * inside a plain-JVM outer class, and the old list only caught it via a wildcard that also
   * excluded the outer class PIT could legitimately have used.
   */
  private fun robolectricTestClassesInSources(): List<String> =
    File(TEST_ROOT)
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .flatMap { source ->
        val lines = source.readLines()
        val pkg =
          lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            ?: return@flatMap emptySequence<String>()
        val enclosing = ArrayDeque<Pair<Int, String>>()
        val found = mutableListOf<String>()
        var pendingRobolectric = false
        for (line in lines) {
          if (RUN_WITH_ROBOLECTRIC.containsMatchIn(line)) {
            pendingRobolectric = true
            continue
          }
          val match = CLASS_DECLARATION.find(line) ?: continue
          val indent = match.groupValues[1].length
          val name = match.groupValues[2]
          while (enclosing.isNotEmpty() && enclosing.last().first >= indent) enclosing.removeLast()
          val binaryName = (enclosing.map { it.second } + name).joinToString("$")
          enclosing.addLast(indent to name)
          if (pendingRobolectric) {
            found += "$pkg.$binaryName"
            pendingRobolectric = false
          }
        }
        found.asSequence()
      }
      .sorted()
      .toList()

  private companion object {
    /** Unit tests run with the module directory as the working directory. */
    const val BUILD_FILE = "build.gradle.kts"
    const val TEST_ROOT = "src/test/java"
    const val SCOPE_FILE = "build/pitest-scope/robolectric-test-classes.txt"

    val RUN_WITH_ROBOLECTRIC = Regex("""@RunWith\(\s*RobolectricTestRunner::class\s*\)""")
    val CLASS_DECLARATION =
      Regex("""^(\s*)(?:(?:internal|private|abstract|open|sealed|data)\s+)*class\s+(\w+)""")
  }
}

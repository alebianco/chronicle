package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * detekt owns complexity, potential bugs and coroutines — never formatting, style or naming.
 *
 * That division of labour is the whole reason a second linter is tolerable here. ktlint already
 * gates formatting (`ktlintCheck`, and `format-kotlin.sh` after every Kotlin edit), and both tools
 * ship opinions about the same lines. Enable `style` or `naming` in detekt and the two start
 * disagreeing: ktlint's rule says wrap here, detekt's says do not, and no edit satisfies both. The
 * build becomes unfixable, and the way that gets resolved in practice is by deleting a stage.
 *
 * The failure this guards against is *quiet*. Nobody turns on `style` on purpose; it arrives by
 * someone regenerating `detekt.yml` from detekt's defaults, or by a version bump changing what
 * `buildUponDefaultConfig` inherits. Neither breaks anything visibly — the next person just finds
 * the linter unbearable and switches the stage off. So the on/off state of each rule set is pinned
 * here, where changing it means changing this test on purpose.
 *
 * It also pins the two configuration facts that would make the gate **pass vacuously**, which is
 * this project's most-feared failure mode because it is indistinguishable from success:
 *
 *  - The gate is `detektDebug`, not the bare `detekt` task. Half the potential-bugs set needs type
 *    resolution — `UnsafeCallOnNullableType` and `ElseCaseInsteadOfExhaustiveWhen` among them — and
 *    without a classpath those rules do not report false negatives, they report *nothing*. The
 *    measured difference on this codebase was 31 findings without type resolution against 120 with.
 *  - `config.validation` stays on, so a rule id that detekt does not recognise fails the run
 *    instead of being ignored. A misspelled rule is a rule that silently does nothing.
 */
class DetektRuleSetTest {
  private val configFile = File("../config/detekt/detekt.yml")
  private val baselineFile = File("../config/detekt/baseline-debug.xml")
  private val appBuildFile = File("build.gradle.kts")
  private val verifyScript = File("../verify.sh")

  private val config by lazy { configFile.readText() }

  /**
   * Reads the `active:` flag of a top-level rule-set block.
   *
   * Deliberately crude — it takes the block from its zero-indented header to the next zero-indented
   * line and finds the first `active:` inside. That is enough for the shape of this file and avoids
   * pulling a YAML parser into the unit suite for one assertion, but it does mean the test reads
   * the *first* `active:` in the block, which is the rule set's own by construction.
   */
  private fun ruleSetActive(name: String): Boolean? {
    val lines = config.lines()
    val start = lines.indexOfFirst { it.trimEnd() == "$name:" }
    if (start < 0) return null
    val body =
      lines
        .drop(start + 1)
        .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("#") }
    val active = body.firstOrNull { it.trim().startsWith("active:") } ?: return null
    return active.substringAfter("active:").trim().toBoolean()
  }

  @Test
  fun `the detekt config exists where the build points at it`() {
    assertTrue(
      "config/detekt/detekt.yml is missing. app/build.gradle.kts sets config.setFrom to it, and " +
        "a missing config does not fail the build — detekt falls back to its defaults, which " +
        "means every rule set ktlint owns switches itself back on.",
      configFile.isFile,
    )
  }

  @Test
  fun `detekt enables exactly the three rule sets this project wants from it`() {
    val enabled = listOf("complexity", "potential-bugs", "coroutines")
    val notEnabled = enabled.filter { ruleSetActive(it) != true }

    assertEquals(
      "these rule sets should be active in config/detekt/detekt.yml. They are what detekt is here " +
        "for: complexity (which the maintainability review had to measure by hand), potential " +
        "bugs (defects rather than opinions), and coroutines (the GlobalScope ban that " +
        "convention 4 states in prose).",
      emptyList<String>(),
      notEnabled,
    )
  }

  @Test
  fun `detekt does not enable a rule set ktlint owns`() {
    val ktlintsTerritory = listOf("style", "naming", "comments")
    val leaking = ktlintsTerritory.filter { ruleSetActive(it) != false }

    assertEquals(
      "these rule sets must be explicitly disabled in config/detekt/detekt.yml. ktlint owns " +
        "formatting, style and naming; running both linters on the same concern produces advice " +
        "that contradicts itself, and a build no edit can satisfy is a build someone switches off.",
      emptyList<String>(),
      leaking,
    )
  }

  @Test
  fun `the formatting rule set is absent rather than disabled`() {
    // `formatting` lives in the separate detekt-formatting artifact, which wraps ktlint's own
    // rules. It is not on the classpath, and naming it here would fail config validation outright
    // ("Property 'formatting' is misspelled or does not exist") — so absence is both the stronger
    // guarantee and the only thing that parses.
    assertTrue(
      "config/detekt/detekt.yml must not declare a `formatting:` block: that rule set ships in " +
        "detekt-formatting, which is deliberately not a dependency, and naming it fails detekt's " +
        "own config validation.",
      config.lines().none { it.trimEnd() == "formatting:" },
    )
  }

  @Test
  fun `config validation is on, so a misspelled rule cannot silently do nothing`() {
    assertTrue(
      "config/detekt/detekt.yml must set `validation: true`. Without it a typo in a rule id is " +
        "ignored rather than reported, and the rule quietly never runs — a gate that is not a gate.",
      Regex("""^\s*validation:\s*true\s*$""", RegexOption.MULTILINE).containsMatchIn(config),
    )
  }

  @Test
  fun `new findings fail the build`() {
    assertTrue(
      "config/detekt/detekt.yml must set `maxIssues: 0`. The baseline already absorbs every " +
        "finding that exists today, so this threshold counts *new* ones only — any value above " +
        "zero buys tolerance for defects that have not been written yet.",
      Regex("""^\s*maxIssues:\s*0\s*$""", RegexOption.MULTILINE).containsMatchIn(config),
    )
  }

  @Test
  fun `a baseline is committed, so the stage fails only on new findings`() {
    assertTrue(
      "config/detekt/baseline-debug.xml is missing. Without it detekt reports the whole existing " +
        "backlog on the first run and blocks every build, which is how a linter gets deleted " +
        "rather than fixed. Regenerate with ./gradlew :app:detektBaselineDebug.",
      baselineFile.isFile,
    )
    assertTrue(
      "config/detekt/baseline-debug.xml has no <ID> entries. An empty baseline against a " +
        "codebase that had findings means the baseline was written from a run that analysed " +
        "nothing — check that detektBaselineDebug, not detektBaseline, produced it.",
      baselineFile.readText().contains("<ID>"),
    )
  }

  @Test
  fun `the build points detekt at the committed config and baseline`() {
    val build = appBuildFile.readText()
    assertTrue(
      "app/build.gradle.kts must point detekt's config at config/detekt/detekt.yml.",
      build.contains("config/detekt/detekt.yml"),
    )
    assertTrue(
      "app/build.gradle.kts must set detekt's baseline to config/detekt/baseline.xml — the " +
        "variant tasks insert `-debug` themselves, so this is the un-suffixed name.",
      build.contains("config/detekt/baseline.xml"),
    )
    assertTrue(
      "app/build.gradle.kts must pin detekt's jvmTarget. Left unset, 1.23.x takes the JVM target " +
        "of whichever daemon is running, so the same analysis gives different answers on " +
        "different machines.",
      Regex("""jvmTarget\s*=\s*"17"""").containsMatchIn(build),
    )
  }

  @Test
  fun `verify_sh runs the type-resolving detekt task, not the bare one`() {
    val verify = verifyScript.readText()

    assertTrue(
      "verify.sh must run `:app:detektDebug`. The bare `detekt` task runs without a classpath, " +
        "and the rules this project wants most — UnsafeCallOnNullableType, " +
        "ElseCaseInsteadOfExhaustiveWhen, IgnoredReturnValue — need type resolution to decide " +
        "anything. Without it they report nothing at all, which looks exactly like a clean tree.",
      verify.contains(":app:detektDebug"),
    )

    // Guard the substring, not just the presence: `detektDebug` contains `detekt`, so a naive
    // check for the task name would pass on a downgrade to the classpath-free task.
    val runsBareDetekt =
      Regex("""GRADLE"?\s+detekt\s*$""", RegexOption.MULTILINE).containsMatchIn(verify)
    assertTrue(
      "verify.sh must not run the bare `detekt` task — it analyses without type resolution and " +
        "silently skips half the potential-bugs rule set.",
      !runsBareDetekt,
    )
  }
}

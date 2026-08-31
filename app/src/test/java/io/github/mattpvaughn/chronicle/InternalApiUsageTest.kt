package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `@InternalCoroutinesApi` is not a stability opt-in like `@ExperimentalCoroutinesApi`.
 * It marks kotlinx internals that may change or disappear in any release with no
 * deprecation cycle, so an upgrade can break the build — or worse, behaviour — with
 * no warning.
 *
 * Nothing here needs them; the annotations were vestigial. This keeps them out.
 */
class InternalApiUsageTest {
  @Test
  fun `no production source opts into InternalCoroutinesApi`() {
    val offenders =
      File(MAIN_SOURCE_ROOT)
        .walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.readText().contains("InternalCoroutinesApi") }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals(
      "InternalCoroutinesApi exposes kotlinx internals with no compatibility guarantee",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * Guards the guard: if the path is ever wrong, the test above would pass by
   * walking an empty tree and prove nothing.
   */
  @Test
  fun `source root resolves and contains kotlin files`() {
    val kotlinFileCount =
      File(MAIN_SOURCE_ROOT).walkTopDown().count { it.extension == "kt" }

    assertEquals(
      "expected the main source root to resolve; a wrong path would make the " +
        "InternalCoroutinesApi check vacuous",
      true,
      kotlinFileCount > 100,
    )
  }

  private companion object {
    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    const val MAIN_SOURCE_ROOT = "src/main/java"
  }
}

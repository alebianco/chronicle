package io.github.mattpvaughn.chronicle.injection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every provider in [ServiceModule] must be scoped.
 *
 * An unscoped `@Provides` hands out a fresh instance per injection point, which for
 * stateful collaborators means two objects that each think they are the only one.
 * `provideProgressUpdater` was the sole offender: `MediaPlayerService` and
 * `AudiobookMediaSessionCallback` each got their own `SimpleProgressUpdater` with its own
 * `handler` and `tickCounter`, so the tick count gating network reports
 * (`tickCounter % NETWORK_CALL_FREQUENCY`) advanced independently in each, and `cancel()`
 * on one could not stop the other's pending `postDelayed`.
 *
 * Written as a sweep rather than a single assertion so the next unscoped provider is
 * caught when it is added, not after it causes a bug.
 */
class ServiceModuleScopeTest {
  private val source =
    java.io.File("src/main/java/io/github/mattpvaughn/chronicle/injection/modules/ServiceModule.kt")
      .readText()

  /**
   * A **source** scan, not reflection.
   *
   * The old custom `@ServiceScope` was `RUNTIME`-retained, so `isAnnotationPresent` could see it.
   * Hilt's `@ServiceScoped` is `CLASS`-retained and therefore invisible to reflection — a
   * reflective sweep reports every provider unscoped, which is exactly the false alarm that
   * replaced this. Reading the file is cruder but it can actually fail for the right reason.
   */
  @Test
  fun `every provider in ServiceModule is scoped`() {
    val providers = Regex("""@Provides\n(?<rest>(?:\s*@[^\n]*\n)*)\s*fun (?<name>\w+)""")
    val unscoped =
      providers.findAll(source)
        .filterNot { it.groups["rest"]!!.value.contains("@ServiceScoped") }
        .map { it.groups["name"]!!.value }
        .sorted()
        .toList()

    assertEquals(
      "an unscoped provider hands out a fresh instance to each injection point",
      emptyList<String>(),
      unscoped,
    )
  }

  /** Guards the guard: a regex matching nothing would pass vacuously. */
  @Test
  fun `the sweep actually finds providers`() {
    val providerCount = Regex("""@Provides""").findAll(source).count()

    assertEquals(
      "expected ServiceModule to expose providers; zero means the scan is wrong",
      true,
      providerCount > 10,
    )
  }
}

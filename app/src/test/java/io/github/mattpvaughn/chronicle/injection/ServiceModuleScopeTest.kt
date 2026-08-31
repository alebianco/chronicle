package io.github.mattpvaughn.chronicle.injection

import dagger.Provides
import io.github.mattpvaughn.chronicle.injection.modules.ServiceModule
import io.github.mattpvaughn.chronicle.injection.scopes.ServiceScope
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
@OptIn(kotlin.time.ExperimentalTime::class)
class ServiceModuleScopeTest {
  @Test
  fun `every provider in ServiceModule is scoped`() {
    val unscoped =
      ServiceModule::class.java.declaredMethods
        .filter { it.isAnnotationPresent(Provides::class.java) }
        .filterNot { it.isAnnotationPresent(ServiceScope::class.java) }
        .map { it.name }
        .sorted()

    assertEquals(
      "an unscoped provider hands out a fresh instance to each injection point",
      emptyList<String>(),
      unscoped,
    )
  }

  /** Guards the guard: reflection finding no providers at all would pass vacuously. */
  @Test
  fun `the sweep actually finds providers`() {
    val providerCount =
      ServiceModule::class.java.declaredMethods
        .count { it.isAnnotationPresent(Provides::class.java) }

    assertEquals(
      "expected ServiceModule to expose providers; zero means the reflection is wrong",
      true,
      providerCount > 10,
    )
  }
}

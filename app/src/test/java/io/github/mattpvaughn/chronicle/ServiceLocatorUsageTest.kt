package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `Injector.get()` is a service locator, and the carve in cu-33 took it out of everything that can
 * do without it. This keeps it out.
 *
 * **Why it matters, concretely.** `ChronicleApplication.get()` is `INSTANCE!!`, so a class that
 * fetches its own dependencies at runtime cannot be constructed in a unit test at all — the first
 * line that reaches the locator throws NPE. The correlation was exact: nine of the twelve
 * ViewModels had no tests, and they were the nine that called it. `features/settings`,
 * `features/login` and `features/collections` sat at 0% together, 9,624 missed instructions.
 *
 * The count went 66 call sites across 29 files to the [EXEMPT] handful below.
 */
class ServiceLocatorUsageTest {
  private val sourceDir = File(MAIN_SOURCE_ROOT)

  private fun locatorCallsIn(file: File): Int =
    file.readLines()
      .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
      .count { it.contains("Injector.get()") }

  private fun offenders(): Map<String, Int> =
    sourceDir.walkTopDown()
      .filter { it.extension == "kt" }
      .filter { it.name !in EXEMPT }
      .map { it.name to locatorCallsIn(it) }
      .filter { it.second > 0 }
      .toMap()

  @Test
  fun `nothing outside the exemption list reaches the service locator`() {
    assertEquals(
      "a class started fetching its own dependencies at runtime. Take them as constructor " +
        "parameters instead — a class that calls Injector.get() cannot be built in a unit test, " +
        "which is what kept nine ViewModels untested until cu-33.",
      emptyMap<String, Int>(),
      offenders(),
    )
  }

  /**
   * Every exempted file still exists and still uses the locator.
   *
   * Without this the list rots into a wishlist: a file that was renamed, or cleaned up by some
   * later change, would sit here forever implying an exemption that is no longer needed — and the
   * next person adding a worker would copy it.
   */
  @Test
  fun `every exempted file exists and still needs its exemption`() {
    val found =
      sourceDir.walkTopDown()
        .filter { it.extension == "kt" && it.name in EXEMPT }
        .filter { locatorCallsIn(it) > 0 }
        .map { it.name }
        .toSet()

    assertEquals(
      "an exemption is stale: the file was renamed, or no longer calls Injector.get() and " +
        "should come off this list.",
      EXEMPT,
      found,
    )
  }

  /**
   * The worker exemptions really are workers.
   *
   * The reason they are exempt is specific to `CoroutineWorker` — WorkManager constructs one
   * reflectively with a fixed `(Context, WorkerParameters)` signature, so a constructor cannot take
   * dependencies without a `WorkerFactory` and a `Configuration.Provider` (the same reasoning that
   * exempted them from `DispatcherProvider` in cu-152, pinned by `WorkerDispatcherTest`). If a
   * non-worker were added to the list it would inherit a justification that does not apply to it.
   */
  @Test
  fun `every exempted worker is actually a CoroutineWorker`() {
    val notWorkers =
      sourceDir.walkTopDown()
        .filter { it.extension == "kt" && it.name in EXEMPT_WORKERS }
        .filterNot { it.readText().contains(": CoroutineWorker(") }
        .map { it.name }
        .toList()

    assertEquals("exempted as a worker but not a CoroutineWorker", emptyList<String>(), notWorkers)
  }

  /** Guards the guard: a wrong path would walk an empty tree and prove nothing. */
  @Test
  fun `source root resolves and contains kotlin files`() {
    assertTrue(
      "expected the main source root to resolve; a wrong path makes every check above vacuous",
      sourceDir.walkTopDown().count { it.extension == "kt" } > 100,
    )
  }

  private companion object {
    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    const val MAIN_SOURCE_ROOT = "src/main/java"

    /**
     * Workers, exempt for the cu-152 reason: WorkManager builds them reflectively with a fixed
     * `(Context, WorkerParameters)` signature, so they have no constructor to inject into. Adding
     * a `WorkerFactory` would buy nothing while no worker is unit-tested.
     */
    val EXEMPT_WORKERS =
      setOf(
        "DownloadNotificationWorker.kt",
        "MoveSyncLocationWorker.kt",
        "PlexSyncScrobbleWorker.kt",
      )

    /**
     * [EXEMPT_WORKERS] plus the DI root itself.
     *
     * `ChronicleApplication` *is* where the graph is built, so reaching it there is not a service
     * locator call in the sense this guard is about — and its one use is deliberately a lazy
     * lambda (`callFactory = { … }`), because resolving the OkHttp client eagerly while
     * constructing the image loader would close a construction cycle.
     */
    val EXEMPT = EXEMPT_WORKERS + "ChronicleApplication.kt"
  }
}

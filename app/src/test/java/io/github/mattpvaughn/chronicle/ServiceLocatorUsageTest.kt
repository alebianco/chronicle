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
     * Workers still reaching the locator — **none, as of cu-185.**
     *
     * The list is kept empty rather than deleted: it is the record of a rule that took three
     * passes to land, and an emptied exemption set is the outcome, not an absence.
     *
     * cu-152 exempted **all** workers, reasoning that WorkManager builds them reflectively through
     * a fixed `(Context, WorkerParameters)` signature so they have no constructor to inject into,
     * and that a `WorkerFactory` "would buy nothing while no worker is unit-tested".
     *
     * cu-179 re-decided that on new facts — `androidx.work:work-testing` was already in the build
     * and unused, and the workers were among the largest untested bodies left — and converted two
     * of the three through a hand-written `ChronicleWorkerFactory`.
     *
     * cu-185 finished it. `@HiltWorker` gives each worker an ordinary `@Inject` constructor and
     * `HiltWorkerFactory` builds them, so the factory we maintained is gone. The last holdout was
     * `DownloadNotificationWorker`'s **companion** `enqueue` helper, which reached the locator for
     * a `WorkManager` to schedule itself; it takes a `Context` now, passed by the one caller,
     * which was already constructor-injected.
     */
    val EXEMPT_WORKERS = emptySet<String>()

    /**
     * **Empty since cu-185** — there is no `Injector` left to exempt anything from.
     *
     * `ChronicleApplication` was the last entry: it *was* where the graph was built, so reaching
     * the locator there was not the thing this guard is about. `@HiltAndroidApp` builds the graph
     * now, and the one call the exemption covered (a lazy `callFactory = { … }`, deliberately
     * lazy so resolving the OkHttp client would not close a construction cycle while the image
     * loader was being built) goes through a narrow `@EntryPoint` instead.
     *
     * The guard itself stays. `Injector` is deleted, but the *pattern* — a class fetching its own
     * dependencies at runtime instead of taking them as constructor parameters — is what cu-33
     * banned, and a new one could be written tomorrow.
     */
    val EXEMPT = EXEMPT_WORKERS
  }
}

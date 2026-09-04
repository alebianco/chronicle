package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `postValue` is banned in migrated files, and this is the **only** mechanism that can enforce it
 * (cu-52).
 *
 * `postValue` is asynchronous and coalescing: a read-after-write sees a stale value, and two posts
 * in one main-loop pass collapse into one. Three of the fifteen device-only bugs in cu-73 had that
 * shape, and the mini-player one was fixed by turning seven `postValue` calls into `value =`.
 *
 * **A unit test cannot catch this.** `InstantTaskExecutorRule` swaps in an `ArchTaskExecutor` that
 * runs everything on the calling thread, which makes `postValue` synchronous *in tests* — verified
 * directly: `postValue(42)` followed by `assertEquals(42, value)` passes under the rule. So the
 * race is invisible to the JVM suite by construction, and a source-level gate is what remains.
 *
 * The list grows as files are migrated. It is an **allowlist of the clean**, not a blocklist of the
 * dirty, so a newly migrated file has to be added deliberately and a regression in one already
 * migrated fails the build.
 */
class PostValueUsageTest {
  private val sourceDir = File(MAIN_SOURCE_ROOT)

  /**
   * `postValue` calls in [file] that are **not** marked as deliberately asynchronous.
   *
   * Per *site*, not per file: `HomeViewModel` is migrated except for one listener that genuinely
   * runs off the main thread, and a file-level allowlist could not express that — it would have to
   * exempt the whole file and stop guarding the other two sites.
   */
  private fun unmarkedPostValueCallsIn(file: File): List<String> {
    val lines = file.readLines()
    return lines.withIndex()
      .filterNot { (_, l) -> l.trimStart().startsWith("*") || l.trimStart().startsWith("//") }
      .filter { (_, l) -> l.contains(".postValue(") }
      .filterNot { (i, _) ->
        // The preceding comment block must say so explicitly. Looking back a few lines rather than
        // one keeps a multi-line explanation working, which these always need.
        (maxOf(0, i - 6) until i).any { lines[it].contains(ASYNC_MARKER) }
      }
      .map { (i, l) -> "${file.name}:${i + 1} ${l.trim()}" }
  }

  @Test
  fun `migrated files do not call postValue`() {
    val offenders =
      MIGRATED
        .mapNotNull { name -> sourceDir.walkTopDown().firstOrNull { it.name == name } }
        .flatMap { unmarkedPostValueCallsIn(it) }
        .sorted()

    assertEquals(
      "a migrated file called postValue without justifying it. postValue is asynchronous and " +
        "coalescing, so a read-after-write sees a stale value — the shape of three device races " +
        "in cu-73. Use `value =` on the main thread; if the call really is off-main, say so in a " +
        "comment containing \"$ASYNC_MARKER\" directly above it.",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * Every allowlisted file exists.
   *
   * Without this the list rots into a wishlist: a renamed or deleted file would sit here forever
   * implying a guarantee about nothing, and the check above would pass by looking at no file at
   * all.
   */
  @Test
  fun `every migrated file named here exists`() {
    val missing = MIGRATED.filterNot { name -> sourceDir.walkTopDown().any { it.name == name } }

    assertEquals("a migrated file was renamed or removed", emptyList<String>(), missing)
  }

  /** Guards the guard: a wrong path would walk an empty tree and prove nothing. */
  @Test
  fun `source root resolves and contains kotlin files`() {
    assertTrue(
      "expected the main source root to resolve",
      sourceDir.walkTopDown().count { it.extension == "kt" } > 100,
    )
  }

  private companion object {
    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    const val MAIN_SOURCE_ROOT = "src/main/java"

    /**
     * The marker a deliberate `postValue` must carry, in a comment directly above the call.
     *
     * Deliberately wordy: it should be easier to convert the call than to silence the check by
     * accident.
     */
    const val ASYNC_MARKER = "Stays `postValue`"

    /**
     * Files whose `postValue` calls have been reviewed and converted.
     *
     * `HomeViewModel` keeps **one** deliberate `postValue`, on its `SharedPreferences` listener: that
     * fires on whichever thread called `apply()`, and a settings *import* writes `KEY_OFFLINE_MODE`
     * off the main thread, where `value =` would throw. It is excluded from this list for that
     * reason rather than being an oversight — a file is only added once *every* site in it is either
     * converted or documented as necessarily asynchronous.
     */
    val MIGRATED = setOf("HomeViewModel.kt")
  }
}

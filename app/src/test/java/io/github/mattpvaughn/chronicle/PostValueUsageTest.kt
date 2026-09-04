package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `postValue` is banned outright, and this is the **only** mechanism that can enforce it (cu-52).
 *
 * `postValue` is asynchronous and coalescing: a read-after-write sees a stale value, and two posts
 * in one main-loop pass collapse into one. Three of the fifteen device-only bugs in cu-73 had that
 * shape, and `MediaServiceConnection.connectIfIdle` documents a crash caused by exactly it — a
 * second `connect()` inside the deferral window reached `MediaBrowserCompat.connect()`, which
 * throws rather than ignoring a redundant call.
 *
 * **A unit test cannot catch this.** `InstantTaskExecutorRule` swaps in an `ArchTaskExecutor` that
 * runs everything on the calling thread, which makes `postValue` synchronous *in tests* — verified
 * directly: `postValue(42)` followed by `assertEquals(42, value)` passes under the rule. So the
 * race is invisible to the JVM suite by construction, and a source-level gate is what remains.
 *
 * The migration is complete, so this is a **blanket ban** rather than the allowlist it started as:
 * every state holder in the app is a `MutableStateFlow`, whose assignment is thread-safe *and*
 * lands immediately, which is what removed the last legitimate reason to reach for `postValue` —
 * publishing from a `SharedPreferences` listener or a `BroadcastReceiver` callback that may not be
 * on the main thread. Reintroducing a `LiveData` is what would make this fire.
 */
class PostValueUsageTest {
  private val sourceDirs = SOURCE_ROOTS.map(::File)

  @Test
  fun `no source file calls postValue`() {
    val offenders =
      sourceDirs
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" } }
        .flatMap { file ->
          file
            .readLines()
            .withIndex()
            .filterNot { (_, l) -> l.trimStart().startsWith("*") || l.trimStart().startsWith("//") }
            .filter { (_, l) -> l.contains(".postValue(") }
            .map { (i, l) -> "${file.name}:${i + 1} ${l.trim()}" }
        }.sorted()

    assertEquals(
      "postValue is asynchronous and coalescing, so a read-after-write sees a stale value — the " +
        "shape of three device races in cu-73 and of the connect() crash MediaServiceConnection " +
        "documents. Use a MutableStateFlow: its assignment is thread-safe and lands immediately, " +
        "so an off-main-thread publish needs no deferral either.",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: a wrong path would walk an empty tree and prove nothing. */
  @Test
  fun `every source root resolves and contains kotlin files`() {
    sourceDirs.forEach { dir ->
      assertTrue(
        "expected ${dir.absolutePath} to resolve to Kotlin sources",
        dir.walkTopDown().any { it.extension == "kt" },
      )
    }
  }

  private companion object {
    /**
     * Relative to the `app` module dir, which is the unit tests' working directory.
     *
     * All three variants, not just `main`: the debug and release source sets each carry their own
     * `DebugHooks`, and a drifted twin there is exactly the class of thing that passes every
     * debug-only check (the cu-70 shape).
     */
    val SOURCE_ROOTS = listOf("src/main/java", "src/debug/java", "src/release/java")
  }
}

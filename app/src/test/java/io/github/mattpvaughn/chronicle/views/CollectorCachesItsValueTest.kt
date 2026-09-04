package io.github.mattpvaughn.chronicle.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `collectWhileStarted` that discards its emission does not leave a stale local behind (cu-52).
 *
 * The bug this exists for, found on the device and by nothing else: `LibraryFragment` combined
 * several sources into one `refreshEmptyStates()`, kept the latest of each in a local, and
 * collected like this —
 *
 * ```
 * var latestBooks: List<Audiobook> = emptyList()
 * viewLifecycleOwner.collectWhileStarted(viewModel.books) { refreshEmptyStates() }
 * ```
 *
 * The lambda ignores its parameter, so `latestBooks` stayed `emptyList()` for the life of the
 * screen and the library rendered **"No books found"** over a full library. It compiles, every
 * unit test passed, and Home was fine because it reads `.value` instead of caching.
 *
 * A source guard rather than a behaviour test, for the same reason `FirstFrameFlashTest` is one:
 * the failure is *structural*. Nothing that inspects a laid-out view can see the difference between
 * "the flow has not emitted" and "the emission was thrown away", and the ViewModel is correct in
 * both cases — the defect lives entirely in the wiring.
 *
 * The rule: a fragment that declares a `latest*` local **must** assign it inside a collector.
 * Reading `.value` off the `StateFlow` instead is fine and is what the other screens do; this only
 * fires when the caching pattern is used and left half-wired.
 */
class CollectorCachesItsValueTest {
  private val sourceDir = File(SOURCE_ROOT)

  private data class Unassigned(
    val file: String,
    val local: String,
  )

  /**
   * Locals matching `var latest<Something>` that are never assigned outside their declaration.
   *
   * Deliberately keyed on the `latest` prefix rather than on flow analysis: the convention is what
   * makes the intent legible, and a local named `latestBooks` that nothing writes is unambiguous.
   */
  private fun unassignedCacheLocals(file: File): List<Unassigned> {
    val text = file.readText()
    return DECLARATION.findAll(text)
      .map { it.groupValues[1] }
      .filterNot { local ->
        // An assignment that is *not* the declaration: `latestBooks = it`, not `var latestBooks =`.
        Regex("""(?<!var )\b${Regex.escape(local)}\s*=[^=]""").containsMatchIn(text)
      }.map { Unassigned(file.name, it) }
      .toList()
  }

  @Test
  fun `a cached collector local is actually assigned`() {
    val offenders =
      sourceDir
        .walkTopDown()
        .filter { it.extension == "kt" }
        .flatMap { unassignedCacheLocals(it) }
        .map { "${it.file}: `${it.local}` is declared but never assigned" }
        .sorted()
        .toList()

    assertEquals(
      "a `latest*` local is the cached value of a collected flow, so a collector has to write to " +
        "it — `collectWhileStarted(flow) { latestX = it; refresh() }`, not `{ refresh() }`. " +
        "Discarding the emission leaves the local on its initial value forever, which is how the " +
        "library screen rendered \"No books found\" over a full library.",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: a wrong path would walk an empty tree and prove nothing. */
  @Test
  fun `the source root resolves and the pattern matches a real declaration`() {
    assertTrue(
      "expected the main source root to resolve",
      sourceDir.walkTopDown().count { it.extension == "kt" } > 100,
    )
    assertTrue(
      "the declaration pattern must match the shape it is written for",
      DECLARATION.containsMatchIn("    var latestBooks: List<Audiobook> = emptyList()"),
    )
  }

  private companion object {
    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    const val SOURCE_ROOT = "src/main/java/io/github/mattpvaughn/chronicle"

    /** `var latestBooks: List<Audiobook> = emptyList()` -> captures `latestBooks`. */
    val DECLARATION = Regex("""\bvar\s+(latest[A-Z]\w*)\s*[:=]""")
  }
}

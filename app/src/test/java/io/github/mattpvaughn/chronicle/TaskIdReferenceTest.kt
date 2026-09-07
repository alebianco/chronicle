package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No source comment cites a backlog task id.
 *
 * ## Why an id in a comment is worse than no id
 *
 * A `cu-NN` reference looks like a citation and behaves like one for about a month. Then the task
 * closes, `backlog task complete` moves it to `backlog/completed/`, and `backlog search` stops
 * indexing it — so the reference resolves to nothing for the reader most likely to need it. Nothing
 * in the build ever checked that the id existed, matched the code beside it, or still described
 * what the code does; a renumbered, split or abandoned task leaves the comment confidently wrong.
 *
 * The rule that replaces it: **a comment carries the reasoning, not a pointer to where the
 * reasoning was once discussed.** If a measurement justifies the code, state the measurement. If a
 * bug shaped it, describe the bug. Both survive archival, a repo move and a reader with no backlog
 * access — and neither can silently rot, because a wrong explanation beside live code is visible
 * where a dead ticket number is not.
 *
 * ## What is still allowed
 *
 * - **`decision-NN`** and the `D1`–`D14` product decisions. Those are owner-level records that do
 *   not close, get renumbered or leave the repo, and several comments genuinely turn on "this is a
 *   product decision, not an engineering one".
 * - **Upstream issue links** (`mattttvaughn/chronicle#83`) — real, resolvable URLs.
 * - **`Task: cu-NN` trailers in commit messages**, which this does not scan. A commit is a
 *   historical record: it cannot rot, because it describes the state at the moment it was written.
 * - The placeholder spellings `cu-NN` and `cu-<n>` in documentation that teaches the workflow.
 *
 * The scan is deliberately source-only. `backlog/` cites task ids constantly and should — the
 * tasks live next door.
 */
class TaskIdReferenceTest {
  private val sourceDirs = SOURCE_ROOTS.map(::File).filter { it.isDirectory }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches the app's sources`() {
    val scanned = sourceDirs.sumOf { dir -> dir.walkTopDown().count { it.extension == "kt" } }

    assertTrue("expected to scan the app's Kotlin sources, saw $scanned", scanned > 400)
  }

  @Test
  fun `no comment cites a task id`() {
    val offenders =
      sourceDirs
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" } }
        // This file carries task ids on purpose: its fixtures are the strings the matcher must
        // recognise. Exempting it by name rather than by a marker comment, so the exemption is
        // one line and cannot spread.
        .filterNot { it.name == "TaskIdReferenceTest.kt" }
        .flatMap { file ->
          file.readLines().withIndex().mapNotNull { (index, line) ->
            "${file.name}:${index + 1}".takeIf { TASK_ID.containsMatchIn(line) }
          }
        }
        .sorted()

    assertEquals(
      "a task id in a comment is a citation that stops resolving: the task is archived out of " +
        "`backlog search`, and nothing here ever checked the id existed or still described the " +
        "code beside it. State the reasoning instead — the measurement, or the bug's shape. " +
        "`decision-NN` and upstream issue links are exempt and remain useful.",
      emptyList<String>(),
      offenders,
    )
  }

  /** And that the matcher can actually fire, on both spellings that appeared in this codebase. */
  @Test
  fun `the matcher detects the forms it bans`() {
    assertTrue(TASK_ID.containsMatchIn("   * Expressed for Compose (cu-181)."))
    assertTrue(TASK_ID.containsMatchIn("  // the churn cu-93 measured at 228 recomputations"))
    assertTrue(TASK_ID.containsMatchIn("   * the ingestion seam is real since cu-33.1"))
  }

  /** A `decision-NN` reference, an upstream issue and ordinary prose must not trip it. */
  @Test
  fun `exempt references are not violations`() {
    assertTrue(!TASK_ID.containsMatchIn("   * Listening position is owned by the tracks (decision-16)."))
    assertTrue(!TASK_ID.containsMatchIn("   * upstream's OOM in mattttvaughn/chronicle#83"))
    assertTrue(!TASK_ID.containsMatchIn("   * D12 rule 6 puts CI logic in verify.sh"))
    // A hyphenated identifier that merely ends in the pattern is not a task id.
    assertTrue(!TASK_ID.containsMatchIn("  const val CACHE_KEY = \"thumb-cu-1234\""))
  }

  /**
   * A task id inside a string literal is still a violation.
   *
   * The scan reads whole lines rather than comment-only lines deliberately: an id has no business
   * in code either, and scanning everything means a reference cannot dodge the guard by moving
   * into a string.
   */
  @Test
  fun `a task id in code is not exempt`() {
    assertTrue(TASK_ID.containsMatchIn("  val note = \"see cu-206 for why\""))
  }

  private companion object {
    /**
     * A backlog task id: `cu-` then digits, optionally `.n` for a split task.
     *
     * Word-bounded at the front so a longer identifier that merely ends in those characters — a
     * URL, an id in a string literal — cannot match. The check runs over whole lines rather than
     * comment-only lines on purpose: a task id has no business in code either, and scanning
     * everything means a violation cannot hide by moving into a string.
     */
    val TASK_ID = Regex("""(?<![\w-])cu-\d+(\.\d+)?\b""")

    val SOURCE_ROOTS =
      listOf(
        "src/main/java/io/github/mattpvaughn/chronicle",
        "src/test/java/io/github/mattpvaughn/chronicle",
        "src/debug/java/io/github/mattpvaughn/chronicle",
        "src/release/java/io/github/mattpvaughn/chronicle",
        "src/androidTest/java/io/github/mattpvaughn/chronicle",
      )
  }
}

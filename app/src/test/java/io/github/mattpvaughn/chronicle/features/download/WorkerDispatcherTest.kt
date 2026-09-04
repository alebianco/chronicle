package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `CoroutineWorker` is the one place `Dispatchers.*` is allowed directly (cu-152).
 *
 * Convention 4 says inject a `DispatcherProvider` rather than reference `Dispatchers.*`, and
 * `RepositoryDispatcherTest` enforces it for repositories. The workers are a **deliberate
 * exemption**, and this pins both halves of that: which files are exempt, and that no *new* file
 * quietly joins them.
 *
 * **Why they are exempt.** WorkManager constructs a worker reflectively through its default
 * factory, with a fixed `(Context, WorkerParameters)` signature — so a constructor cannot take a
 * `DispatcherProvider` at all without adding a `WorkerFactory` and a `Configuration.Provider`. The
 * convention's own purpose is "so a test can control it" (`RepositoryDispatcherTest`'s wording),
 * and that plumbing would buy nothing today: no worker is unit-tested, and WorkManager's own
 * harness, `TestListenableWorkerBuilder`, supplies its own executor regardless.
 *
 * **And the calls are correct.** `CoroutineWorker.doWork` runs on `Dispatchers.Default`; both
 * remaining uses wrap genuine blocking file I/O (moving downloaded files between directories,
 * polling Fetch's download state), which is exactly what `Dispatchers.IO` is for. Converting them
 * would not have fixed a bug — it would have renamed one.
 *
 * Revisit if a worker ever gains a unit test, or if a `WorkerFactory` appears for another reason:
 * at that point injection becomes both possible and worth something.
 */
class WorkerDispatcherTest {
  private val sourceDir = File("src/main/java/io/github/mattpvaughn/chronicle")

  /** The files allowed to name `Dispatchers.*` directly, and nothing else. */
  private val exemptWorkers =
    setOf(
      "DownloadNotificationWorker.kt",
      "MoveSyncLocationWorker.kt",
    )

  private fun filesNamingDispatchers(): List<File> =
    sourceDir.walkTopDown()
      .filter { it.extension == "kt" }
      .filter { Regex("""\bDispatchers\.\w+""").containsMatchIn(it.readText()) }
      .toList()

  /**
   * Every `CoroutineWorker` that names `Dispatchers` is on the exemption list.
   *
   * A new worker reaching for `Dispatchers.IO` is probably right to — but it should be a decision,
   * recorded here, rather than drift.
   */
  @Test
  fun `only the listed workers name Dispatchers directly`() {
    val workers =
      sourceDir.walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.readText().contains(": CoroutineWorker(") }
        .toList()

    assertTrue("no CoroutineWorker found — did they move?", workers.isNotEmpty())

    val naming =
      workers
        .filter { Regex("""\bDispatchers\.\w+""").containsMatchIn(it.readText()) }
        .map { it.name }
        .toSet()

    assertEquals(
      "a worker started naming Dispatchers directly, or stopped. Both are fine, but the " +
        "exemption list must say so — see this test's KDoc for why workers are exempt at all",
      exemptWorkers,
      naming,
    )
  }

  /**
   * The exemption covers workers only.
   *
   * This is the half that matters: the list above could otherwise be quietly extended with a
   * repository or a ViewModel, which convention 4 does *not* exempt.
   */
  @Test
  fun `every exempt file really is a CoroutineWorker`() {
    exemptWorkers.forEach { name ->
      val file =
        sourceDir.walkTopDown().firstOrNull { it.name == name }
      assertTrue("$name is on the exemption list but does not exist", file != null)
      assertTrue(
        "$name is exempt from convention 4 but is not a CoroutineWorker — the exemption is " +
          "about WorkManager's reflective construction, and does not apply to anything else",
        file!!.readText().contains(": CoroutineWorker("),
      )
    }
  }

  /**
   * `PlexSyncScrobbleWorker` names no dispatcher, and should not start.
   *
   * Called out by name because it is on the path that protects the listener's position (cu-9), and
   * because the task that produced this test assumed it was one of the offenders — it never was.
   */
  @Test
  fun `the scrobble worker names no dispatcher at all`() {
    val file =
      sourceDir.walkTopDown().firstOrNull { it.name == "PlexSyncScrobbleWorker.kt" }
    assertTrue("PlexSyncScrobbleWorker not found", file != null)

    assertTrue(
      "the scrobble worker gained a Dispatchers reference; it had none, and it is on the " +
        "position-reporting path where WorkManager's own retry and cancellation must stay in charge",
      !Regex("""\bDispatchers\.\w+""").containsMatchIn(file!!.readText()),
    )
  }
}

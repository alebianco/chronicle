package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Who owns the `cached = true` write when a download finishes (cu-138).
 *
 * Two defects, one shape. `DownloadNotificationWorker.doWork` used to end with:
 *
 * ```
 * fetch.getDownloads { downloads ->
 *   CoroutineScope(coroutineContext).launch { … updateCachedStatus(bookId, true) … }
 * }
 * return@withContext Result.success()
 * ```
 *
 * `fetch.getDownloads` is an **async callback**, so it had not necessarily fired by the time the
 * line below it returned; and `CoroutineWorker` cancels its context once `doWork` returns, so even
 * when the callback did fire in time the `launch` was running in a context already being torn
 * down. The write raced its own cancellation and usually lost.
 *
 * It presented intermittently because `CachedFileManager`'s Fetch2 group listener performs the
 * *same* write on an injected long-lived `externalScope`, which survives — so one broken owner was
 * masked by one working one. That is the "downloaded book reports as not downloaded" symptom
 * [[cu-85]] chased from the cache-scan end.
 *
 * **The decision: `CachedFileManager` owns it.** Its listener is a `@Singleton` with an injected
 * scope whose lifetime is not tied to any unit of work, and it is already the reconciliation
 * authority for cache state (it owns the scan, the track-level writes and the uncache path). The
 * worker is a notification renderer whose whole reason to exist ends when its notification does.
 *
 * These assertions are structural, following `CachedFileManagerScopeTest`'s precedent: driving
 * Fetch2 callbacks needs a real `Fetch` and a `BroadcastReceiver`, which is instrumented territory
 * (cu-54). What is cheap and worth pinning is that the worker cannot reacquire the write, and
 * cannot reintroduce a scope tied to its own cancellation.
 */
class DownloadCompletionOwnershipTest {
  private val workerSource: String get() = File(WORKER_PATH).readText()

  private val managerSource: String get() = File(MANAGER_PATH).readText()

  /**
   * Source with comments stripped.
   *
   * Needed because the fix documents the bug it replaced by quoting the old expression, and a
   * naive scan of the raw text then matches the explanation instead of any live code. Stripping
   * comments is the difference between asserting about the code and asserting about the prose.
   */
  private fun String.withoutComments(): String =
    replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
      .replace(Regex("""//[^\n]*"""), "")

  /**
   * Guards the guard: every assertion below reads a source file by relative path, and a wrong
   * path would make all of them vacuously true.
   */
  @Test
  fun `both source files are actually found`() {
    assertTrue("worker source not found at $WORKER_PATH", workerSource.length > 1000)
    assertTrue("manager source not found at $MANAGER_PATH", managerSource.length > 1000)
  }

  /** The bug, in its exact original form: a scope built from the worker's own context. */
  @Test
  fun `the worker never scopes work to its own cancellable context`() {
    assertTrue(
      "CoroutineScope(coroutineContext) inside a CoroutineWorker is cancelled the moment " +
        "doWork returns — the work it launches races its own teardown (cu-138)",
      !Regex("""CoroutineScope\(\s*(coroutineContext|workerContext)\s*\)""")
        .containsMatchIn(workerSource.withoutComments()),
    )
  }

  /** Exactly one owner. The duplicate is what made the broken one invisible. */
  @Test
  fun `only CachedFileManager marks a book cached on download completion`() {
    assertEquals(
      "the worker must not write cached status — CachedFileManager owns it (cu-138)",
      0,
      Regex("""updateCachedStatus""").findAll(workerSource.withoutComments()).count(),
    )
    assertTrue(
      "CachedFileManager must still own the write",
      managerSource.contains("updateCachedStatus"),
    )
  }

  /**
   * The owner's write must stay on the injected scope, not the listener's calling thread —
   * a Fetch2 callback thread is no safer a place to be cancelled than the worker's was.
   */
  @Test
  fun `the owning write runs on the injected external scope`() {
    val onFinished =
      managerSource.withoutComments().substringAfter("override fun onFinished(")
    assertTrue(
      "the cached-status write must be launched on externalScope",
      onFinished.substringBefore("updateCachedStatus").contains("externalScope.launch"),
    )
  }
}

private const val WORKER_PATH =
  "src/main/java/io/github/mattpvaughn/chronicle/features/download/DownloadNotificationWorker.kt"
private const val MANAGER_PATH =
  "src/main/java/io/github/mattpvaughn/chronicle/data/sources/plex/CachedFileManager.kt"

package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the structured-concurrency contract for [CachedFileManager].
 *
 * `GlobalScope.launch` is unstructured: the work cannot be cancelled and its
 * failures have no parent to surface them. Two of the three sites here write to
 * the database, so a failed delete or a failed cache-status update was silent —
 * the UI would keep showing a book as downloaded after its files were gone.
 *
 * These assertions are structural rather than behavioural on purpose. Driving the
 * download callbacks needs a real [com.tonyodev.fetch2.Fetch] and a `BroadcastReceiver`,
 * which is instrumented territory (cu-54); what is cheap and worth pinning here is
 * that the class cannot reach for a global scope again.
 */
class CachedFileManagerScopeTest {
  private val constructorParams
    get() = CachedFileManager::class.constructors.first().parameters

  @Test
  fun `takes an injected coroutine scope`() {
    assertTrue(
      "CachedFileManager must accept a CoroutineScope so its work is cancellable",
      constructorParams.any { it.type.classifier == CoroutineScope::class },
    )
  }

  @Test
  fun `takes an injected dispatcher provider`() {
    assertTrue(
      "CachedFileManager must accept a DispatcherProvider so tests can control threading",
      constructorParams.any { it.type.classifier == DispatcherProvider::class },
    )
  }

  @Test
  fun `source declares no GlobalScope usage`() {
    val source = File(CACHED_FILE_MANAGER_PATH).readText()
    assertTrue(
      "GlobalScope must not reappear in CachedFileManager",
      !source.contains("GlobalScope"),
    )
  }

  @Test
  fun `source declares no hardcoded dispatchers`() {
    val source = File(CACHED_FILE_MANAGER_PATH).readText()
    assertTrue(
      "Dispatchers.IO/Main must come from the injected provider",
      !Regex("""Dispatchers\.(IO|Main|Default)""").containsMatchIn(source),
    )
  }

  private companion object {
    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    const val CACHED_FILE_MANAGER_PATH =
      "src/main/java/io/github/mattpvaughn/chronicle/data/sources/plex/CachedFileManager.kt"
  }
}

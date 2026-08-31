package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass

/**
 * Repositories do their work on [kotlinx.coroutines.Dispatchers.IO]. Hardcoded, that
 * dispatcher cannot be redirected, so a test either sleeps and hopes or skips the
 * threading entirely — which is why none of these classes has a test covering their
 * coroutine behaviour (H5).
 *
 * Scope is repositories only. ViewModels already have `viewModelScope`, and the
 * service/worker lifecycles are a separate question.
 */
class RepositoryDispatcherTest {
  @Test
  fun `repositories take an injected DispatcherProvider`() {
    val missing =
      REPOSITORIES.filterNot { klass ->
        klass.constructors.first().parameters.any {
          it.type.classifier == DispatcherProvider::class
        }
      }.map { it.simpleName }

    assertEquals(
      "these repositories still cannot have their dispatchers controlled by a test",
      emptyList<String>(),
      missing,
    )
  }

  @Test
  fun `repository sources hold no hardcoded dispatchers`() {
    val offenders =
      REPOSITORY_SOURCES.filter { path ->
        Regex("""Dispatchers\.(IO|Main|Default)""").containsMatchIn(File(path).readText())
      }.map { it.substringAfterLast('/') }

    assertEquals(
      "dispatchers must come from the injected provider",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * Guards the guard: a wrong path would make the scan above pass while reading
   * nothing.
   */
  @Test
  fun `repository sources all resolve`() {
    REPOSITORY_SOURCES.forEach { path ->
      assertTrue("expected $path to exist", File(path).exists())
    }
  }

  private companion object {
    val REPOSITORIES: List<KClass<*>> =
      listOf(
        BookRepository::class,
        TrackRepository::class,
        ChapterRepository::class,
        CollectionsRepository::class,
      )

    /** Relative to the `app` module dir, which is the unit tests' working directory. */
    val REPOSITORY_SOURCES: List<String> =
      listOf(
        "BookRepository",
        "TrackRepository",
        "ChapterRepository",
        "CollectionsRepository",
        "LibrarySyncRepository",
      ).map { "src/main/java/io/github/mattpvaughn/chronicle/data/local/$it.kt" }
  }
}

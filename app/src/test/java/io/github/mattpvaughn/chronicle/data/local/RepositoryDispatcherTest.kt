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
   * The player layer, converted in cu-72. Scanned separately from the repositories because
   * `MediaPlayerService` keeps exactly one legitimate `Dispatchers.Main`.
   */
  @Test
  fun `player sources hold no hardcoded IO dispatcher`() {
    val offenders =
      PLAYER_SOURCES.filter { path ->
        Regex("""Dispatchers[.](IO|Default)""").containsMatchIn(File(path).readText())
      }.map { it.substringAfterLast('/') }

    assertEquals(
      "the player's disk and network hops must come from the injected provider",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * `MediaPlayerService.serviceScope` is the one exception, and it is deliberate: `ServiceModule`
   * provides that very scope to the Dagger graph, so it must exist before injection runs — a field
   * initialiser cannot read an injected dispatcher without a circular dependency. Main is also the
   * right dispatcher for a scope driving MediaSession and notification updates.
   *
   * Pinned so the exception stays *one* line and stays explained, rather than becoming precedent.
   */
  @Test
  fun `the service scope is the only hardcoded dispatcher in the player`() {
    // Comment lines are skipped: the exception is *explained* in a comment that names the
    // dispatcher, and counting that would make the assertion depend on the prose.
    val mains =
      File(PLAYER_SOURCES.single { it.endsWith("MediaPlayerService.kt") })
        .readLines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .count { Regex("""Dispatchers[.]Main""").containsMatchIn(it) }

    assertEquals("only the serviceScope declaration may hardcode a dispatcher; see cu-72", 1, mains)
  }

  /**
   * Guards the guard: a wrong path would make the scan above pass while reading
   * nothing.
   */
  @Test
  fun `repository sources all resolve`() {
    (REPOSITORY_SOURCES + PLAYER_SOURCES).forEach { path ->
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

    val PLAYER_SOURCES: List<String> =
      listOf(
        "MediaPlayerService",
        "AudiobookMediaSessionCallback",
        "OnMediaChangedCallback",
        "ProgressUpdater",
      ).map { "src/main/java/io/github/mattpvaughn/chronicle/features/player/$it.kt" }
  }
}

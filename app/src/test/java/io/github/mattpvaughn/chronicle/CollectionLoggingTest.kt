package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `Timber` call must not interpolate a whole collection.
 *
 * `Audiobook.toString()` drags in the serialized `chapters` column, so one `List<Audiobook>`
 * is tens of kilobytes; a measured session produced **3.38 MB across 2920 lines**, all of it
 * built and written on the main thread. The interpolation happens before `Timber` is reached,
 * so a detached release tree does not save it and neither does a `BuildConfig.DEBUG` guard —
 * the string is assembled either way in a debug build (cu-134).
 *
 * The defect is recurrent: cu-110 fixed three instances and declared the class swept, the
 * 2026-09-02 review found three more, and this scan found four the review had missed. Hence a
 * build-breaking check rather than another sweep.
 *
 * ## What this can and cannot check
 *
 * It keys on the *name* of the interpolated expression, not its type. That is a deliberate
 * limit, not an oversight: the two worst historical offenders (`mergedBooks`,
 * `networkChapters`) are declared with inferred types, so nothing short of the compiler knows
 * they are collections. A name heuristic catches the whole known history and every plural in
 * the tree today, at the price of missing a collection with a singular name. Sibling checks
 * take the same shape — see [io.github.mattpvaughn.chronicle.data.sources.plex.TokenLoggingTest].
 *
 * The fix is always a projection: `${books.map { it.id }}`, `${books.size}`, `${books.count()}`.
 * Those are permitted, because the point is to force the log to say something bounded — not to
 * ban logging about collections.
 */
class CollectionLoggingTest {
  @Test
  fun `no production source logs a whole collection`() {
    val offenders =
      File(MAIN_SOURCE_ROOT)
        .walkTopDown()
        .filter { it.extension == "kt" }
        .flatMap { file -> violationsIn(file.readText()).map { "${file.name}: $it" } }
        .sorted()
        .toList()

    assertEquals(
      "log a projection instead — ${'$'}{books.map { it.id }}, ${'$'}{books.size}",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches a plausible number of files`() {
    val scanned = File(MAIN_SOURCE_ROOT).walkTopDown().count { it.extension == "kt" }

    assertTrue("expected the main source root to resolve, found $scanned files", scanned > 100)
  }

  /** And that the matcher can actually fire, on a real instance from the history. */
  @Test
  fun `the matcher detects a known-bad log statement`() {
    val bad = """Timber.i("Loaded books: ${'$'}mergedBooks")"""

    assertEquals(
      "if this fails the sweep above proves nothing",
      listOf("mergedBooks"),
      violationsIn(bad),
    )
  }

  @Test
  fun `a braced interpolation is caught too`() {
    assertEquals(
      listOf("networkChapters"),
      violationsIn("""Timber.i("Network chapters: ${'$'}{networkChapters}")"""),
    )
  }

  @Test
  fun `a projection is permitted`() {
    val projections =
      listOf(
        """Timber.i("tracks: ${'$'}{tracks.map { it.id }}")""",
        """Timber.i("tracks: ${'$'}{tracks.size}")""",
        """Timber.i("tracks: ${'$'}{tracks.count()}")""",
        """Timber.i("tracks: ${'$'}{tracks.joinToString { it.title }}")""",
        """Timber.i("first: ${'$'}{tracks.firstOrNull()?.id}")""",
        // A projection whose own operator name is plural — the shape that flagged this
        // task's fix for `bookDownloads` until the lambda lookahead was added.
        """Timber.i("d: ${'$'}{downloads.mapValues { (_, d) -> d.size }}")""",
      )

    projections.forEach { source ->
      assertEquals("should be allowed: $source", emptyList<String>(), violationsIn(source))
    }
  }

  /**
   * A scalar whose name merely ends in `s` must not be flagged, or the check becomes noise
   * that the next agent baselines away. Every case here is a live call site that the first
   * cut of this matcher flagged wrongly.
   */
  @Test
  fun `a singular name ending in s is not flagged`() {
    val benign =
      listOf(
        """Timber.i("Loading status: ${'$'}loadingStatus")""",
        """Timber.i("Service created! ${'$'}this")""",
        """Timber.e(error, "playback error: ${'$'}diagnosis")""",
        """Timber.i("progress: ${'$'}{request.trackProgress}")""",
        """Timber.i("refresh: ${'$'}{prefsRepo.refreshRateMinutes}")""",
        """Timber.i("duration: ${'$'}durationMillis")""",
        """Timber.i("offset: ${'$'}trueStartTimeOffsetMillis")""",
        """Timber.i("now: ${'$'}{System.currentTimeMillis()}")""",
        """Timber.i("copied: ${'$'}{copied.exists()}")""",
      )

    benign.forEach { source ->
      assertEquals("should not be flagged: $source", emptyList<String>(), violationsIn(source))
    }
  }

  /**
   * A plural unit name is the failure mode that makes a bare "ends in s" rule useless — this
   * tree logs far more `Millis` than it does collections.
   */
  @Test
  fun `an explicit collection suffix is caught regardless of plurality`() {
    assertEquals(
      listOf("trackList"),
      violationsIn("""Timber.i("tracks: ${'$'}trackList")"""),
    )
  }

  /** A pluralized acronym has no lowercase letter before its `s`. */
  @Test
  fun `a pluralized acronym is caught`() {
    assertEquals(
      listOf("activeDownloadIDs"),
      violationsIn("""Timber.i("Active downloads: ${'$'}activeDownloadIDs")"""),
    )
  }

  private companion object {
    /** Relative to the `app` module dir, the unit tests' working directory. */
    const val MAIN_SOURCE_ROOT = "src/main/java"

    /** A `Timber.x( ... )` call including its arguments, across newlines. */
    val TIMBER_CALL =
      Regex("""Timber\.[a-z]\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A bare `$name` or `${name}` interpolation — nothing following that would reduce it.
     *
     * The lookahead rejects `.`, `?`, `(`, `[` and a trailing lambda. That last one is not
     * theoretical: `${'$'}{books.mapValues { … }}` reads as the name `books.mapValues`
     * followed by a space, so without it a legitimate projection gets flagged on the leaf
     * `mapValues` — which is exactly what happened to this task's own fix.
     *
     * `${'$'}{a.b}` is intentionally not matched on `a`: a property chain ending in a plural
     * (`${'$'}{book.chapters}`) is caught by the final segment instead, so the check reads the
     * name that describes the logged value rather than its owner.
     */
    val BARE_INTERPOLATION = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_.]*)\}?(?!\s*\{)(?![\w.?(\[])""")

    /**
     * A name ending in `List`, `Set` or `Map` says its type outright.
     */
    val EXPLICIT_COLLECTION_SUFFIX = Regex("""(?:List|Set|Map)$""")

    /** A pluralized acronym — `activeDownloadIDs`, `trackURLs`. */
    val ACRONYM_PLURAL = Regex("""[A-Z]{2,}s$""")

    /**
     * Plural nouns that are not collections, by *suffix* rather than by whole name.
     *
     * Two groups, and they are the reason a bare plural rule does not work here:
     *
     * - **Units.** `Millis`, `Minutes`, `Seconds`, `Bytes`, `Ms` — the commonest plural nouns
     *   in this tree (`currentTimeMillis`, `refreshRateMinutes`, `durationMillis`) and all
     *   scalars. Anything counted has a plural unit name.
     * - **Latin/irregular singulars.** `status`, `diagnosis`, `progress`, `this`, `exists` —
     *   English singulars that happen to end in `s`.
     *
     * Suffix-matched so `loadingStatus` is covered by `status` without listing it, which is
     * what keeps this from growing a name per call site.
     */
    val SCALAR_SUFFIX =
      Regex(
        """(?:[Mm]illis|[Mm]inutes|[Ss]econds|[Hh]ours|[Dd]ays|[Bb]ytes|[Mm]s|""" +
          """[Ss]tatus|[Dd]iagnosis|[Pp]rogress|[Aa]ddress|[Ss]uccess|[Pp]rocess|""" +
          """[Ee]xists|[Bb]ounds|[Ff]ocus|this)$""",
      )

    fun looksLikeCollection(name: String): Boolean {
      val leaf = name.substringAfterLast('.')
      if (SCALAR_SUFFIX.containsMatchIn(leaf)) return false
      if (EXPLICIT_COLLECTION_SUFFIX.containsMatchIn(leaf)) return true
      if (!leaf.endsWith('s') || leaf.length <= 2) return false
      // A trailing `s` after a lowercase letter: `books`, `tracks`, `tempLibraries`. Not a
      // bare `Books`, which would make every CamelCase word ending in s a collection.
      //
      // The `IDs`/`URLs` shape is an acronym pluralized, so the char before the `s` is
      // uppercase and the lowercase rule alone would miss it — `activeDownloadIDs` is a live
      // `Set<String>` that must be caught.
      return leaf[leaf.length - 2].isLowerCase() || ACRONYM_PLURAL.containsMatchIn(leaf)
    }

    /** The collection names logged bare by any `Timber` call in [source]. */
    fun violationsIn(source: String): List<String> =
      TIMBER_CALL.findAll(source)
        .flatMap { call -> BARE_INTERPOLATION.findAll(call.value) }
        .map { it.groupValues[1] }
        .filter(::looksLikeCollection)
        .toList()
  }
}

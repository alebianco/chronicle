package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import io.github.mattpvaughn.chronicle.data.sources.plex.model.narrators
import io.github.mattpvaughn.chronicle.data.sources.plex.model.seriesName
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Which tag carries which field, by the Audnexus convention.
 *
 * The filter names are Plex's own — the path segment its filter endpoints take — and are not the
 * same as the response keys (`Style`/`Mood`), which is a trap worth naming: the *request* wants
 * `style`, the *response* carries `Style`.
 */
enum class TagFilter(
  val filterName: String,
) {
  /** Narrator. */
  STYLE("style"),

  /** Series. */
  MOOD("mood"),
}

/** One tag value and the books that carry it. */
data class TagAssociation(
  val filter: TagFilter,
  val value: String,
  val bookIds: Set<String>,
)

/**
 * Fills in narrator and series for the whole library, without a request per book.
 *
 * **Why this exists.** `Style` and `Mood` are detail-only: the library listing omits them,
 * so before this the only way to learn a book's narrator was to open it. A fresh install therefore
 * had an almost-empty facet index, and `FacetList.unknownCount` existed to admit as much.
 *
 * **The route.** Plex will enumerate the distinct values of a tag filter for a section, and will
 * filter a listing by one of those values — so the index can be built the other way round: ask for
 * every narrator (one request), then ask which books each narrator read (one request per narrator).
 * That is `1 + N` requests per field rather than one per book, and it yields the association
 * directly rather than deriving it.
 *
 * A CLAUDE.md gotcha claimed an index "cannot be built from a refresh". That was inferred from the
 * listing gap without checking whether another endpoint could enumerate the tags; it can, and this
 * is it.
 *
 * **What is deliberately not here.** The spec also documents a multi-id form,
 * `/library/metadata/{id1},{id2},...`, which would be cheaper still — 2-4 requests for a whole
 * library. It is spec-verified but never live-tested, and python-plexapi does not use it, so it
 * stays a follow-up to try against a real server rather than the mechanism this depends on.
 */
class TagIndexSeeder(
  private val plexMediaService: PlexMediaService,
  private val plexPrefsRepo: PlexPrefsRepo,
  private val dispatchers: DispatcherProvider,
) {
  /**
   * Reads narrator **and** series for [bookIds] in a handful of requests ("Route B").
   *
   * The cheap path. One multi-id metadata request answers both fields for a whole batch of books,
   * where [readAssociations] needs `1 + N` per field: measured against the household server,
   * **196 books in one request, 0.196 s** versus 185 requests for narrators alone.
   *
   * Returns the same [TagAssociation] shape as the `1 + N` walk, so `withSeededTags` and its
   * never-overwrite rule are untouched and the two routes are interchangeable.
   *
   * Failure is **per batch** and never fatal: a library that splits into eight requests should
   * index the seven that answered. An empty result lets the caller fall back to Route A, since the
   * endpoint is spec-documented rather than guaranteed across Plex versions.
   */
  suspend fun readAssociationsByIds(bookIds: List<String>): List<TagAssociation> {
    if (bookIds.isEmpty()) return emptyList()

    val narrators = mutableMapOf<String, MutableSet<String>>()
    val series = mutableMapOf<String, MutableSet<String>>()

    for (batch in batchIdsByUrlLength(bookIds)) {
      val directories =
        withContext(dispatchers.io) {
          try {
            // `metadata`, not `plexDirectories`: a metadata response carries its items under
            // "Metadata" while a *filter choices* response carries them under "Directory". Both
            // deserialize to `PlexDirectory`, so reading the wrong one compiles, returns an empty
            // list, and silently seeds nothing.
            plexMediaService.retrieveAlbums(batch.joinToString(separator = ","))
              .plexMediaContainer.metadata
          } catch (t: Throwable) {
            // Ids only: a list of directories is a collection-shaped log, and which books
            // were asked for is the whole diagnostic.
            Timber.i("Multi-id metadata failed for ${batch.size} books (first ${batch.take(3)}): $t")
            null
          }
        } ?: continue

      for (directory in directories) {
        val id = directory.ratingKey.takeIf { it.isNotEmpty() } ?: continue
        directory.narrators().forEach { narrators.getOrPut(id) { mutableSetOf() }.add(it) }
        directory.seriesName().takeIf { it.isNotEmpty() }
          ?.let { series.getOrPut(id) { mutableSetOf() }.add(it) }
      }
    }

    // Inverted back to value -> books, which is the shape `withSeededTags` consumes. Going through
    // the same type keeps one merge rule rather than two that must agree.
    return buildAssociations(TagFilter.STYLE, narrators) + buildAssociations(TagFilter.MOOD, series)
  }

  private fun buildAssociations(
    filter: TagFilter,
    byBook: Map<String, Set<String>>,
  ): List<TagAssociation> {
    val booksByValue = mutableMapOf<String, MutableSet<String>>()
    for ((bookId, values) in byBook) {
      for (value in values) {
        booksByValue.getOrPut(value) { mutableSetOf() }.add(bookId)
      }
    }
    return booksByValue.map { (value, ids) -> TagAssociation(filter, value, ids) }
  }

  /**
   * Reads every value of [filter] and the books carrying each — the `1 + N` walk ("Route A").
   *
   * Kept as the fallback for [readAssociationsByIds], since the multi-id endpoint is
   * spec-documented rather than guaranteed across Plex versions.
   *
   * Failures are **per value**, not fatal: a library with forty narrators should index
   * thirty-nine of them if one listing fails, rather than none. A failure to enumerate at all
   * returns empty, since there is then nothing to iterate.
   */
  suspend fun readAssociations(filter: TagFilter): List<TagAssociation> {
    val libraryId = plexPrefsRepo.library?.id ?: return emptyList()

    val choices =
      withContext(dispatchers.io) {
        try {
          plexMediaService.retrieveFilterChoices(libraryId, filter.filterName)
            .plexMediaContainer.plexDirectories
        } catch (t: Throwable) {
          // Community-documented endpoint: a server that does not answer it is not an error the
          // user can act on, and the app keeps the per-book fallback it already had.
          Timber.i("Could not enumerate ${filter.filterName} values: $t")
          null
        }
      } ?: return emptyList()

    return choices.mapNotNull { choice -> readOneAssociation(libraryId, filter, choice) }
  }

  private suspend fun readOneAssociation(
    libraryId: String,
    filter: TagFilter,
    choice: PlexDirectory,
  ): TagAssociation? {
    // The tag *id*, never its display text — a filter bound against the title matches nothing.
    val tagKey = choice.key.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
    val value = choice.title.trim().takeIf { it.isNotEmpty() } ?: return null

    val books =
      withContext(dispatchers.io) {
        try {
          when (filter) {
            TagFilter.STYLE ->
              plexMediaService.retrieveAlbumsWithTag(libraryId, styleKey = tagKey)
            TagFilter.MOOD ->
              plexMediaService.retrieveAlbumsWithTag(libraryId, moodKey = tagKey)
          }.plexMediaContainer.asAudiobooks()
        } catch (t: Throwable) {
          Timber.i("Could not list books for ${filter.filterName} '$value': $t")
          null
        }
      } ?: return null

    return TagAssociation(filter = filter, value = value, bookIds = books.map { it.id }.toSet())
  }
}

/**
 * Applies tag associations to the books that have not learned them yet.
 *
 * Pure, so the merge rule is testable without a server — and the rule is the delicate part. It
 * mirrors `Audiobook.merge`'s third rule: a value learned from the *detail* response is
 * authoritative and must not be overwritten by this coarser source, but a book that knows nothing
 * takes what the index offers.
 *
 * Seeding therefore **never overwrites a non-empty field**. A book synced individually has a
 * narrator read straight off its own metadata; the index only says which books a narrator is
 * associated with, which is the same fact arrived at less precisely.
 */
fun List<Audiobook>.withSeededTags(associations: List<TagAssociation>): List<Audiobook> {
  if (associations.isEmpty()) return this

  val narratorsById = mutableMapOf<String, MutableList<String>>()
  val seriesById = mutableMapOf<String, String>()
  for (association in associations) {
    for (id in association.bookIds) {
      when (association.filter) {
        // A full-cast recording carries several narrators, and each arrives as its own value —
        // so they accumulate rather than overwrite, joined the way `Audiobook.from` joins them.
        TagFilter.STYLE -> narratorsById.getOrPut(id) { mutableListOf() }.add(association.value)
        // A book belongs to one series; if a server somehow reports two, the first wins rather
        // than the last, so the result does not depend on map iteration order.
        TagFilter.MOOD -> seriesById.putIfAbsent(id, association.value)
      }
    }
  }

  return map { book ->
    val narrator =
      if (book.narrator.isEmpty()) {
        narratorsById[book.id]?.sorted()?.joinToString(separator = ", ").orEmpty()
      } else {
        book.narrator
      }
    val series =
      if (book.series.isEmpty()) {
        seriesById[book.id]?.let { stripSeriesPrefix(it) }.orEmpty()
      } else {
        book.series
      }
    if (narrator == book.narrator && series == book.series) {
      book
    } else {
      book.copy(narrator = narrator, series = series)
    }
  }
}

/**
 * Strips the `Series:` prefix the Audnexus convention writes into a `Mood` tag.
 *
 * The same normalisation `AudnexusTags.seriesName` applies to a detail response — repeated here
 * rather than shared because that one reads a `PlexTag` list and this reads a filter choice's
 * title, and collapsing the two would make one of them lie about its input.
 */
private fun stripSeriesPrefix(raw: String): String {
  val trimmed = raw.trim()
  for (prefix in listOf("series:", "series -", "series")) {
    if (trimmed.startsWith(prefix, ignoreCase = true)) {
      return trimmed.substring(prefix.length).trim(' ', ':', '-')
    }
  }
  return trimmed
}

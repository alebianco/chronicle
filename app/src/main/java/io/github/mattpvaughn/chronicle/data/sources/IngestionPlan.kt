package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.model.Audiobook

/**
 * What a refresh should write and delete, decided without touching a database (cu-80).
 *
 * `BookRepository.refreshData` welded this decision to Plex fetching and to Room, so nothing about
 * it could be tested and a second source had nowhere to plug in. `SourceManager.refreshBooks` said
 * as much in a `check` that threw: *"neither bookRepository nor trackRepository accepts a
 * caller-supplied list."*
 *
 * Pure over lists, in the style of [io.github.mattpvaughn.chronicle.data.model.ChapterAssembly] and
 * `SleepTimerLogic`, so the two rules that actually matter — which local values survive a merge, and
 * which rows a refresh is allowed to delete — are assertable directly.
 */
data class IngestionPlan(
  /** Rows to insert or replace. */
  val toUpsert: List<Audiobook>,
  /** Ids to delete: books this source used to have and no longer lists. */
  val toRemove: List<String>,
)

/**
 * Merges [fetched] into [local] for one source.
 *
 * **Removal is scoped to [sourceId], and that is the load-bearing part.** The Plex-only path could
 * safely delete every local book absent from its fetch, because there was only ever one source.
 * With two, that same line makes each refresh delete the other's library — silently, and including
 * listening progress the server does not hold. So a book is a removal candidate **only** if it
 * belongs to the source doing the ingesting.
 *
 * A source that fetched nothing is treated as a failed refresh, not an emptied library: an empty
 * [fetched] removes nothing. `refreshData` gets this right by returning early on a network failure;
 * stating it here means a source that answers `Result.success(emptyList())` cannot wipe a library
 * either.
 *
 * [Audiobook.merge] decides field-by-field what survives — local progress is never overwritten by a
 * network copy (decision-16), and a local-only column must be named in both its arms (cu-20). This
 * function decides only *which* books are merged at all.
 */
fun planIngestion(
  fetched: List<Audiobook>,
  local: List<Audiobook>,
  sourceId: Long,
): IngestionPlan {
  val stamped = fetched.map { if (it.source == sourceId) it else it.copy(source = sourceId) }
  val localById = local.associateBy { it.id }
  val merged =
    stamped.map { networkBook ->
      val localBook = localById[networkBook.id]
      if (localBook != null) Audiobook.merge(network = networkBook, local = localBook) else networkBook
    }

  if (stamped.isEmpty()) {
    return IngestionPlan(toUpsert = emptyList(), toRemove = emptyList())
  }

  val fetchedIds = stamped.map { it.id }.toSet()
  val removed =
    local
      .filter { it.source == sourceId }
      .filterNot { it.id in fetchedIds }
      .map { it.id }

  return IngestionPlan(toUpsert = merged, toRemove = removed)
}

package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asCollections
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionsRepository
  @Inject
  constructor(
    private val plexMediaService: PlexMediaService,
    private val prefsRepo: PrefsRepo,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val collectionsDao: CollectionsDao,
    private val dispatchers: DispatcherProvider,
  ) {
    /** The Plex server these collections belong to, as a scoping key (decision-21). */
    private val currentSourceId: SourceId
      get() = SourceId.forPlexServer(plexPrefsRepo.server?.serverId.orEmpty())

    // TODO: handle collections sorting!
    suspend fun getChildIds(collectionId: String): List<String> {
      return collectionsDao.getCollectionAsync(collectionId).childIds
    }

    fun getCollection(id: String): Flow<Collection?> = collectionsDao.getCollection(id)

    fun getAllCollections(): Flow<List<Collection>> = collectionsDao.getAllRows(currentSourceId)

    fun hasCollections(): Flow<Boolean> =
      collectionsDao
        .countCollections(currentSourceId)
        .map { it > 0 }

    /**
     * Claims collections that carry no usable scope for the connected server.
     *
     * Two markers, for two different accidents:
     *
     * - [SourceId.LEGACY_PLEX] is what `COLLECTIONS_MIGRATION_2_3` stamped on rows that predate
     *   the source-scoping migration, exactly as the book and track migrations do.
     * - [SourceId.UNKNOWN] is what this fix addresses: `Collection.from` hardcoded it and the
     *   repository never resolved a real one, so every row written between the source-scoping
     *   migration and this fix — including rows the migration had correctly marked `LEGACY_PLEX`,
     *   which the next refresh then overwrote — is unreachable by every scoped read.
     *
     * Adopting `UNKNOWN` is safe in a way adopting an arbitrary foreign scope would not be: an
     * unscoped row belongs to no server, so there is no first server for a second one to steal it
     * from. `LEGACY_PLEX` carries the same argument, which is why `BookDao.adoptLegacyRows` matches
     * the marker only and never a resolved id.
     */
    suspend fun adoptUnscopedRows() {
      val scope = currentSourceId
      if (!scope.isKnown) return
      withContext(dispatchers.io) {
        val legacy = collectionsDao.adoptLegacyRows(newSource = scope, legacySource = SourceId.LEGACY_PLEX)
        val unscoped = collectionsDao.adoptLegacyRows(newSource = scope, legacySource = SourceId.UNKNOWN)
        if (legacy + unscoped > 0) {
          Timber.i("Adopted ${legacy + unscoped} unscoped collections into the connected server's scope")
        }
      }
    }

    suspend fun refreshCollectionsPaginated() {
      prefsRepo.lastRefreshTimeStamp = System.currentTimeMillis()
      val networkCollections: MutableList<Collection> = mutableListOf()
      withContext(dispatchers.io) {
        try {
          val libraryId = plexPrefsRepo.library?.id ?: return@withContext
          var chaptersLeft = 1L
          // Maximum number of pages of data we fetch. Failsafe in case of bad data from the
          // server since we don't want infinite loops. This limits us to a maximum 1,000,000
          // collections for now
          val maxIterations = 5000
          var i = 0
          while (chaptersLeft > 0 && i < maxIterations) {
            val response =
              plexMediaService
                .retrieveCollectionsPaginated(libraryId, i * 100)
                .plexMediaContainer
            chaptersLeft = response.totalSize - (response.offset + response.size)
            networkCollections.addAll(response.asCollections())
            i++
          }
        } catch (t: Throwable) {
          Timber.i("Failed to retrieve books: $t")
        }
      }

      withContext(dispatchers.io) {
        try {
          // Stamp the connected server's scope, exactly as `planIngestion` does for books.
          // `Collection.from` cannot know which server it is parsing for, so it emits
          // `SourceId.UNKNOWN` and the repository — which does know — resolves it here.
          //
          // An unresolved scope writes **nothing** rather than filing rows under a key no later
          // refresh can match. That is not a hypothetical: before this fix every collection was
          // stored with `UNKNOWN` while `getAllCollections` and `hasCollections` both filtered by
          // `currentSourceId`, so every row was invisible to every read of it and the Collections
          // tab was hidden for every user. `SourceId.UNKNOWN` is inert, never a wildcard.
          val scope = currentSourceId
          if (!scope.isKnown) {
            Timber.i("Skipping collection ingestion: no source resolved")
            return@withContext
          }

          val collectionsWithChildIds =
            networkCollections.map {
              val collectionItems =
                plexMediaService.fetchBooksInCollection(it.id)
                  .plexMediaContainer
                  .asAudiobooks()

              val childIds = collectionItems.map { book -> book.id }
              it.copy(childIds = childIds, source = scope)
            }
          collectionsDao.insertAll(collectionsWithChildIds)
        } catch (t: Throwable) {
          Timber.i("Failed to retrieve books: $t")
        }
      }
    }

    suspend fun clear() {
      collectionsDao.clear()
    }
  }

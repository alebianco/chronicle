package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.TrackRepository
import javax.inject.Inject

class SourceManager
  @Inject
  constructor(
    private val bookRepository: BookRepository,
    private val trackRepository: TrackRepository,
  ) {
    private val sources = mutableListOf<MediaSource>()

    fun getSources(): List<MediaSource> {
      return sources.toList()
    }

    /** Adds a [MediaSource] from [sources] then refreshes data */
    suspend fun addSource(mediaSource: MediaSource) {
      sources.add(mediaSource)
      refreshBooks()
    }

    /** Removes a [MediaSource] from [sources] then refreshes data if the removal succeeded */
    suspend fun removeSource(mediaSource: MediaSource) {
      val removed = sources.remove(mediaSource)
      if (removed) {
        refreshBooks()
      }
    }

    /**
     * Would fetch from every registered [MediaSource] and merge the results into the
     * local repositories.
     *
     * **Not implemented, and deliberately not stubbed as a no-op.** The previous body
     * fetched books and tracks and then discarded both, so a refresh silently persisted
     * nothing — it only escaped notice because [sources] is never populated.
     *
     * Making it work is not a small fix: neither [bookRepository] nor [trackRepository]
     * accepts a caller-supplied list. Each owns its own sync from Plex, so ingesting a
     * merged multi-source list needs a repository API that does not exist yet. That
     * arrives with the second backend (cu-33), which is also the first time this method
     * has anything to merge.
     *
     * Until then this fails loudly the moment a source is added, rather than pretending
     * to work.
     */
    suspend fun refreshBooks() {
      check(sources.isEmpty()) {
        "SourceManager cannot persist fetched media yet: the repositories expose no " +
          "bulk-insert API. See cu-33 before registering a MediaSource."
      }
    }
  }

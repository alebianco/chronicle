package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import timber.log.Timber
import javax.inject.Inject

class SourceManager
  @Inject
  constructor(
    // The **interfaces**, not the concrete repositories (cu-80). Depending on `BookRepository`
    // meant depending on its Plex constructor — `PlexMediaService`, `PlexPrefsRepo` — so the one
    // class whose whole purpose is to be backend-neutral could not be constructed in a test
    // without a Plex stack behind it.
    private val bookRepository: IBookRepository,
    private val trackRepository: ITrackRepository,
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
     * Fetches from every registered [MediaSource] and merges the results into the repositories.
     *
     * Until cu-80 this was a `check` that threw, because *"neither bookRepository nor
     * trackRepository accepts a caller-supplied list"* — each owned its own Plex sync. They accept
     * one now ([io.github.mattpvaughn.chronicle.data.local.IBookRepository.ingest]), so this can do
     * what its name says.
     *
     * **Per source, never in aggregate**, and that is the load-bearing part. Ingesting one merged
     * list would make every refresh delete the books belonging to the other sources, since removal
     * is driven by "local rows this source no longer lists". Each source is ingested against its
     * own id, so one failing or returning nothing cannot empty another's library.
     *
     * A source whose fetch fails is **logged and skipped**, not fatal: one unreachable backend must
     * not stop the others from refreshing, which is the rule the Plex path already applies to the
     * optional tag index (cu-143).
     */
    suspend fun refreshBooks() {
      for (source in sources) {
        val fetched = source.fetchAudiobooks()
        if (fetched.isOk) {
          bookRepository.ingest(fetched.value, source.id, source.capabilities())
        } else {
          Timber.e("Refresh failed for source ${source.id}; leaving its books untouched")
        }
      }
    }
  }

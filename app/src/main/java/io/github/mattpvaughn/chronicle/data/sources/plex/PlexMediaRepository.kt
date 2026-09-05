package io.github.mattpvaughn.chronicle.data.sources.plex

import android.support.v4.media.MediaMetadataCompat
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.features.player.AbstractMediaSource
import io.github.mattpvaughn.chronicle.features.player.toAlbumMediaMetadata
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PlexMediaRepository
  @Inject
  constructor(private val bookRepository: IBookRepository) :
  AbstractMediaSource() {
    private val bookIndex = 0

    /**
     * A snapshot, not a stream: [load] is a `suspend fun` called once, and [iterator] reads the
     * list synchronously. Holding the `Flow` instead would need a collector running for the source's
     * whole life to keep a value available, which is more machinery than a one-shot read deserves.
     */
    private var books: List<Audiobook> = emptyList()

    override suspend fun load() {
      books = bookRepository.getAllBooks().first()
    }

    override fun whenReady(performAction: (Boolean) -> Unit): Boolean {
      performAction(true)
      return true
    }

    // Needs to iterate over books and tracks
    override fun iterator(): Iterator<MediaMetadataCompat> {
      return object : Iterator<MediaMetadataCompat> {
        override fun hasNext(): Boolean {
          return bookIndex < books.size - 1
        }

        override fun next(): MediaMetadataCompat {
          return books[bookIndex].toAlbumMediaMetadata()
        }
      }
    }
  }

package io.github.mattpvaughn.chronicle.data.sources

import androidx.media3.datasource.DefaultDataSource
import com.github.michaelbull.result.Result
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

interface MediaSource {
  /** An ID uniquely representing a specific source. */
  val id: Long

  /**
   * Expose a [DefaultDataSource.Factory] which can transform a [List<MediaItemTrack>] into a
   * [androidx.media3.exoplayer.source.ConcatenatingMediaSource]
   */
  val dataSourceFactory: DefaultDataSource.Factory

  /** Fetch all audiobooks */
  suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable>

  /** Fetch all tracks */
  suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable>

  /**
   * Whether books provided by the source can be downloaded. For example, we could consider
   * local files to not be downloadable, while files provided by a server would be
   */
  val isDownloadable: Boolean

  /**
   * Whether the source carries narrator metadata.
   *
   * Backends differ in what they know about a book, so the UI reads these flags to
   * degrade gracefully rather than rendering a blank field that looks like missing
   * data (decision-11). Plex encodes narrator in `Style` tags by convention; a bare
   * folder of MP3s knows nothing until tags are read (cu-33.2).
   */
  val hasNarrator: Boolean

  /** Whether the source carries series/sequence metadata. */
  val hasSeries: Boolean

  /**
   * Whether the source stores playback progress server-side.
   *
   * When false, local progress is authoritative and there is nothing to sync or to
   * drift against — which is what [io.github.mattpvaughn.chronicle.data.local.BookRepository]
   * and the sync paths need to know.
   */
  val hasServerProgress: Boolean

  companion object {
    const val NO_SOURCE_FOUND = -1L
  }
}

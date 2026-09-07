package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.ktor.client.statement.HttpStatement

/** A [MediaSource] whose authoritative source of truth in accessed via HTTP requests */
interface HttpMediaSource : MediaSource {
  /**
   * Fetches information not included in [fetchTracks] which must be fetched one track at a time,
   * like chapter information
   */
  suspend fun fetchAdditionalTrackInfo(): MediaItemTrack

  /**
   * A file stream from the server, not yet read.
   *
   * An [HttpStatement] rather than OkHttp's `ResponseBody`: the caller decides when to execute and
   * consume it, which is what keeps a whole audiobook out of memory. Same reason the seam no longer
   * exposes a download-library request type — an engine's types do not belong on the multi-backend
   * interface.
   */
  suspend fun fetchStream(url: String): HttpStatement

  /** Updates the playback progress of a [MediaItemTrack] to the server */
  suspend fun updateProgress(
    mediaItemTrack: MediaItemTrack,
    playbackState: String,
  )

  /** Informs the server that a media session has begun */
  suspend fun sendMediaSessionStartCommand()

  /** Return true if the media source can currently be accessed, false otherwise */
  suspend fun isReachable(): Boolean

  /**
   * The fully-resolved URL to download [trackUrl] from.
   *
   * A `String`, not an engine request type. This used to return Fetch2's `Request`, which put a
   * download library's type on the multi-backend seam — so every future backend would have had to
   * speak Fetch2 whether or not it downloaded that way. Auth headers are the client's job
   * (`plexHeadersPlugin`), so a URL is all a source needs to supply.
   */
  fun makeDownloadUrl(trackUrl: String): String

  /** Makes a [LazyHeaders] with the needed HTTP headers */
  fun makeImageRequestHeaders(): Any?

  /** Appends a relative path (i.e. [MediaItemTrack.media]) to the media source's base url */
  fun toServerString(relativePathForResource: String): String
}

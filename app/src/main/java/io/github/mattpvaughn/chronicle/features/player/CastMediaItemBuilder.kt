package io.github.mattpvaughn.chronicle.features.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * The result of preparing a book's tracks for a Cast receiver.
 *
 * [streamedInsteadOfLocal] is not a detail: it is the difference between "casting worked" and
 * "casting silently used mobile data on a book the user had already downloaded", and the UI is
 * expected to say so.
 */
data class CastPlaylist(
  val items: List<CastTrack>,
  val streamedInsteadOfLocal: Boolean,
)

data class CastTrack(
  val uri: String,
  val mimeType: String,
  val title: String,
)

/**
 * Builds the list a Cast receiver will play, given each track's local and server URIs.
 *
 * Two things force a separate builder rather than reusing the ExoPlayer path. A receiver is a
 * different machine, so a downloaded `file://` URI (cu-83) is unreachable and the *server* URI must
 * be substituted — casting a downloaded book streams rather than failing. And the receiver fetches
 * the audio itself, with no access to the OkHttp client's `X-Plex-Token` header, so the token has
 * to travel in the query string instead ([appendTokenTo]).
 *
 * Pure and free of Android and Cast types so the substitution rule is testable — which matters here
 * because no development device has Play services, making this the only place the behaviour can be
 * checked at all.
 */
fun buildCastPlaylist(
  tracks: List<CastSourceCandidate>,
  authToken: String,
): CastPlaylist {
  var substituted = false
  val items =
    tracks.mapNotNull { candidate ->
      val chosen =
        when (castEligibilityOf(candidate.preferredUri)) {
          CastEligibility.Eligible -> candidate.preferredUri
          // Downloaded: the receiver cannot open the file, so stream the same track instead.
          CastEligibility.LocalFileOnly -> {
            substituted = true
            candidate.serverUri
          }
          CastEligibility.NoSource -> candidate.serverUri
        }
      if (castEligibilityOf(chosen) != CastEligibility.Eligible) {
        // No reachable URI for this track. Dropping it silently would cast a book with gaps, so the
        // caller is told the playlist is incomplete by getting fewer items than tracks.
        return@mapNotNull null
      }
      CastTrack(
        uri = appendTokenTo(chosen, authToken),
        mimeType = castMimeTypeOf(chosen),
        title = candidate.title,
      )
    }
  return CastPlaylist(items = items, streamedInsteadOfLocal = substituted)
}

/** A track's two possible sources: where it plays from locally, and where it always lives. */
data class CastSourceCandidate(
  val preferredUri: String,
  val serverUri: String,
  val title: String,
)

/**
 * Puts the Plex token in the query string.
 *
 * The receiver issues its own HTTP requests and cannot be given the `X-Plex-Token` *header* the
 * app's OkHttp client uses, so the query parameter — which Plex accepts equivalently — is the only
 * way it can authenticate. An empty token is left off entirely rather than sent as `token=`, which
 * Plex treats as a malformed request rather than an anonymous one (cu-33's "empty counts as
 * absent").
 */
internal fun appendTokenTo(
  uri: String,
  authToken: String,
): String {
  if (authToken.isEmpty() || uri.contains("X-Plex-Token=")) {
    return uri
  }
  val separator = if (uri.contains('?')) '&' else '?'
  return "$uri$separator" + "X-Plex-Token=$authToken"
}

/**
 * The media3 item a Cast receiver is given.
 *
 * `MediaItem` is a common media3 type, not a Cast one, so this stays outside [CastPlayerProvider] —
 * nothing here loads a Play-services class on a device that has none.
 */
fun CastTrack.asMedia3Item(): MediaItem =
  MediaItem.Builder()
    .setUri(uri)
    .setMimeType(mimeType)
    .setMediaMetadata(
      MediaMetadata.Builder().setTitle(title).build(),
    )
    .build()

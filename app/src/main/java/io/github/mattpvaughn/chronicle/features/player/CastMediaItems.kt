package io.github.mattpvaughn.chronicle.features.player

/**
 * The MIME type a Cast receiver should be told to expect for a track URI.
 *
 * A receiver does not sniff content the way ExoPlayer's extractors do — it decides from the MIME
 * type in the media info, and an absent or wrong one is refused with a generic load error that says
 * nothing about the cause. ExoPlayer never needed this, which is why nothing in the local playback
 * path carries a MIME type to reuse.
 *
 * Plex serves audiobook parts with the container extension in the path, so the extension is the
 * only signal available before playback. `audio/mpeg` is the fallback rather than null because the
 * overwhelming majority of this library is MP3 and a wrong-but-plausible guess degrades to a
 * receiver-side failure, whereas null fails every time.
 */
fun castMimeTypeOf(trackSourceUri: String): String {
  val path = trackSourceUri.substringBefore('?').substringBefore('#')
  return when (path.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    else -> "audio/mpeg"
  }
}

package io.github.mattpvaughn.chronicle.features.player

/**
 * Whether a track can be handed to a Cast receiver, and why not when it cannot.
 *
 * A receiver fetches the audio itself over the network, so the two failure modes are entirely
 * decided by the URI — no Cast SDK type appears here, which is what lets the rules be tested on a
 * machine with no Play services (both development tablets are Play-services-free GSIs, so the
 * on-device path cannot be exercised at all).
 */
sealed interface CastEligibility {
  data object Eligible : CastEligibility

  /**
   * The track resolves to a `file://` URI on this device. A receiver is a separate machine
   * on the network and cannot open it, so casting a downloaded book must stream from the server
   * instead of failing opaquely mid-playback.
   */
  data object LocalFileOnly : CastEligibility

  /** No usable URI at all — nothing to hand over. */
  data object NoSource : CastEligibility
}

/**
 * Classifies a track source URI for casting.
 *
 * Deliberately a string check rather than [android.net.Uri] parsing: this runs per track when a
 * cast begins, and `Uri.parse` is unavailable under plain JVM unit tests, which would push the one
 * rule that most needs covering onto Robolectric.
 */
fun castEligibilityOf(trackSourceUri: String): CastEligibility =
  when {
    trackSourceUri.isBlank() -> CastEligibility.NoSource
    trackSourceUri.startsWith("file://", ignoreCase = true) -> CastEligibility.LocalFileOnly
    trackSourceUri.startsWith("http://", ignoreCase = true) ||
      trackSourceUri.startsWith("https://", ignoreCase = true) -> CastEligibility.Eligible
    // Anything else (content://, a bare path from a legacy row) is not fetchable by a receiver.
    else -> CastEligibility.LocalFileOnly
  }

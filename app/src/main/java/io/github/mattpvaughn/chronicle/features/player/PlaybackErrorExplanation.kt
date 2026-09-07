package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.R

/**
 * What to tell the user about a playback failure.
 *
 * Two cases because the honest answer is sometimes "we do not have a better word for this". A
 * recognised status gets a written explanation; anything else shows the raw diagnosis rather than
 * a generic "playback error" that tells the user nothing and hides the detail from a bug report.
 */
sealed interface PlaybackErrorExplanation {
  /** A recognised failure with a written explanation. */
  data class Resource(val messageRes: Int) : PlaybackErrorExplanation

  /** An unrecognised failure; show what the player actually said. */
  data class Raw(val diagnosis: String) : PlaybackErrorExplanation
}

/**
 * Maps a raw playback diagnosis to what the user should see.
 *
 * Extracted from `MainActivity`'s `BroadcastReceiver` when `LocalBroadcastManager` retired. It was
 * always pure — a string in, a string resource out — but sat inside an `onReceive` that needed a
 * `Context`, an `Intent` and an activity to test, so it never was. Now it is a function over a
 * string, and `PlaybackErrorExplanationTest` covers it.
 *
 * The diagnosis is `describePlaybackError`'s cause-chain description, which is why this matches on
 * a substring: the status code appears inside a longer message such as
 * "InvalidResponseCodeException: Response code: 404", not on its own.
 *
 * A blank or absent diagnosis is [R.string.playback_error_unknown]; the old receiver used that
 * string as the fallback for a missing extra, and it stays the fallback for a message with nothing
 * in it.
 */
fun explainPlaybackError(diagnosis: String): PlaybackErrorExplanation =
  when {
    diagnosis.isBlank() -> PlaybackErrorExplanation.Resource(R.string.playback_error_unknown)
    diagnosis.contains("404") -> PlaybackErrorExplanation.Resource(R.string.playback_error_404)
    diagnosis.contains("503") -> PlaybackErrorExplanation.Resource(R.string.playback_error_503)
    diagnosis.contains("401") -> PlaybackErrorExplanation.Resource(R.string.playback_error_401)
    else -> PlaybackErrorExplanation.Raw(diagnosis)
  }

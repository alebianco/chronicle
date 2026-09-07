package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the diagnosis → message mapping that `MainActivity`'s `BroadcastReceiver` used to hold.
 *
 * It was always pure, but sat inside an `onReceive` needing a `Context`, an `Intent` and an
 * activity, so it had no test. Retiring `LocalBroadcastManager` made it a function over a string;
 * this is the coverage that became possible as a result.
 */
class PlaybackErrorExplanationTest {
  private fun resOf(diagnosis: String): Int = (explainPlaybackError(diagnosis) as PlaybackErrorExplanation.Resource).messageRes

  @Test
  fun `a 404 is explained as a missing file`() {
    // The real shape from `describePlaybackError`, not a bare code — the status is embedded in a
    // longer cause-chain description, which is why the mapping matches on a substring.
    assertEquals(
      R.string.playback_error_404,
      resOf("InvalidResponseCodeException: Response code: 404"),
    )
  }

  @Test
  fun `a 503 is explained as the server being unavailable`() {
    assertEquals(
      R.string.playback_error_503,
      resOf("InvalidResponseCodeException: Response code: 503"),
    )
  }

  @Test
  fun `a 401 is explained as an auth failure`() {
    assertEquals(
      R.string.playback_error_401,
      resOf("InvalidResponseCodeException: Response code: 401"),
    )
  }

  @Test
  fun `an unrecognised diagnosis is shown verbatim`() {
    // Deliberately not flattened to a generic "playback error": the raw text is what makes a
    // report actionable, and hiding it is how a mid-listen stall became undiagnosable before.
    val diagnosis = "SocketTimeoutException: timeout after 20000ms"

    assertEquals(
      PlaybackErrorExplanation.Raw(diagnosis),
      explainPlaybackError(diagnosis),
    )
  }

  @Test
  fun `a blank diagnosis falls back to the unknown message`() {
    // The receiver used this string when the intent extra was missing; a message with nothing in
    // it is the same situation, and showing an empty Toast would tell the user nothing at all.
    assertEquals(R.string.playback_error_unknown, resOf(""))
    assertEquals(R.string.playback_error_unknown, resOf("   "))
  }

  @Test
  fun `the first matching status wins and nothing throws on a multi-code message`() {
    // A cause chain can mention more than one number. The old `when` had the same precedence; this
    // pins it rather than leaving it to whichever branch was written first.
    assertEquals(
      R.string.playback_error_404,
      resOf("Response code: 404 (after a 503 retry)"),
    )
  }
}

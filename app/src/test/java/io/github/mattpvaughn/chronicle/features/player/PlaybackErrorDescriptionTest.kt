package io.github.mattpvaughn.chronicle.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Playback-error diagnosis (cu-103).
 *
 * Written from a real dead end: a book stopped mid-listen every 10-15 minutes and the log held only
 * `Exoplayer playback error: ... Source error`. That names the *category* of failure and nothing
 * else — the HTTP status or IO fault that stopped playback lives in the cause chain.
 */
class PlaybackErrorDescriptionTest {
  /** The exact shape that was undiagnosable: a generic wrapper over the real fault. */
  @Test
  fun `the cause is included, not just the wrapper`() {
    val error = RuntimeException("Source error", IOException("unexpected end of stream"))

    val description = describePlaybackError(error)

    assertTrue(
      "the wrapper alone is what made this undiagnosable: $description",
      description.contains("unexpected end of stream"),
    )
    assertTrue(description.contains("Source error"))
  }

  /** A deeper chain — an HTTP status is often two levels down. */
  @Test
  fun `a nested cause chain is walked to the bottom`() {
    val error =
      RuntimeException(
        "Source error",
        IOException(
          "Unable to connect",
          IllegalStateException("Response code: 401"),
        ),
      )

    val description = describePlaybackError(error)

    assertTrue("the status is the actionable part: $description", description.contains("401"))
  }

  /** A throwable with no message must still identify itself rather than reading as empty. */
  @Test
  fun `a message-less throwable is named by its type`() {
    assertEquals("IOException", describePlaybackError(IOException()))
  }

  @Test
  fun `an error with no cause describes just itself`() {
    assertEquals(
      "IllegalStateException: nothing to play",
      describePlaybackError(IllegalStateException("nothing to play")),
    )
  }

  /**
   * Identical links collapse. A wrapper that copies its cause's message would otherwise repeat the
   * same text three times and push the useful part off the end of a log line.
   */
  @Test
  fun `repeated identical links are collapsed`() {
    val inner = IOException("Source error")
    val error = RuntimeException("Source error", inner)

    assertEquals("RuntimeException: Source error <- IOException: Source error", describePlaybackError(error))
  }

  /**
   * A self-referential cause must not hang the error handler. `Throwable.initCause` refuses `this`,
   * so the cycle is built between two throwables — which is the case the `!==` guard alone does not
   * catch and the depth cap does.
   */
  @Test
  fun `a cyclic cause chain terminates`() {
    val a = IOException("a")
    val b = IOException("b", a)
    a.initCause(b)

    val description = describePlaybackError(a)

    assertTrue("must terminate and still say something useful", description.isNotEmpty())
    assertTrue(description.contains("a"))
  }
}

package io.github.mattpvaughn.chronicle.features.player

import io.mockk.mockk
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test

/**
 * Pins the behaviour that every development device here depends on: no Play services means no Cast,
 * quietly.
 *
 * Both tablets are `Phh-Treble vanilla` GSIs with zero Google packages, so the unavailable path is
 * the *only* one exercised in practice — a crash on it would take the media service down on every
 * launch, and no test in this repo would have noticed.
 */
class CastPlayerProviderTest {
  private val unavailable =
    object : CastAvailability {
      override fun isCastSupported() = false
    }

  @Test
  fun `no play services yields no cast player rather than an exception`() {
    val provider = CastPlayerProvider(mockk(relaxed = true), unavailable)

    assertThat(provider.castPlayerOrNull(), nullValue())
  }

  @Test
  fun `observing sessions is a no-op when cast is unsupported`() {
    val provider = CastPlayerProvider(mockk(relaxed = true), unavailable)
    var notified = false

    provider.observeSessions(onAvailable = { notified = true }, onUnavailable = { notified = true })

    assertThat(notified, equalTo(false))
  }

  @Test
  fun `release is safe before anything was created`() {
    // Called from service teardown, which runs whether or not Cast ever resolved.
    CastPlayerProvider(mockk(relaxed = true), unavailable).release()
  }

  @Test
  fun `availability is consulted once, not per playback`() {
    // CastContext resolution is expensive and its failure is permanent for the process, so a miss
    // must not be retried on every book the user opens.
    var checks = 0
    val counting =
      object : CastAvailability {
        override fun isCastSupported(): Boolean {
          checks++
          return false
        }
      }
    val provider = CastPlayerProvider(mockk(relaxed = true), counting)

    repeat(3) { provider.castPlayerOrNull() }

    assertThat(checks, equalTo(1))
  }
}

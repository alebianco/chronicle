package io.github.mattpvaughn.chronicle.features.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * Pins the rules that decide whether a track can be cast.
 *
 * These matter more than their size suggests: no development device here has Play services, so the
 * on-device path cannot be exercised, and these rules are the part of Cast a machine can prove.
 */
class CastEligibilityTest {
  @Test
  fun `a server url is eligible`() {
    assertThat(
      castEligibilityOf("https://192-168-1-7.abc.plex.direct:32400/library/parts/1/file.mp3"),
      equalTo(CastEligibility.Eligible),
    )
  }

  @Test
  fun `plain http is eligible too`() {
    // Cleartext is refused app-wide by network security config, so this cannot arise from
    // Plex — but a future source could, and the receiver, not this app, does the fetching.
    assertThat(castEligibilityOf("http://example.test/a.mp3"), equalTo(CastEligibility.Eligible))
  }

  @Test
  fun `a downloaded file uri is not castable`() {
    // The exact shape established: Uri.fromFile output, which a receiver cannot reach.
    assertThat(
      castEligibilityOf("file:///storage/emulated/0/chronicle/123.mp3"),
      equalTo(CastEligibility.LocalFileOnly),
    )
  }

  @Test
  fun `a bare path is not castable`() {
    // A legacy row written before this has no scheme at all; it is still a local path.
    assertThat(
      castEligibilityOf("/storage/emulated/0/chronicle/123.mp3"),
      equalTo(CastEligibility.LocalFileOnly),
    )
  }

  @Test
  fun `an empty uri reports no source rather than being treated as local`() {
    assertThat(castEligibilityOf(""), equalTo(CastEligibility.NoSource))
    assertThat(castEligibilityOf("   "), equalTo(CastEligibility.NoSource))
  }

  @Test
  fun `scheme matching ignores case`() {
    assertThat(castEligibilityOf("HTTPS://example.test/a.mp3"), equalTo(CastEligibility.Eligible))
    assertThat(castEligibilityOf("FILE:///tmp/a.mp3"), equalTo(CastEligibility.LocalFileOnly))
  }
}

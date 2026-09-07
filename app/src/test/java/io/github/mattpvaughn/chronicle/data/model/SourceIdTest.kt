package io.github.mattpvaughn.chronicle.data.model

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Test

/**
 * [SourceId] is the scoping key from decision-21, so these pin the two properties the scoping
 * depends on: a Plex server maps to a stable value, and two different servers never collide.
 */
class SourceIdTest {
  @Test
  fun `a plex server id is prefixed so backends cannot collide`() {
    assertThat(SourceId.forPlexServer("abc123").value, equalTo("plex:abc123"))
  }

  @Test
  fun `two different plex servers get different ids`() {
    assertThat(
      SourceId.forPlexServer("server-one"),
      not(equalTo(SourceId.forPlexServer("server-two"))),
    )
  }

  @Test
  fun `the same plex server always maps to the same id`() {
    assertThat(
      SourceId.forPlexServer("stable-identifier"),
      equalTo(SourceId.forPlexServer("stable-identifier")),
    )
  }

  /**
   * A server that has not been chosen yet must be distinguishable from one that *has*. Coalescing
   * the two would file every pre-login row under a real server's scope, where the next refresh
   * for a different server would delete them as absent from its fetch.
   */
  @Test
  fun `an unknown source is not any real plex server id`() {
    assertThat(SourceId.UNKNOWN, not(equalTo(SourceId.forPlexServer("a-real-server"))))
    assertThat(SourceId.UNKNOWN.isKnown, equalTo(false))
    assertThat(SourceId.forPlexServer("a-real-server").isKnown, equalTo(true))
  }

  @Test
  fun `an empty plex identifier is unknown rather than a bare prefix`() {
    assertThat(SourceId.forPlexServer(""), equalTo(SourceId.UNKNOWN))
  }

  @Test
  fun `the room converter round-trips`() {
    val converters = SourceIdConverters()
    val original = SourceId.forPlexServer("round-trip")
    assertThat(converters.toSourceId(converters.fromSourceId(original)), equalTo(original))
  }

  /**
   * The legacy value every existing row carries. the `planIngestion` compared against the
   * `0L` constant, so a migration mapping it to the connected server must have something to map
   * *from* that is not itself a valid server id.
   */
  @Test
  fun `the legacy plex constant is a distinct value`() {
    assertThat(SourceId.LEGACY_PLEX.value, equalTo("plex:legacy"))
    assertThat(SourceId.LEGACY_PLEX, not(equalTo(SourceId.UNKNOWN)))
  }
}

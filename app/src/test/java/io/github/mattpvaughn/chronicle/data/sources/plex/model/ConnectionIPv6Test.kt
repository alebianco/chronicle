package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * The wire key for a connection's IPv6 flag is **`IPv6`**, and Moshi is case-sensitive.
 *
 * Without the explicit `@Json(name = "IPv6")` the property name `iPv6` is what Moshi looks for, so
 * a real response would parse to `false` for every connection — silently, with every test still
 * green, because the hand-written `resources.json` fixture omits the key entirely. That is exactly
 * how `plexGenres` went missing for the life of the project, so the casing is pinned here
 * rather than assumed.
 *
 * The flag is parsed and **not acted on**; see `Connection.iPv6` for why.
 */
@OptIn(ExperimentalStdlibApi::class)
class ConnectionIPv6Test {
  private val adapter = Moshi.Builder().build().adapter<Connection>()

  @Test
  fun `the wire key is IPv6, not the property name`() {
    val parsed = adapter.fromJson("""{"uri":"https://x","IPv6":true}""")

    assertThat(parsed?.iPv6, equalTo(true))
  }

  /** Sabotage guard: if the annotation were dropped, this shape would be the one that parsed. */
  @Test
  fun `the property-name spelling is not accepted`() {
    val parsed = adapter.fromJson("""{"uri":"https://x","iPv6":true}""")

    assertThat(
      "a lowercase-i key must not satisfy the flag, or the annotation is doing nothing",
      parsed?.iPv6,
      equalTo(false),
    )
  }

  @Test
  fun `an absent flag defaults to false rather than failing the parse`() {
    // The shape the household's server actually sends is `"IPv6": false` on all three connections,
    // but an older or trimmed response may omit it; a missing flag must not lose the connection.
    val parsed = adapter.fromJson("""{"uri":"https://x","local":true,"relay":false}""")

    assertThat(parsed?.iPv6, equalTo(false))
    assertThat(parsed?.local, equalTo(true))
  }

  @Test
  fun `an explicit false parses as false`() {
    val parsed = adapter.fromJson("""{"uri":"https://x","IPv6":false}""")

    assertThat(parsed?.iPv6, equalTo(false))
  }
}

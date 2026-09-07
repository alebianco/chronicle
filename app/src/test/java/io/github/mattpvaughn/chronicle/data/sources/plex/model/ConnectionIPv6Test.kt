package io.github.mattpvaughn.chronicle.data.sources.plex.model

import io.github.mattpvaughn.chronicle.data.ChronicleJson
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * The wire key for a connection's IPv6 flag is **`IPv6`**, and the parser is case-sensitive.
 *
 * Without the explicit `@SerialName("IPv6")` the property name `iPv6` is what it looks for, so
 * a real response would parse to `false` for every connection — silently, with every test still
 * green, because the hand-written `resources.json` fixture omits the key entirely. That is exactly
 * how `plexGenres` went missing for the life of the project, so the casing is pinned here
 * rather than assumed.
 *
 * The flag is parsed and **not acted on**; see `Connection.iPv6` for why.
 */
class ConnectionIPv6Test {
  private fun parse(json: String) = ChronicleJson.decodeFromString<Connection>(json)

  @Test
  fun `the wire key is IPv6, not the property name`() {
    val parsed = parse("""{"uri":"https://x","IPv6":true}""")

    assertThat(parsed.iPv6, equalTo(true))
  }

  /** Sabotage guard: if the annotation were dropped, this shape would be the one that parsed. */
  @Test
  fun `the property-name spelling is not accepted`() {
    val parsed = parse("""{"uri":"https://x","iPv6":true}""")

    assertThat(
      "a lowercase-i key must not satisfy the flag, or the annotation is doing nothing",
      parsed.iPv6,
      equalTo(false),
    )
  }

  @Test
  fun `an absent flag defaults to false rather than failing the parse`() {
    // The shape the household's server actually sends is `"IPv6": false` on all three connections,
    // but an older or trimmed response may omit it; a missing flag must not lose the connection.
    val parsed = parse("""{"uri":"https://x","local":true,"relay":false}""")

    assertThat(parsed.iPv6, equalTo(false))
    assertThat(parsed.local, equalTo(true))
  }

  @Test
  fun `an explicit false parses as false`() {
    val parsed = parse("""{"uri":"https://x","IPv6":false}""")

    assertThat(parsed.iPv6, equalTo(false))
  }

  /**
   * A connection survives being written and read back, under the wire spelling.
   *
   * Not a redundant round-trip: `SharedPreferencesPlexPrefsRepo` **writes** connections to
   * preferences and reads them on the next launch, so this direction is real production behaviour
   * and not just the inverse of a parse. It is also the direction the annotation could break
   * asymmetrically — a wrong `@SerialName` writes `iPv6` and reads `IPv6`, so a flag would survive
   * one launch and vanish on the next, which is far harder to see than never parsing at all.
   */
  @Test
  fun `a connection round-trips through the stored form`() {
    val connection =
      Connection(
        uri = "https://192-168-1-7.abc.plex.direct:32400",
        local = true,
        relay = false,
        protocol = "https",
        iPv6 = false,
      )

    val written = ChronicleJson.encodeToString(connection)

    assertThat("the wire spelling must be written, not the property name", written.contains("\"IPv6\""), equalTo(true))
    assertThat(parse(written), equalTo(connection))
  }
}

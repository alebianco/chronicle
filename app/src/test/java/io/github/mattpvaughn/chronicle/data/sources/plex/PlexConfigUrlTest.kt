package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PlexConfig.toServerString`, the join every Plex URL in the app is built from.
 *
 * Untested until cu-33 because the class fetched its `Context` from the service locator in two
 * methods, so constructing one on the JVM threw. It now takes the context as a constructor
 * parameter, and the join — which is pure and has four cases, two of which are the ones that
 * produce a doubled or missing slash — is finally reachable.
 */
class PlexConfigUrlTest {
  private fun config(url: String): PlexConfig =
    PlexConfig(
      plexPrefsRepo = FakePlexPrefsRepo(),
      connectionChooser = mockk(relaxed = true),
      appContext = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(),
    ).apply { this.url = url }

  @Test
  fun `a bare base and a rooted path join with one slash`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400").toServerString("/library/sections"),
    )
  }

  /**
   * The case the function exists for, and the one it used to get wrong (cu-160).
   *
   * It stripped the path's leading slash and then added one back, so `//` came out — not cosmetic
   * to Plex, which matches a path rather than normalising it. Unreachable from live data (every
   * `/api/v2/resources` uri arrives without a trailing slash), fixed anyway because `url` is a
   * public `var`.
   */
  @Test
  fun `a trailing slash and a rooted path do not double the slash`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400/").toServerString("/library/sections"),
    )
  }

  /**
   * All four combinations agree. A Plex path is matched, not normalised, so `//` and `/` are
   * different paths and only one of them exists on the server.
   */
  @Test
  fun `all four slash combinations produce the same url`() {
    val results =
      listOf(
        config("https://server:32400").toServerString("/library/sections"),
        config("https://server:32400/").toServerString("/library/sections"),
        config("https://server:32400").toServerString("library/sections"),
        config("https://server:32400/").toServerString("library/sections"),
      )

    assertEquals(setOf("https://server:32400/library/sections"), results.toSet())
  }

  /** Several trailing or leading slashes still collapse to one, for the same reason. */
  @Test
  fun `repeated slashes on either side collapse to one`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400///").toServerString("///library/sections"),
    )
  }
}

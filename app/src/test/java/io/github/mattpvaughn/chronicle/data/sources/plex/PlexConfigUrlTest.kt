package io.github.mattpvaughn.chronicle.data.sources.plex

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
    ).apply { this.url = url }

  @Test
  fun `a bare base and a rooted path join with one slash`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400").toServerString("/library/sections"),
    )
  }

  /**
   * **Characterisation, not endorsement.** When *both* sides carry a slash the branch strips the
   * path's and then adds one back — `"$url/" + path.substring(1)` — so the result is `//`, which
   * is precisely the case the KDoc claims to account for. Left alone here: cu-33 is a DI carve, and
   * quietly changing how every Plex URL is built is not something to smuggle into one. Filed as
   * cu-160; this test is what will fail loudly when that fix lands, which is the point of writing
   * it down now.
   */
  @Test
  fun `a trailing slash and a rooted path currently double the slash`() {
    assertEquals(
      "https://server:32400//library/sections",
      config("https://server:32400/").toServerString("/library/sections"),
    )
  }

  @Test
  fun `a bare base and a relative path gain the separating slash`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400").toServerString("library/sections"),
    )
  }

  @Test
  fun `a trailing slash and a relative path join unchanged`() {
    assertEquals(
      "https://server:32400/library/sections",
      config("https://server:32400/").toServerString("library/sections"),
    )
  }

  /**
   * Three of the four combinations agree; the fourth is the doubled slash above. Asserting the
   * count rather than "they all match" keeps this honest about today's behaviour while still
   * failing if a *second* combination ever diverges.
   */
  @Test
  fun `only the both-slashes case diverges from the others`() {
    val results =
      listOf(
        config("https://server:32400").toServerString("/library/sections"),
        config("https://server:32400/").toServerString("/library/sections"),
        config("https://server:32400").toServerString("library/sections"),
        config("https://server:32400/").toServerString("library/sections"),
      )

    assertEquals(setOf("https://server:32400/library/sections", "https://server:32400//library/sections"), results.toSet())
  }
}

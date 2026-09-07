package io.github.mattpvaughn.chronicle.views.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [CoverImage] asks the network for.
 *
 * The decision is a pure function so it can be asserted directly: `AsyncImage` is asynchronous and
 * Robolectric has no network, so a rendering assertion cannot tell "requested the right url" from
 * "requested nothing".
 */
class CoverImageContractTest {
  private val toServerUrl: (String) -> String = { "https://plex.example$it" }

  @Test
  fun `a connected server with artwork is requested`() {
    assertEquals(
      "https://plex.example/library/metadata/1001/thumb/1600000001",
      coverModel("/library/metadata/1001/thumb/1600000001", serverConnected = true, toServerUrl),
    )
  }

  /**
   * Offline asks for nothing.
   *
   * Not a fallback to a cached url or a retry: an unreachable host would be retried per item, on
   * every scroll, for a whole library.
   */
  @Test
  fun `an unreachable server is not asked`() {
    assertNull(coverModel("/library/metadata/1001/thumb/1", serverConnected = false, toServerUrl))
  }

  /**
   * A book with no artwork asks for nothing either.
   *
   * This is the case the old call sites got wrong in a second way: an empty `thumb` still built a
   * url — the server root — so the request was made *and* could not produce an image.
   */
  @Test
  fun `a book with no artwork is not asked for`() {
    assertNull(coverModel("", serverConnected = true, toServerUrl))
  }

  @Test
  fun `an absent thumb is not asked for even when connected`() {
    // `orEmpty()` at the nullable call sites (player, details) lands here.
    assertNull(coverModel(null.orEmpty(), serverConnected = true, toServerUrl))
  }
}

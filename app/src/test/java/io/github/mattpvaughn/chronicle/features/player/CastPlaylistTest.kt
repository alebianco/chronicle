package io.github.mattpvaughn.chronicle.features.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.Test

/**
 * Pins the substitution rule that makes casting a *downloaded* book work.
 *
 * This is the acceptance criterion the task called silent: a receiver cannot open a `file://` URI,
 * and nothing about the failure would point at the cause. No development device has Play services,
 * so these tests are the only proof available for the rule.
 */
class CastPlaylistTest {
  private fun candidate(
    preferred: String,
    server: String = SERVER_URI,
    title: String = "Chapter One",
  ) = CastSourceCandidate(preferredUri = preferred, serverUri = server, title = title)

  @Test
  fun `a streaming book casts its own urls unchanged apart from the token`() {
    val playlist = buildCastPlaylist(listOf(candidate(SERVER_URI)), TOKEN)

    assertThat(playlist.streamedInsteadOfLocal, equalTo(false))
    assertThat(playlist.items, hasSize(1))
    assertThat(playlist.items[0].uri, containsString("/library/parts/1/file.mp3"))
  }

  @Test
  fun `a downloaded book is streamed from the server instead of failing`() {
    val playlist = buildCastPlaylist(listOf(candidate("file:///storage/emulated/0/1.mp3")), TOKEN)

    // The whole point: the file:// URI must not reach the receiver.
    assertThat(playlist.items[0].uri, not(containsString("file://")))
    assertThat(playlist.items[0].uri, containsString("/library/parts/1/file.mp3"))
    // And the caller must be able to tell the user this is now streaming.
    assertThat(playlist.streamedInsteadOfLocal, equalTo(true))
  }

  @Test
  fun `a track with no reachable uri is dropped rather than casting a broken item`() {
    val playlist = buildCastPlaylist(listOf(candidate("file:///a.mp3", server = "")), TOKEN)

    assertThat(playlist.items, hasSize(0))
  }

  @Test
  fun `track order is preserved`() {
    val playlist =
      buildCastPlaylist(
        listOf(
          candidate(SERVER_URI, title = "One"),
          candidate("file:///b.mp3", title = "Two"),
          candidate(SERVER_URI, title = "Three"),
        ),
        TOKEN,
      )

    assertThat(playlist.items.map { it.title }, contains("One", "Two", "Three"))
  }

  @Test
  fun `the token travels in the query string because a receiver cannot send our header`() {
    val playlist = buildCastPlaylist(listOf(candidate(SERVER_URI)), TOKEN)

    assertThat(playlist.items[0].uri, containsString("X-Plex-Token=$TOKEN"))
  }

  @Test
  fun `a uri that already carries a token is not given a second one`() {
    val withToken = "$SERVER_URI?X-Plex-Token=existing"
    val playlist = buildCastPlaylist(listOf(candidate(withToken)), TOKEN)

    assertThat(playlist.items[0].uri, equalTo(withToken))
  }

  @Test
  fun `an existing query string is extended rather than replaced`() {
    val playlist = buildCastPlaylist(listOf(candidate("$SERVER_URI?download=0")), TOKEN)

    assertThat(playlist.items[0].uri, containsString("download=0"))
    assertThat(playlist.items[0].uri, containsString("&X-Plex-Token=$TOKEN"))
  }

  @Test
  fun `an empty token is omitted rather than sent as an empty parameter`() {
    // An empty token is *absent*, and Plex reads `X-Plex-Token=` as malformed rather than
    // anonymous — so sending it would turn "no token" into a hard failure.
    val playlist = buildCastPlaylist(listOf(candidate(SERVER_URI)), "")

    assertThat(playlist.items[0].uri, not(containsString("X-Plex-Token")))
  }

  @Test
  fun `mime type is derived per track`() {
    val playlist =
      buildCastPlaylist(
        listOf(
          candidate("https://s/a.mp3"),
          candidate("https://s/b.m4b"),
        ),
        TOKEN,
      )

    assertThat(playlist.items.map { it.mimeType }, contains("audio/mpeg", "audio/mp4"))
  }

  private companion object {
    const val SERVER_URI = "https://192-168-1-7.abc.plex.direct:32400/library/parts/1/file.mp3"
    const val TOKEN = "tokenvalue"
  }
}

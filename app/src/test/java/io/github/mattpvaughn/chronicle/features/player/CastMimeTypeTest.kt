package io.github.mattpvaughn.chronicle.features.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/** Pins the MIME types handed to a Cast receiver, which does not sniff content the way ExoPlayer does. */
class CastMimeTypeTest {
  @Test
  fun `common audiobook containers map to their mime types`() {
    assertThat(castMimeTypeOf("https://s/a.mp3"), equalTo("audio/mpeg"))
    assertThat(castMimeTypeOf("https://s/a.m4b"), equalTo("audio/mp4"))
    assertThat(castMimeTypeOf("https://s/a.m4a"), equalTo("audio/mp4"))
    assertThat(castMimeTypeOf("https://s/a.opus"), equalTo("audio/opus"))
    assertThat(castMimeTypeOf("https://s/a.flac"), equalTo("audio/flac"))
  }

  @Test
  fun `a query string does not become part of the extension`() {
    // Plex part URLs carry ?X-Plex-Token=..., so naive substringAfterLast would read the token.
    assertThat(
      castMimeTypeOf("https://s/library/parts/1/file.m4b?X-Plex-Token=abc"),
      equalTo("audio/mp4"),
    )
  }

  @Test
  fun `an unknown or absent extension falls back to mpeg rather than failing`() {
    assertThat(castMimeTypeOf("https://s/library/parts/1/file"), equalTo("audio/mpeg"))
    assertThat(castMimeTypeOf("https://s/a.xyz"), equalTo("audio/mpeg"))
  }

  @Test
  fun `extension matching ignores case`() {
    assertThat(castMimeTypeOf("https://s/a.M4B"), equalTo("audio/mp4"))
  }
}

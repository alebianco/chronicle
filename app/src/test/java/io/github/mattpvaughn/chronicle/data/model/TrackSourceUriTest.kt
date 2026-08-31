package io.github.mattpvaughn.chronicle.data.model

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A cached track's source must be a URI ExoPlayer can recognise as a local file.
 *
 * `getTrackSource()` returned `File(...).absolutePath` — a bare path with no scheme. That string
 * becomes `METADATA_KEY_MEDIA_URI`, and `MediaMetadataCompat.mediaUri` parses it with `toUri()`:
 *
 * ```
 * "/data/user/0/app/files/3001.mp3".toUri()  ->  scheme = null
 * ```
 *
 * A schemeless URI reaching `ProgressiveMediaSource`/`DefaultDataSource` is not resolved as a file,
 * which surfaces to the user as an unsupported-format error **on downloaded books only** — the
 * uncached branch is fine because `toServerString` yields a proper `https://` URL. It also explains
 * the workarounds the owner found: offline mode and a fresh launch take different paths through
 * track resolution.
 *
 * These tests exercise the URI construction directly rather than through `getTrackSource()`, which
 * reaches the Dagger graph via `Injector.get()` for `cachedMediaDir` and `plexConfig` and so cannot
 * be constructed in a unit test (the same coupling cu-79 is about). What is pinned here is the
 * property that was wrong: the string handed to the player must parse to a URI with a scheme.
 */
@RunWith(RobolectricTestRunner::class)
class TrackSourceUriTest {
  private val cachedDir = File("/data/user/0/io.github.mattpvaughn.chronicle/files")

  @Test
  fun `a bare absolute path parses to a URI with no scheme`() {
    // The bug, pinned so the reason for cachedTrackUri is not lost.
    val bare = File(cachedDir, "3001.mp3").absolutePath

    assertEquals(
      "a bare path has no scheme, so ExoPlayer cannot resolve it as a file",
      null,
      bare.toUri().scheme,
    )
  }

  @Test
  fun `a cached track source carries the file scheme`() {
    val source = MediaItemTrack.cachedTrackUri(cachedDir, "3001.mp3")

    assertEquals("file", source.toUri().scheme)
  }

  @Test
  fun `a cached track source still resolves to the right path`() {
    val source = MediaItemTrack.cachedTrackUri(cachedDir, "3001.mp3")

    assertEquals("/data/user/0/io.github.mattpvaughn.chronicle/files/3001.mp3", source.toUri().path)
  }

  /**
   * A path with a space or a non-ASCII character must be **percent-encoded** in the URI string.
   *
   * Asserted on the raw string, not on `Uri.path`: `path` decodes, so it returns the same value for
   * a properly encoded URI and for naive `"file://" + path` concatenation — verified, and it made
   * an earlier version of this test pass against exactly the sloppy implementation it was meant to
   * reject. A literal space in a URI handed to a `DataSource` is what actually breaks.
   */
  @Test
  fun `a cached track source percent-encodes spaces and non-ascii`() {
    val awkward = File("/storage/emulated/0/Audio Books/Æther")
    val source = MediaItemTrack.cachedTrackUri(awkward, "3001.mp3")

    assertEquals(
      "file:///storage/emulated/0/Audio%20Books/%C3%86ther/3001.mp3",
      source,
    )
    assertFalse("a raw space must never reach the DataSource", source.contains(' '))
    // And it still decodes back to the real path.
    assertEquals("/storage/emulated/0/Audio Books/Æther/3001.mp3", source.toUri().path)
  }

  @Test
  fun `the server source is left as an absolute http url`() {
    // Guards the other branch: it must not be turned into a file URI.
    val serverUrl = "https://192-168-1-7.abc.plex.direct:32400/library/parts/1/file.mp3"

    assertEquals("https", serverUrl.toUri().scheme)
    assertNotNull(serverUrl.toUri().host)
  }
}

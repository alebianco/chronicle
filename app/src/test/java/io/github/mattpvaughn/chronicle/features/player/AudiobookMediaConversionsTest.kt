package io.github.mattpvaughn.chronicle.features.player

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.core.net.toUri
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.EXTRA_IS_DOWNLOADED
import io.github.mattpvaughn.chronicle.data.sources.plex.EXTRA_PLAY_COMPLETION_STATE
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.STATUS_NOT_PLAYED
import io.github.mattpvaughn.chronicle.data.sources.plex.STATUS_PARTIALLY_PLAYED
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two conversions that put a book in front of **Android Auto**, previously uncovered.
 *
 * `toMediaItem` is what `onLoadChildren` hands the car, and `toAlbumMediaMetadata` is what the
 * session publishes — so a wrong field here is a wrong title or a missing cover on a head unit,
 * which is the hardest surface to inspect. A live crash was found in this area for exactly that
 * reason (`mediaController.metadata` is platform-typed and was dereferenced unguarded).
 *
 * Robolectric rather than a plain JVM test: `MediaDescriptionCompat`, `Bundle` and
 * `MediaMetadataCompat` are real framework types. The review that prompted this had called such
 * code "unreachable by a JVM unit test" — it is not, and this project already runs Robolectric in
 * 40 suites. Only the `PlexConfig` collaborator is mocked, because `makeThumbUri` reads a
 * dimension resource and the server's token.
 */
@RunWith(RobolectricTestRunner::class)
class AudiobookMediaConversionsTest {
  private fun book(
    progress: Long = 0L,
    isCached: Boolean = false,
  ) = Audiobook(
    id = "1001",
    source = TEST_SOURCE,
    title = "Mistborn",
    author = "Brandon Sanderson",
    genre = "Fantasy",
    thumb = "/library/metadata/1001/thumb",
    progress = progress,
    isCached = isCached,
  )

  private val plexConfig =
    mockk<PlexConfig> {
      every { makeThumbUri(any()) } returns "https://server/photo".toUri()
    }

  @Test
  fun `album metadata carries the fields a media session displays`() {
    val metadata = book().toAlbumMediaMetadata()

    assertEquals("1001", metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
    assertEquals("Mistborn", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
    assertEquals("Mistborn", metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM))
    assertEquals("Brandon Sanderson", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    assertEquals("Fantasy", metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE))
  }

  @Test
  fun `a media item carries the title author and id the car browses by`() {
    val item = book().toMediaItem(plexConfig)

    assertEquals("1001", item.mediaId)
    assertEquals("Mistborn", item.description.title)
    assertEquals("Brandon Sanderson", item.description.subtitle)
    assertTrue("a browsed book must be playable", item.isPlayable)
  }

  /**
   * The completion state is a branch on `progress == 0L`, and Auto renders it as the partial-play
   * marker on the tile. An unstarted book showing as partially played — or the reverse — is the
   * kind of thing nobody notices until they are driving.
   */
  @Test
  fun `an unstarted book is marked not played`() {
    val extras = book(progress = 0L).toMediaItem(plexConfig).description.extras!!

    assertEquals(
      STATUS_NOT_PLAYED,
      extras.getInt(EXTRA_PLAY_COMPLETION_STATE),
    )
  }

  @Test
  fun `a started book is marked partially played`() {
    val extras = book(progress = 5_000L).toMediaItem(plexConfig).description.extras!!

    assertEquals(
      STATUS_PARTIALLY_PLAYED,
      extras.getInt(EXTRA_PLAY_COMPLETION_STATE),
    )
  }

  /**
   * The downloaded flag is what lets Auto show a book as available offline — the one piece of
   * state a car genuinely needs, since it may be out of network range.
   */
  @Test
  fun `the downloaded flag reflects whether the book is cached`() {
    val cached = book(isCached = true).toMediaItem(plexConfig).description.extras!!
    val notCached = book(isCached = false).toMediaItem(plexConfig).description.extras!!

    assertTrue(cached.getBoolean(EXTRA_IS_DOWNLOADED))
    assertFalse(notCached.getBoolean(EXTRA_IS_DOWNLOADED))
  }

  @Test
  fun `the artwork uri is resolved through plex config rather than passed raw`() {
    val item = book().toMediaItem(plexConfig)

    assertEquals("https://server/photo", item.description.iconUri.toString())
  }

  @Test
  fun `a media item is flagged playable rather than browsable`() {
    val item = book().toMediaItem(plexConfig)

    assertEquals(MediaBrowserCompat.MediaItem.FLAG_PLAYABLE, item.flags)
  }
}

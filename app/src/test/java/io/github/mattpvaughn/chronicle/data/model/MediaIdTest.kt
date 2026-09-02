package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Media
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Part
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asCollections
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asTrackList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ids that would escape the cache directory (cu-111).
 *
 * An id is server-controlled data that becomes a **filename**: a downloaded track is written to
 * `File(cachedMediaDir, "$id.$extension")`, and `File(parent, child)` does not normalize. So an id
 * of `../../../../databases/BookDatabase` writes attacker-controlled bytes into app-private
 * storage, next to the Room databases and `ChronicleAuth.xml`.
 *
 * Reaching this requires the *server* to be hostile — the app refuses cleartext app-wide (cu-42),
 * so a network attacker cannot inject a response. That is why the response is to drop the item and
 * keep going rather than fail the sync.
 *
 * Ids are `String` since cu-71 so a non-numeric backend can be represented (decision-11), so the
 * rule cannot be "digits only". These tests pin both directions: the traversal shapes are refused,
 * and the legitimate non-numeric shapes are not.
 */
class MediaIdTest {
  @Test
  fun `an ordinary numeric id is valid`() {
    assertTrue(MediaId.isValid("3001"))
    assertTrue(MediaId.isValid("1"))
  }

  /** The reason ids are `String` at all — an ABS or WebDAV id must survive. */
  @Test
  fun `a non-numeric id is valid`() {
    assertTrue(MediaId.isValid("li_8x2h9fk3"))
    assertTrue(MediaId.isValid("abc-123_x"))
    assertTrue(MediaId.isValid("book:1001"))
    assertTrue(MediaId.isValid("1001.2"))
  }

  @Test
  fun `the traversal primitives are refused`() {
    assertFalse(MediaId.isValid(".."))
    assertFalse(MediaId.isValid("."))
  }

  /** The exploit shape from the review, and the variants that reach the same place. */
  @Test
  fun `a path traversal is refused`() {
    assertFalse(MediaId.isValid("../../../../databases/BookDatabase"))
    assertFalse(MediaId.isValid("../x"))
    assertFalse(MediaId.isValid("a/../b"))
    assertFalse(MediaId.isValid("..a"))
  }

  @Test
  fun `a path separator is refused`() {
    assertFalse(MediaId.isValid("a/b"))
    assertFalse(MediaId.isValid("/etc/passwd"))
    assertFalse(MediaId.isValid("a\\b"))
  }

  /**
   * NUL terminates a string in native code, so a name that validates as a whole can resolve to a
   * shorter path once it reaches a native `open()`.
   */
  @Test
  fun `an embedded NUL is refused`() {
    assertFalse(MediaId.isValid("3001\u0000/../../x"))
  }

  @Test
  fun `an empty or blank id is refused`() {
    assertFalse(MediaId.isValid(""))
    assertFalse(MediaId.isValid("   "))
  }

  /**
   * The property that actually matters, asserted on the filesystem rather than on the predicate:
   * for every id the validator accepts, the destination stays inside the cache directory.
   *
   * This is the test that would have caught the original bug — `isValid` could be weakened in some
   * subtle way and still pass the cases above, but this one resolves real paths.
   */
  @Test
  fun `an accepted id never escapes the cache directory`() {
    val cacheDir = File("/data/data/io.github.mattpvaughn.chronicle/files/media")
    val candidates =
      listOf(
        "3001", "li_8x2h9fk3", "abc-123_x", "book:1001", "1001.2",
        "../../../../databases/BookDatabase", "../x", "a/../b", "/etc/passwd", "a\\b", "..",
      )

    candidates.filter { MediaId.isValid(it) }.forEach { id ->
      val dest = File(cacheDir, "$id.mp3")
      assertTrue(
        "an accepted id must not escape the cache dir, but '$id' resolved to ${dest.canonicalPath}",
        dest.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator),
      )
    }
  }

  /**
   * The converse, so the test above cannot pass vacuously: these ids really do escape.
   *
   * Verified against the JVM's own `File(parent, child)` semantics rather than assumed, because
   * intuition is wrong here in both directions. Two ids that *look* like escapes are not:
   *
   * - `a/../b` canonicalizes back to `<cacheDir>/b`.
   * - `/etc/passwd` is treated as **relative** by `File(parent, child)`, so it lands at
   *   `<cacheDir>/etc/passwd` — Java differs from a naive path join here.
   *
   * Both are still refused, because a separator lets the server dictate the on-disk layout inside
   * our directory (subdirectories nothing cleans up). But they are refused for that reason, not
   * for escaping — and an earlier draft of this test claimed otherwise and failed twice, which is
   * exactly what the "proves nothing" message exists to catch. Overstating an exploit hides which
   * part of a guard is actually load-bearing.
   */
  @Test
  fun `the rejected traversal ids really would have escaped`() {
    val cacheDir = File("/data/data/io.github.mattpvaughn.chronicle/files/media")

    listOf("../../../../databases/BookDatabase", "../x").forEach { id ->
      val dest = File(cacheDir, "$id.mp3")
      assertFalse(
        "'$id' should have escaped the cache dir — if it does not, this test proves nothing",
        dest.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator),
      )
    }
  }

  /**
   * A separator that does *not* escape is still refused: it lets the server choose the on-disk
   * layout inside our own directory, creating subdirectories that no cleanup path knows about.
   */
  @Test
  fun `a separator that stays inside the directory is still refused`() {
    assertFalse(MediaId.isValid("a/../b"))
    assertFalse(MediaId.isValid("sub/dir"))
    assertFalse(MediaId.isValid("/etc/passwd"))
  }

  // --- The chokepoint: where a server response becomes local models. ---

  private fun dir(
    id: String,
    title: String = "Dune",
  ) = PlexDirectory(
    ratingKey = id,
    title = title,
    parentRatingKey = 1001,
    // `fromPlexModel` reads `media[0].part[0]` unguarded, so a bare fixture throws before the
    // filter is even reached.
    media = listOf(Media(part = listOf(Part(key = "/library/parts/$id/file.mp3", size = 100L)))),
  )

  private fun container(vararg dirs: PlexDirectory) = PlexMediaContainer(metadata = dirs.toList())

  @Test
  fun `a book with an unsafe id is dropped, and the rest survive`() {
    val books =
      container(
        dir("1001"),
        dir("../../../../databases/BookDatabase", title = "Evil"),
        dir("1002"),
      ).asAudiobooks()

    assertEquals(listOf("1001", "1002"), books.map { it.id })
  }

  @Test
  fun `a track with an unsafe id is dropped`() {
    val tracks =
      container(dir("2001"), dir("../x", title = "Evil")).asTrackList()

    assertEquals(listOf("2001"), tracks.map { it.id })
  }

  @Test
  fun `a collection with an unsafe id is dropped`() {
    val collections =
      container(dir("5001"), dir("/etc/passwd", title = "Evil")).asCollections()

    assertEquals(listOf("5001"), collections.map { it.id })
  }

  /** A clean response must pass through untouched — the filter must not cost anything real. */
  @Test
  fun `a clean response is unchanged`() {
    val books = container(dir("1001"), dir("1002"), dir("li_abc")).asAudiobooks()

    assertEquals(listOf("1001", "1002", "li_abc"), books.map { it.id })
  }
}

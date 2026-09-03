package io.github.mattpvaughn.chronicle.data.sources.plex.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A book row may only come from *album* metadata.
 *
 * Found by looking at the Continue Listening shelf on a device (cu-18): it showed two entries for
 * one book — "The Hobbit", and "An Unexpected Party" whose author read "The Hobbit" and whose
 * `leafCount` was 0. That second row was a **track** sitting in the `Audiobook` table. It arrives
 * through `fetchBookAsync` → `retrieveAlbum(bookId)` → `asAudiobooks()` → `bookDao.update`, and
 * `update` is `@Insert(REPLACE)`, so it *inserts* a row that does not exist rather than failing.
 *
 * `retrieveAlbum` and `retrieveChapterInfo` are the **same URL with the same query parameters**
 * (`/library/metadata/{id}?includeChapters=1`), so nothing about the request says which shape is
 * expected back. The only guard available is the response's own `type`.
 *
 * A phantom book is a bad failure to leave silent: the user sees an entry they cannot explain,
 * cannot play, and cannot remove. Same rule as cu-15 — fail loudly rather than ingest quietly.
 */
class AsAudiobooksTypeTest {
  @Test
  fun `album metadata becomes a book`() {
    val container = containerOf(directory(id = "1001", type = "album", title = "The Hobbit"))

    assertEquals(listOf("1001"), container.asAudiobooks().map { it.id })
  }

  /** The live defect: a track container must yield nothing at all. */
  @Test
  fun `track metadata yields no books`() {
    val container =
      containerOf(
        directory(id = "2001", type = "track", title = "An Unexpected Party"),
        directory(id = "2002", type = "track", title = "Roast Mutton"),
      )

    assertEquals(emptyList<String>(), container.asAudiobooks().map { it.id })
  }

  @Test
  fun `a mixed container keeps only the albums`() {
    val container =
      containerOf(
        directory(id = "1001", type = "album", title = "The Hobbit"),
        directory(id = "2001", type = "track", title = "An Unexpected Party"),
        directory(id = "1002", type = "album", title = "Dune"),
      )

    assertEquals(listOf("1001", "1002"), container.asAudiobooks().map { it.id })
  }

  /**
   * **An absent `type` is accepted.** This is the case that decides whether the guard is safe to
   * ship: `type` is not documented as guaranteed, and a server that omits it would have its entire
   * library dropped by a strict check — turning a cosmetic duplicate into an empty app. Accepting
   * the unknown keeps the failure mode the one we already had.
   */
  @Test
  fun `metadata with no type is accepted rather than dropped`() {
    val container = containerOf(directory(id = "1001", type = "", title = "The Hobbit"))

    assertEquals(listOf("1001"), container.asAudiobooks().map { it.id })
  }

  /** An unrecognised type is also accepted, for the same reason. Only a *known* non-album goes. */
  @Test
  fun `an unrecognised type is accepted`() {
    val container = containerOf(directory(id = "1001", type = "audiobook", title = "The Hobbit"))

    assertEquals(listOf("1001"), container.asAudiobooks().map { it.id })
  }

  /** The other known non-album shapes this endpoint could return. */
  @Test
  fun `artist and collection metadata yield no books`() {
    val container =
      containerOf(
        directory(id = "3001", type = "artist", title = "J R R Tolkien"),
        directory(id = "4001", type = "collection", title = "Middle-earth"),
      )

    assertEquals(emptyList<String>(), container.asAudiobooks().map { it.id })
  }

  /** The id guard still applies, and independently of the type one. */
  @Test
  fun `an album with an unsafe id is still dropped`() {
    val container =
      containerOf(
        directory(id = "../../etc/passwd", type = "album", title = "Bad"),
        directory(id = "1001", type = "album", title = "The Hobbit"),
      )

    assertEquals(listOf("1001"), container.asAudiobooks().map { it.id })
  }

  private fun containerOf(vararg directories: PlexDirectory) = PlexMediaContainer(metadata = directories.toList())

  private fun directory(
    id: String,
    type: String,
    title: String,
  ) = PlexDirectory(ratingKey = id, type = type, title = title)
}

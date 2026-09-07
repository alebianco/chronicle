package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [planIngestion] — what a refresh writes and deletes, for one source.
 *
 * The rules under test are the two that can lose a user's data: which local values survive a merge,
 * and which rows a refresh may delete. Both were previously welded inside
 * `BookRepository.refreshData` next to a Plex network call, so neither could be asserted at all.
 */
class IngestionPlanTest {
  private val plex = SourceId.forPlexServer("server-a")
  private val other = SourceId.forPlexServer("server-b")

  private fun book(
    id: String,
    source: SourceId = plex,
    title: String = "Book $id",
    progress: Long = 0L,
  ) = EMPTY_AUDIOBOOK.copy(id = id, source = source, title = title, progress = progress)

  @Test
  fun `a book the source no longer lists is removed`() {
    val plan = planIngestion(fetched = listOf(book("1")), local = listOf(book("1"), book("2")), sourceId = plex)

    assertEquals(listOf("2"), plan.toRemove)
  }

  /**
   * The single most dangerous line in this change.
   *
   * Removal used to be "every local book absent from the fetch", which was safe only while there
   * was exactly one source. With two, each refresh would delete the other's entire library —
   * silently, and taking listening progress no server holds a copy of.
   */
  @Test
  fun `a refresh never removes another source's books`() {
    val plan =
      planIngestion(
        fetched = listOf(book("1")),
        local = listOf(book("1"), book("99", source = other)),
        sourceId = plex,
      )

    assertEquals("another source's book must survive", emptyList<String>(), plan.toRemove)
  }

  /** A failed or empty fetch is not an emptied library. */
  @Test
  fun `an empty fetch removes nothing`() {
    val plan = planIngestion(fetched = emptyList(), local = listOf(book("1"), book("2")), sourceId = plex)

    assertEquals(emptyList<String>(), plan.toRemove)
    assertEquals(emptyList<Audiobook>(), plan.toUpsert)
  }

  /**
   * Listening position is owned by the tracks and a network copy never overwrites it
   * (decision-16). `planIngestion` delegates that to `Audiobook.merge`; this asserts it
   * really is delegated, since an ingestion path that bypassed `merge` would look identical until
   * someone lost their place in a book.
   */
  @Test
  fun `local progress survives ingestion`() {
    val plan =
      planIngestion(
        fetched = listOf(book("1", progress = 0L)),
        local = listOf(book("1", progress = 900_000L)),
        sourceId = plex,
      )

    assertEquals(900_000L, plan.toUpsert.single().progress)
  }

  @Test
  fun `a book absent locally is inserted as new`() {
    val plan = planIngestion(fetched = listOf(book("5")), local = emptyList(), sourceId = plex)

    assertEquals(listOf("5"), plan.toUpsert.map { it.id })
  }

  /**
   * A source that hands back books without stamping its own id still gets them attributed to it —
   * otherwise they would default to source 0 (Plex) and the next Plex refresh would delete them as
   * "no longer listed".
   */
  @Test
  fun `fetched books are stamped with the ingesting source`() {
    val plan = planIngestion(fetched = listOf(book("1", source = plex)), local = emptyList(), sourceId = other)

    assertEquals(other, plan.toUpsert.single().source)
  }

  @Test
  fun `a book belonging to this source that is still listed is kept`() {
    val plan =
      planIngestion(fetched = listOf(book("1"), book("2")), local = listOf(book("1"), book("2")), sourceId = plex)

    assertTrue(plan.toRemove.isEmpty())
    assertEquals(2, plan.toUpsert.size)
  }

  /**
   * An unresolved scope must be inert, not a wildcard.
   *
   * [SourceId.UNKNOWN] reaches ingestion when no server is chosen — mid-login, or after a
   * `PlexConfig.clear()`. Stamping rows with it would file them under a key no later refresh
   * matches, so neither the removal rule nor any scoped read would ever see them again: a
   * catalogue that grows and can never be pruned.
   */
  @Test
  fun `an unresolved source writes nothing`() {
    val plan =
      planIngestion(
        fetched = listOf(book("1"), book("2")),
        local = listOf(book("3")),
        sourceId = SourceId.UNKNOWN,
      )

    assertEquals(emptyList<Audiobook>(), plan.toUpsert)
    assertEquals("an unresolved scope must not delete either", emptyList<String>(), plan.toRemove)
  }
}

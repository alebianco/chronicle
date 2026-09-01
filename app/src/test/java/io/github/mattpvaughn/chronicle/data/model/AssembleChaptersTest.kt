package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fallback inside the chapter-loading path, where *some* tracks may lack server chapter data.
 *
 * `BookRepository` and `ChapterRepository` both wrote this inline as
 * `listOf(track.asChapter(0L))` — a literal zero offset for every track. Chapter offsets are
 * absolute within the book, so in a multi-file book with no server chapters every chapter claimed
 * to start at 0, and `getChapterAt` then resolves the wrong chapter or none at all.
 */
class AssembleChaptersTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long,
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    index = index,
    duration = duration,
    title = "Track $index",
  )

  private val tracks =
    listOf(
      track("2001", 1, 1_000L),
      track("2002", 2, 2_000L),
      track("2003", 3, 3_000L),
    )

  /** The regression: with no server chapters anywhere, offsets must still accumulate. */
  @Test
  fun `fallback chapters get cumulative offsets, not zero`() {
    val chapters = assembleChapters(tracks) { emptyList() }

    assertEquals(listOf(0L, 1_000L, 3_000L), chapters.map { it.bookStartTimeOffset })
  }

  @Test
  fun `a fallback chapter is produced for every track with no server data`() {
    assertEquals(3, assembleChapters(tracks) { emptyList() }.size)
  }

  @Test
  fun `server chapters are used when present`() {
    val chapters =
      assembleChapters(tracks) { track ->
        listOf(
          Chapter(
            title = "Server ${track.index}",
            id = "4${track.index}",
            index = track.index.toLong(),
            trackId = track.id,
            bookId = "1001",
            bookStartTimeOffset = 0L,
            bookEndTimeOffset = track.duration,
          ),
        )
      }

    assertEquals(3, chapters.size)
    assertEquals(listOf("Server 1", "Server 2", "Server 3"), chapters.map { it.title })
  }

  /**
   * The mixed case, which is what makes the running offset non-trivial: a track the server
   * answered for must still advance the offset used by a later track that it did not.
   */
  @Test
  fun `a track without server chapters is offset past the tracks before it`() {
    val chapters =
      assembleChapters(tracks) { track ->
        if (track.index == 1) {
          listOf(
            Chapter(
              title = "Server 1",
              id = "41",
              index = 1L,
              trackId = track.id,
              bookId = "1001",
              bookStartTimeOffset = 0L,
              bookEndTimeOffset = 1_000L,
            ),
          )
        } else {
          emptyList()
        }
      }

    val fallbacks = chapters.filter { it.title.startsWith("Track") }
    assertEquals(
      "a fallback must start after the tracks preceding it, server-answered or not",
      listOf(1_000L, 3_000L),
      fallbacks.map { it.bookStartTimeOffset },
    )
  }

  @Test
  fun `fallback chapters carry their book and track`() {
    val chapters = assembleChapters(tracks) { emptyList() }

    assertEquals(listOf("1001", "1001", "1001"), chapters.map { it.bookId })
    assertEquals(listOf("2001", "2002", "2003"), chapters.map { it.trackId })
  }

  @Test
  fun `no tracks yields no chapters`() {
    assertEquals(emptyList<Chapter>(), assembleChapters(emptyList()) { emptyList() })
  }
}

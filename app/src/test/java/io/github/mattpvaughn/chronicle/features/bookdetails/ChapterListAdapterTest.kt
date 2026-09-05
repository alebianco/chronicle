package io.github.mattpvaughn.chronicle.features.bookdetails

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.features.bookdetails.ChapterListAdapter.ChapterListModel
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chapter list's **section headers** and **active-chapter tracking** — 621 instructions that
 * sat at 0%.
 *
 * Two decisions worth pinning, both invisible until a specific library shape reaches them:
 *
 *  1. Disc headers appear **only** when the book actually spans discs, decided by the last
 *     chapter's `discNumber`. Inserting them unconditionally would put a "Disc 1" banner above
 *     every single-disc book — most of the library.
 *  2. `isActive` is part of `areContentsTheSame`, which is what moves the highlight as playback
 *     crosses a chapter boundary. `PreferenceModel.defaultValue` carries a comment about exactly
 *     this hazard: a field omitted from the contents comparison makes a row silently stop
 *     repainting, and nothing fails — it just stops updating.
 *
 * Robolectric because `ListAdapter` reaches for the framework's `Looper` when it diffs.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterListAdapterTest {
  private fun chapter(
    id: String,
    index: Long,
    discNumber: Int = 1,
    title: String = "Chapter $index",
    trackId: String = "t1",
  ) = Chapter(
    id = id,
    title = title,
    index = index,
    discNumber = discNumber,
    bookStartTimeOffset = BookOffset.ZERO,
    trackId = trackId,
  )

  private fun adapter() = ChapterListAdapter(mockk<TrackClickListener>(relaxed = true))

  // `currentList`, not `getItem`: the latter is protected on `ListAdapter`.
  private fun ChapterListAdapter.models(): List<ChapterListModel> = currentList

  @Test
  fun `a single-disc book gets no section headers`() {
    val adapter = adapter()

    adapter.submitChapters(
      listOf(chapter("1", 1), chapter("2", 2), chapter("3", 3)),
    )

    assertEquals(3, adapter.itemCount)
    assertTrue(
      "a single-disc book must not be given a Disc 1 banner",
      adapter.models().none { it is ChapterListModel.SectionHeaderWrapper },
    )
  }

  /**
   * The trigger is the **last** chapter's disc number, so a book only grows headers once it
   * genuinely spans discs — and then it gets one for the first disc too, or disc 1's chapters
   * would sit under no heading at all while the rest are labelled.
   */
  @Test
  fun `a multi-disc book gets a header for every disc including the first`() {
    val adapter = adapter()

    adapter.submitChapters(
      listOf(
        chapter("1", 1, discNumber = 1),
        chapter("2", 2, discNumber = 1),
        chapter("3", 3, discNumber = 2),
        chapter("4", 4, discNumber = 3),
      ),
    )

    val headers = adapter.models().filterIsInstance<ChapterListModel.SectionHeaderWrapper>()
    assertEquals("one header per disc", 3, headers.size)
    assertEquals("4 chapters + 3 headers", 7, adapter.itemCount)
  }

  @Test
  fun `a header precedes the chapters it introduces`() {
    val adapter = adapter()

    adapter.submitChapters(
      listOf(chapter("1", 1, discNumber = 1), chapter("2", 2, discNumber = 2)),
    )

    val models = adapter.models()
    assertTrue("the list must open with a header", models.first() is ChapterListModel.SectionHeaderWrapper)
    assertTrue(models[1] is ChapterListModel.ChapterItemModel)
    assertTrue(models[2] is ChapterListModel.SectionHeaderWrapper)
    assertTrue(models[3] is ChapterListModel.ChapterItemModel)
  }

  @Test
  fun `an empty chapter list produces an empty adapter`() {
    val adapter = adapter()

    adapter.submitChapters(emptyList())

    assertEquals(0, adapter.itemCount)
  }

  // ---- active chapter ----

  @Test
  fun `no chapter is active before one is nominated`() {
    val adapter = adapter()

    assertFalse(adapter.isActive(chapter("1", 1)))
  }

  /**
   * All three of track, disc and index must match. Index alone is not enough: a multi-track book
   * restarts chapter indices per track, so two different chapters legitimately share one — which
   * would highlight both.
   */
  @Test
  fun `the active chapter is identified by track disc and index together`() {
    val adapter = adapter()

    adapter.updateCurrentChapter(trackId = "t2", discNumber = 1, chapterIndex = 3L)

    assertTrue(adapter.isActive(chapter("x", 3, trackId = "t2")))
    assertFalse("a different track must not match", adapter.isActive(chapter("x", 3, trackId = "t1")))
    assertFalse("a different index must not match", adapter.isActive(chapter("x", 4, trackId = "t2")))
    assertFalse(
      "a different disc must not match",
      adapter.isActive(chapter("x", 3, discNumber = 2, trackId = "t2")),
    )
  }

  /**
   * The highlight has to *move*. `isActive` is compared in `areContentsTheSame`, so a change to it
   * must produce a different model — otherwise `DiffUtil` reports no change and the row keeps its
   * old appearance while playback has moved on.
   */
  @Test
  fun `changing the active chapter moves which chapter reports itself active`() {
    val adapter = adapter()
    val one = chapter("1", 1, trackId = "t1")
    val two = chapter("2", 2, trackId = "t1")

    adapter.updateCurrentChapter("t1", 1, 1L)
    assertTrue("chapter 1 is active first", adapter.isActive(one))
    assertFalse(adapter.isActive(two))

    adapter.updateCurrentChapter("t1", 1, 2L)
    assertFalse("the highlight must move off chapter 1", adapter.isActive(one))
    assertTrue("and onto chapter 2", adapter.isActive(two))
  }

  /**
   * `submitChapters(null)` keeps the chapters it already holds — measured, not assumed: the first
   * version of this test asserted the list emptied, and it does not. That is the right behaviour,
   * since the ViewModel emits null while a source has not arrived yet and dropping the list on
   * that would blank a populated screen mid-playback.
   */
  @Test
  fun `a null submission after a real one does not blank the list`() {
    val adapter = adapter()
    adapter.submitChapters(listOf(chapter("1", 1), chapter("2", 2)))

    adapter.submitChapters(null)

    assertEquals("a not-yet-emitted source must not empty the screen", 2, adapter.itemCount)
  }
}
